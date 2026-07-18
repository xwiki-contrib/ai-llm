/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.llm.mcp.internal.tool;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPReachAwareParams;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that deletes an XWiki document, moving it to the recycle bin.
 *
 * <p>This is a default tool bundled with the MCP server module. Deletion is destructive but always
 * recoverable: the document goes to the recycle bin (an administrator can restore it from there), and on
 * a wiki with no recycle bin the deletion is refused entirely. A mandatory {@code base_version} (the
 * version the agent read with {@code get_document}) doubles as proof of a recent read, so a deletion is
 * never based on a stale or absent view of the content being deleted.</p>
 *
 * <p>There is no recursive delete: deleting the home page of a space that still has child pages is
 * refused, because it would orphan them. The single exception is a home page whose only remaining child
 * is the space's {@code WebPreferences} page (space settings): the platform's delete UI special-cases
 * that situation by defaulting to delete the {@code WebPreferences} child along with the page, so it
 * never blocks the delete; this tool instead deletes only the home page and reports that
 * {@code WebPreferences} remains.</p>
 *
 * <p>Rights- and configuration-bearing documents ({@code WebPreferences}, {@code XWikiPreferences}, the
 * main wiki's wiki descriptors and the MCP server configuration document) are refused outright, with a
 * pointer to delete them manually in the wiki UI.</p>
 *
 * <p>Resolution and authorization both go through {@link MCPDocumentAccess} for the delete right before
 * the document is loaded, so the per-wiki space filter is applied and the existence of a protected
 * document is never leaked. The delete right is re-checked at deletion time through
 * {@link com.xpn.xwiki.api.Document#hasAccessLevel(String)}; the deletion itself goes through
 * {@link com.xpn.xwiki.XWiki#deleteAllDocuments(XWikiDocument, XWikiContext)} so every translation of
 * the page is deleted with the default document, matching the platform UI's delete. The delete right
 * attaches to the locale-free document reference - there is no per-translation right (the platform's
 * own delete job checks once, then deletes all translations). The platform records the context user as
 * the deleter when it moves the documents to the recycle bin.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPDeleteDocumentTool.TOOL_ID)
@Singleton
public class MCPDeleteDocumentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "delete_document";

    private static final String REFERENCE_PARAM = "reference";

    private static final String BASE_VERSION_PARAM = "base_version";

    /**
     * Number of child page names fetched by the children guard: enough to distinguish "no children",
     * "exactly one child" (the {@code WebPreferences} case) and "more than one child".
     */
    private static final int CHILD_FETCH_LIMIT = 2;

    private static final String WEB_HOME = "WebHome";

    /**
     * The children lookup of the platform delete UI (flamingo {@code delete.vm}): every document under
     * the space prefix except the document itself, deliberately including hidden documents and
     * translations. The short XWQL form selects {@code doc.fullName}.
     */
    private static final String CHILDREN_STATEMENT =
        "where doc.fullName like :space and doc.fullName <> :fullName";

    /**
     * Count variant of {@link #CHILDREN_STATEMENT}, used only to report an accurate child count in the
     * refusal message.
     */
    private static final String COUNT_STATEMENT =
        "select count(doc.fullName) from Document doc " + CHILDREN_STATEMENT;

    private static final String SPACE_BIND = "space";

    private static final String FULLNAME_BIND = "fullName";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    /**
     * The agent-facing result message for a deletion that failed with a storage-level error. The root
     * cause stays in the server logs, off the wire.
     */
    private static final String DELETE_FAILED_MESSAGE = "Could not delete the document. Try again; if it "
        + "persists, report it to a wiki administrator (details are in the server logs).";

    /**
     * The agent-facing result when the deletion itself failed partway. The platform deletes the
     * translation rows one by one (default document last) with no transaction across them, so some
     * translations can already sit in the recycle bin while the document itself survives. Retrying is
     * safe: the default document holds the {@code base_version}, and it is deleted last.
     */
    private static final String DELETE_INCOMPLETE_MESSAGE = "The deletion failed partway: some translations "
        + "may already have been moved to the recycle bin while the document itself still exists. Re-read "
        + "it with get_document and retry the deletion; if it persists, report it to a wiki administrator "
        + "(details are in the server logs).";

    /**
     * The refusal for a wiki without a recycle bin: this tool never performs an unrecoverable delete.
     */
    private static final String NO_RECYCLE_BIN_MESSAGE = "This wiki has no recycle bin, so deletion would "
        + "be permanent. Refusing; delete via the wiki UI if you really intend this.";

    /**
     * The fail-closed refusal when the children guard itself failed: without knowing whether children
     * exist, the deletion is not performed.
     */
    private static final String CHILD_CHECK_FAILED_MESSAGE = "Could not verify whether this document has "
        + "child pages, so the deletion was refused. Try again; if it persists, report it to a wiki "
        + "administrator (details are in the server logs).";

    /**
     * The success-result note for the {@code WebPreferences}-only-child case.
     */
    private static final String WEB_PREFERENCES_NOTE =
        "Note: the space's WebPreferences page (space settings) remains.";

    /**
     * Shared opening of the refusal messages that name the refused document.
     */
    private static final String REFUSING_TO_DELETE = "Refusing to delete " + QUOTE;

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description so
     * no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPDeleteDocumentTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private QueryManager queryManager;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example and the cross-wiki
     * sentence in the {@code reference} description only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The reference of the document to delete, e.g. \"Sandbox.OldPage\" "
            + "or \"" + (crossWiki ? "xwiki:" : "") + "Help.Foo\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(BASE_VERSION_PARAM, "The document version you last read, shown by "
                + "get_document. Always required: fetch the document with get_document first and pass its "
                + "version, so a deletion is always based on a recent read. The deletion is refused if the "
                + "document has changed since.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = PARAMS.advertised(this.wikiReach.isReachEnabled());
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description("Delete an XWiki document, moving it to the recycle bin (never permanent). "
                + "All translations of the page are deleted with it. Requires base_version from "
                + "get_document - read the document first. Deleting the home page of a space that still "
                + "has child pages is refused; there is no recursive delete.")
            .build();
    }

    @Override
    public boolean isWrite()
    {
        return true;
    }

    @Override
    public String getCategory()
    {
        return "Authoring";
    }

    @Override
    public String getSummary()
    {
        return "Delete a document (moves it to the recycle bin).";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                delete_document moves the document to the recycle bin - it never deletes permanently.
                An administrator can restore it from the recycle bin. On a wiki with no recycle bin
                the deletion is refused entirely.
                All translations of the page are deleted with it (they all go to the recycle bin),
                matching the wiki UI's own delete. There is no per-translation delete: translations
                are created and edited via write_document/edit_document locale=..., but deletion is
                always whole-document.
                base_version is required: read the document with get_document first and pass the
                version it shows, so every deletion is based on a recent read of what is deleted.
                base_version covers the default-locale document; translation content has its own
                version history and is not version-checked.
                There is no recursive delete: deleting the home page of a space that still has child
                pages is refused, because they would be orphaned. Delete or move the children first.
                Exception: a home page whose only remaining child is the space's WebPreferences page
                (space settings) can be deleted; the WebPreferences page remains.

            EXAMPLES
                Delete:      get_document reference="Sandbox.Old" (note its Version line),
                             then delete_document reference="Sandbox.Old", base_version="2.1"
                Conflict:    a "Version conflict" result means the document changed since you read
                             it - re-read it with get_document and retry if you still intend it.

            SEE ALSO
                man get_document    Read a document's source and current version.
                man write_document  Create a document or replace its entire content.
                man                 (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
            String baseVersion = PARAMS.parser().requireString(args, BASE_VERSION_PARAM);

            DocumentReference ref = MCPWriteSupport.resolveFor(this.documentAccess, reference, Right.DELETE);

            return checkAndDelete(ref, reference, baseVersion);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP delete_document tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP delete_document tool failure details", e);
            return MCPToolSupport.errorResult(DELETE_FAILED_MESSAGE);
        }
    }

    /**
     * Runs the deletion guards inside the target wiki, then deletes: the document must exist, must not
     * be rights- or configuration-bearing (checked before the version so a denylisted page is refused
     * regardless of version), the {@code base_version} must match the current version, the wiki must
     * have a recycle bin and a space home page must have no children (bar the {@code WebPreferences}
     * exception).
     *
     * <p>The checks are best-effort: a concurrent save landing between them and the delete can still
     * win. The window is milliseconds, the platform's own delete action carries the same race with no
     * version check at all, and the recycle bin bounds the damage.</p>
     *
     * @param ref the resolved and authorized document reference
     * @param reference the original reference string, for error messages
     * @param baseVersion the version the agent read
     * @return the tool result
     * @throws XWikiException when loading or deleting the document fails
     */
    private McpSchema.CallToolResult checkAndDelete(DocumentReference ref, String reference, String baseVersion)
        throws XWikiException
    {
        return MCPWriteSupport.inTargetWiki(this.contextProvider.get(), ref, (xcontext, xdoc) -> {
            if (xdoc.isNew()) {
                return MCPToolSupport.errorResult("Document " + QUOTE + reference + QUOTE
                    + " does not exist; nothing to delete.");
            }
            McpSchema.CallToolResult refusal = sensitiveRefusal(xcontext, ref);
            if (refusal != null) {
                return refusal;
            }
            String currentVersion = xdoc.getVersion();
            if (!baseVersion.equals(currentVersion)) {
                return MCPToolSupport.errorResult(MCPWriteSupport.versionConflictError(
                    MCPWriteSupport.DOCUMENT_SUBJECT, currentVersion, baseVersion,
                    "retry the deletion if you still intend it."));
            }
            if (!xcontext.getWiki().hasRecycleBin(xcontext)) {
                return MCPToolSupport.errorResult(NO_RECYCLE_BIN_MESSAGE);
            }
            return guardChildrenAndDelete(xcontext, xdoc, ref, reference, currentVersion);
        });
    }

    /**
     * Builds the refusal for a rights- or configuration-bearing document, or returns {@code null} when
     * the document is not denylisted. The message includes the document's URL so the human operator can
     * delete the page manually in the wiki UI. The decision is made from the reference alone (name,
     * space and wiki) - never from the document's content.
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param ref the resolved document reference
     * @return the refusal result, or {@code null} when the deletion may proceed
     */
    private McpSchema.CallToolResult sensitiveRefusal(XWikiContext xcontext, DocumentReference ref)
    {
        if (!MCPWriteSupport.isSensitiveDocument(xcontext, ref, this.localSerializer)) {
            return null;
        }
        String url = MCPWriteSupport.safeDocumentUrl(this.documentAccessBridge, this.logger, ref, null);
        return MCPToolSupport.errorResult(REFUSING_TO_DELETE
            + MCPToolSupport.stripLineBreaks(this.serializer.serialize(ref)) + QUOTE
            + ": this page defines access rights or wiki configuration. If you really intend to delete "
            + "it, do it manually in the wiki UI" + (url != null ? ": " + url : PERIOD));
    }

    /**
     * Applies the children guard, re-checks the delete right through the API document, then deletes the
     * document and all its translations via {@code XWiki#deleteAllDocuments} - every row goes to the
     * recycle bin under one shared batch ID, as the platform UI delete does - and builds the success
     * result. The delete right attaches to the locale-free document reference, so the single re-check
     * covers the translations too; the platform records the context user as the deleter when it moves
     * the documents to the recycle bin. A failure of the children lookup itself refuses the deletion
     * (fail closed) instead of deleting with unknown children. The per-row deletion is not
     * transactional, so a failure of the delete itself is reported as possibly partial (some
     * translations already in the recycle bin) rather than as "nothing happened".
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param xdoc the loaded target document
     * @param ref the resolved document reference
     * @param reference the original reference string, for the refusal message
     * @param version the deleted document's version, for the success result
     * @return the tool result
     * @throws XWikiException when listing the translations fails (before anything is deleted)
     */
    private McpSchema.CallToolResult guardChildrenAndDelete(XWikiContext xcontext, XWikiDocument xdoc,
        DocumentReference ref, String reference, String version) throws XWikiException
    {
        boolean webPreferencesRemains;
        try {
            List<String> children = childPages(ref);
            webPreferencesRemains = isOnlyChildWebPreferences(ref, children);
            if (!children.isEmpty() && !webPreferencesRemains) {
                return childRefusal(ref, reference);
            }
        } catch (QueryException e) {
            this.logger.warn("MCP delete_document could not check for child pages: [{}]",
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP delete_document child check failure details", e);
            return MCPToolSupport.errorResult(CHILD_CHECK_FAILED_MESSAGE);
        }

        // Re-check the delete right at deletion time (deleteAllDocuments itself checks nothing); a rights
        // lookup failure inside hasAccessLevel yields false, so this refusal fails closed.
        Document apiDoc = new Document(xdoc, xcontext);
        if (!apiDoc.hasAccessLevel(Right.DELETE.getName())) {
            return MCPToolSupport.errorResult(
                "You do not have permission to delete " + QUOTE + reference + QUOTE + PERIOD);
        }

        List<Locale> translationLocales = xdoc.getTranslationLocales(xcontext);
        try {
            xcontext.getWiki().deleteAllDocuments(xdoc, xcontext);
        } catch (XWikiException e) {
            this.logger.warn("MCP delete_document failed partway through deleting [{}]: [{}]",
                ref, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP delete_document deletion failure details", e);
            return MCPToolSupport.errorResult(DELETE_INCOMPLETE_MESSAGE);
        }

        return MCPToolSupport.result(buildSuccessResult(ref, version, webPreferencesRemains, translationLocales));
    }

    /**
     * Fetches up to {@value #CHILD_FETCH_LIMIT} child page full names of the given document's space. A
     * terminal page (a name other than {@code WebHome}) has no children by definition, so no query is
     * run for it. The lookup uses the platform delete UI's own query shape, deliberately including
     * hidden documents and translations.
     *
     * <p>The lookup deliberately does not go through the authorized row-query door: the guard must see
     * ALL children - including ones the calling user cannot view or that fall outside the space filter -
     * or those children would be silently orphaned. Nothing about them is echoed beyond a count and the
     * space's own {@code WebPreferences} fact, matching the disclosure of the platform delete UI's own
     * count.</p>
     *
     * @param ref the resolved document reference
     * @return the child page full names, at most {@value #CHILD_FETCH_LIMIT} of them
     * @throws QueryException when the query fails
     */
    private List<String> childPages(DocumentReference ref) throws QueryException
    {
        if (!WEB_HOME.equals(ref.getName())) {
            return List.of();
        }
        Query query = this.queryManager.createQuery(CHILDREN_STATEMENT, Query.XWQL);
        query.setWiki(ref.getWikiReference().getName());
        query.setLimit(CHILD_FETCH_LIMIT);
        bindChildParams(query, ref);
        return query.execute();
    }

    /**
     * Binds the children-lookup parameters with the query parameter escaping API: the space as an
     * escaped literal prefix followed by any characters, and the document's own full name for the
     * self-exclusion. Both binds use the wiki-local serialized forms - {@code doc.fullName} is
     * wiki-local, and the target wiki is scoped by {@code setWiki}.
     *
     * @param query the query to bind
     * @param ref the resolved document reference
     */
    private void bindChildParams(Query query, DocumentReference ref)
    {
        query.bindValue(SPACE_BIND).literal(spaceDotPrefix(ref)).anyChars().query();
        query.bindValue(FULLNAME_BIND, this.localSerializer.serialize(ref));
    }

    /**
     * Builds the wiki-local space prefix ({@code "Space."} or {@code "Parent.Child."}) the children
     * lookup matches full names against.
     *
     * @param ref the resolved document reference
     * @return the local space name followed by a dot
     */
    private String spaceDotPrefix(DocumentReference ref)
    {
        return this.localSerializer.serialize(ref.getLastSpaceReference()) + PERIOD;
    }

    /**
     * Decides the {@code WebPreferences} exception: whether the only child of the space is its
     * {@code WebPreferences} page (space settings). The platform's delete UI special-cases that
     * situation by defaulting to delete the {@code WebPreferences} child along with the page, so it
     * never blocks the delete; this tool instead deletes only the home page and reports that
     * {@code WebPreferences} remains.
     *
     * @param ref the resolved document reference
     * @param children the fetched child page full names
     * @return whether the only child is the space's {@code WebPreferences} page
     */
    private boolean isOnlyChildWebPreferences(DocumentReference ref, List<String> children)
    {
        return children.size() == 1
            && children.get(0).equals(spaceDotPrefix(ref) + MCPWriteSupport.WEB_PREFERENCES);
    }

    /**
     * Builds the refusal for a space home page with children, running the count variant of the children
     * lookup so the message reports the accurate child count (including hidden documents and
     * translations, matching the platform's own count).
     *
     * @param ref the resolved document reference
     * @param reference the original reference string, for the refusal message
     * @return the refusal result
     * @throws QueryException when the count query fails
     */
    private McpSchema.CallToolResult childRefusal(DocumentReference ref, String reference) throws QueryException
    {
        Query countQuery = this.queryManager.createQuery(COUNT_STATEMENT, Query.XWQL);
        countQuery.setWiki(ref.getWikiReference().getName());
        bindChildParams(countQuery, ref);
        long count = ((Long) countQuery.execute().get(0)).longValue();
        return MCPToolSupport.errorResult(REFUSING_TO_DELETE + reference + QUOTE + ": it is the "
            + "home page of a space with " + count + " child pages (including hidden ones). Deleting it "
            + "would orphan them. Delete or move the children first - there is no recursive delete.");
    }

    /**
     * Builds the success result: the canonical reference, the deleted translations when there were any,
     * the deleted version, the recycle-bin recovery hint and the {@code WebPreferences} note when
     * applicable.
     *
     * @param ref the deleted document's reference
     * @param version the version the document had when deleted
     * @param webPreferencesRemains whether the space's {@code WebPreferences} page remains
     * @param translationLocales the locales of the deleted translations (the default locale excluded)
     * @return the success result text
     */
    private String buildSuccessResult(DocumentReference ref, String version, boolean webPreferencesRemains,
        List<Locale> translationLocales)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Deleted document ").append(MCPToolSupport.stripLineBreaks(this.serializer.serialize(ref)));
        if (!translationLocales.isEmpty()) {
            sb.append(", including ").append(translationLocales.size()).append(" translation(s) (")
                .append(StringUtils.join(translationLocales, ", ")).append(')');
        }
        sb.append(PERIOD).append(NEW_LINE);
        sb.append(MCPWriteSupport.VERSION_PREFIX).append(version).append(NEW_LINE);
        sb.append("The document was moved to the recycle bin; an administrator can restore it from there.");
        if (webPreferencesRemains) {
            sb.append(NEW_LINE).append(WEB_PREFERENCES_NOTE);
        }
        return sb.toString();
    }
}
