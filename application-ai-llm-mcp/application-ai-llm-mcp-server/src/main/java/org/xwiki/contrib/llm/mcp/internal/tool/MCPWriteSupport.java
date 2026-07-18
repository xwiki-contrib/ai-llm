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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Shared write-path plumbing for the document-writing MCP tools ({@link MCPEditDocumentTool},
 * {@link MCPWriteDocumentTool}, {@link MCPDeleteDocumentTool}): write-right resolution, the
 * authenticated-user guard and target-wiki context switch around a write, the {@code [AI]}-prefixed
 * version-comment construction, the minor-edit policy, the review-URL result line and the agent-facing
 * message fragments the tools' {@code base_version} checks share. Not a component: a plain holder of
 * static helpers, kept in this module so the oldcore types it handles ({@link XWikiContext},
 * {@link XWikiDocument}) stay out of the API module's surface.
 *
 * @version $Id$
 * @since 0.9.1
 */
final class MCPWriteSupport
{
    /**
     * Upper bound, in characters, on the document content a write tool accepts or produces, so a
     * pathological call (or a {@code replace_all} amplification) cannot persist an arbitrarily large
     * document version.
     */
    static final int MAX_CONTENT_CHARS = 1_000_000;

    /**
     * The agent-facing result message for a save that failed with a storage-level error. The root cause
     * stays in the server logs (logged by the tool under its own name), off the wire.
     */
    static final String SAVE_FAILED_MESSAGE = "Could not save the document. Try again; if it persists, report "
        + "it to a wiki administrator (details are in the server logs).";

    /**
     * Prefix of the result line reporting the saved version (or the version transition) of a write.
     */
    static final String VERSION_PREFIX = "Version: ";

    /**
     * The space-level preferences document name (space rights overrides), part of the sensitive-document
     * denylist. Also the single child page the delete tool's {@code WebPreferences} exception concerns.
     */
    static final String WEB_PREFERENCES = "WebPreferences";

    /**
     * The default subject of the shared agent-facing messages naming what a write acted on; translation
     * writes pass {@link #translationSubject(Locale)} instead.
     */
    static final String DOCUMENT_SUBJECT = "the document";

    /**
     * The target noun of the write tools' default-language success messages (e.g. {@code Created
     * document A.B}); a translation write names the row via {@link #translationSubject(Locale)}
     * instead.
     */
    static final String DOCUMENT_NOUN = "document ";

    /**
     * The refusal for a translation creation on a wiki whose multilingual support is off. Editing an
     * EXISTING translation row is still allowed regardless of the flag, matching the platform, so only
     * the creation path returns this.
     */
    static final String NOT_MULTILINGUAL_MESSAGE = "This wiki is not multilingual, so a new translation "
        + "cannot be created. An administrator can enable multilingual support in the wiki "
        + "administration, or write the default language version instead, without 'locale'.";

    /**
     * The wiki-level preferences document name (wiki rights and configuration), part of the
     * sensitive-document denylist.
     */
    private static final String XWIKI_PREFERENCES = "XWikiPreferences";

    /**
     * Document-name prefix of the wiki descriptor documents in the main wiki's {@code XWiki} space.
     */
    private static final String WIKI_DESCRIPTOR_PREFIX = "XWikiServer";

    /**
     * The space-dot prefix identifying the main wiki's {@code XWiki} space, where the wiki descriptor
     * documents live.
     */
    private static final String XWIKI_SPACE_DOT = "XWiki.";

    /**
     * The wiki-local full name of the MCP server configuration document. Mirrors
     * {@code MCPServerConfiguration.CONFIG_SPACES} + {@code CONFIG_DOC_NAME} (the source of truth,
     * package-private in {@code internal.server}).
     */
    private static final String MCP_CONFIG_LOCAL_FULLNAME = "AI.MCP.Code.MCPServerConfig";

    /**
     * Marker prefixed to the save comment of every write made through the MCP tools, making agent-made
     * revisions identifiable in document history (for review, filtering or reverting). Treat it as a
     * stable contract: tooling and administrators may match on it.
     */
    private static final String AI_COMMENT_PREFIX = "[AI] ";

    /**
     * Cap on the saved version comment, comfortably under the database column limit (1023 characters).
     */
    private static final int MAX_COMMENT_CHARS = 1000;

    /**
     * Shared opening of the agent-facing messages that quote a document reference.
     */
    private static final String DOCUMENT_QUOTE = "Document \"";

    /**
     * Article opening the row-subject phrases, shared so {@link #currentRowSubject(Locale)} can splice
     * {@code current} into {@link #translationSubject(Locale)}'s output.
     */
    private static final String THE_PREFIX = "the ";

    private static final String VIEW_ACTION = "view";

    private MCPWriteSupport()
    {
    }

    /**
     * Resolves and authorizes a document reference for {@link Right#EDIT} through
     * {@link #resolveFor(MCPDocumentAccess, String, Right)}, keeping the edit-right call sites of the
     * content-writing tools to a single argument list.
     *
     * @param documentAccess the resolution and authorization component of the calling tool
     * @param reference the reference string from the tool call
     * @return the resolved and authorized document reference
     * @throws IllegalArgumentException when the reference is malformed, filtered out or not editable by
     *             the calling user, with the agent-facing message as the exception message
     */
    static DocumentReference resolveForEdit(MCPDocumentAccess documentAccess, String reference)
    {
        return resolveFor(documentAccess, reference, Right.EDIT);
    }

    /**
     * Resolves and authorizes a document reference for the given right through
     * {@link MCPDocumentAccess#resolveAndAuthorize(String, Right)}, so the per-wiki space filter is
     * applied and the existence of a protected document is never leaked. A denial is rethrown as an
     * {@link IllegalArgumentException} carrying the denial's agent-facing message, which the calling
     * tool's argument-error handling returns as an error result.
     *
     * @param documentAccess the resolution and authorization component of the calling tool
     * @param reference the reference string from the tool call
     * @param right the right required on the document
     * @return the resolved and authorized document reference
     * @throws IllegalArgumentException when the reference is malformed, filtered out or denied the
     *             required right for the calling user, with the agent-facing message as the exception
     *             message
     */
    static DocumentReference resolveFor(MCPDocumentAccess documentAccess, String reference, Right right)
    {
        try {
            return documentAccess.resolveAndAuthorize(reference, right);
        } catch (MCPAccessDeniedException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Runs a tool-specific write body against the loaded target document, inside the target wiki:
     * refuses when no authenticated user is in the context, switches the context wiki to the
     * reference's wiki so save-time rights and class resolution are correct for a cross-wiki write,
     * loads the document (stamping the wiki's default locale on a document about to be created, see
     * {@link #stampDefaultLocaleOnCreation}), runs the body and restores the original context wiki
     * afterwards (also when the body throws).
     *
     * @param xcontext the XWiki context, possibly {@code null} when none is available
     * @param ref the resolved reference of the target document
     * @param body the tool-specific write logic
     * @return the body's result, or an error result when no authenticated user is in the context
     * @throws XWikiException when loading the document or the body fails
     */
    static McpSchema.CallToolResult inTargetWiki(XWikiContext xcontext, DocumentReference ref, WriteBody body)
        throws XWikiException
    {
        if (xcontext == null || xcontext.getUserReference() == null) {
            return MCPToolSupport.errorResult("No authenticated user in context.");
        }
        String originalWiki = xcontext.getWikiId();
        try {
            xcontext.setWikiId(ref.getWikiReference().getName());
            XWikiDocument xdoc = xcontext.getWiki().getDocument(ref, xcontext);
            stampDefaultLocaleOnCreation(xcontext, xdoc);
            return body.write(xcontext, xdoc);
        } finally {
            xcontext.setWikiId(originalWiki);
        }
    }

    /**
     * Stamps the wiki's default locale as the default locale of a document about to be created: a
     * freshly instantiated document carries the ROOT default locale, which is never persisted on a
     * create. The platform UI also stamps on every create (monolingual wikis included), but it
     * persists the request-dependent locale preference it resolves per HTTP request
     * ({@code SaveAction} calls {@code setDefaultLocale(getLocalePreference)}); this seam deliberately
     * diverges and stamps {@code XWiki#getDefaultLocale(XWikiContext)} instead, which is deterministic
     * - ENGLISH on an unconfigured wiki, never ROOT - so an MCP creation cannot depend on the calling
     * request's language negotiation. The
     * guard keeps the stamp off translation rows (their own locale is not ROOT) and off documents that
     * already declare a default locale; documents that exist are untouched. This one seam covers every
     * MCP document creation: the content, object and schema write tools all load through
     * {@link #inTargetWiki(XWikiContext, DocumentReference, WriteBody)}, and the tools that clone the
     * loaded document carry the stamp into their clones. The delete tools also pass through here but
     * never save a new document, so the stamp is inert for them. Translation rows loaded through
     * {@link #loadTranslation(XWikiContext, DocumentReference, Locale)} bypass this seam entirely.
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param xdoc the loaded target document
     */
    private static void stampDefaultLocaleOnCreation(XWikiContext xcontext, XWikiDocument xdoc)
    {
        if (xdoc.isNew() && Locale.ROOT.equals(xdoc.getLocale()) && Locale.ROOT.equals(xdoc.getDefaultLocale())) {
            xdoc.setDefaultLocale(xcontext.getWiki().getDefaultLocale(xcontext));
        }
    }

    /**
     * Resolves which stored row a locale-aware write addresses, applying the shared translation gates
     * in order: no locale, or a locale that
     * {@link #isDefaultLanguageRequest(XWikiContext, XWikiDocument, Locale)} recognizes as naming the
     * default language, addresses the default document (on a document that does not exist yet this
     * makes {@code locale=<wiki default>} a plain default-document creation); any other locale on a
     * document that does not exist yet is refused (the default language version must be created
     * first); otherwise the EXACT translation row is loaded - never with parent-locale fallback - and
     * creating a new translation is refused when the wiki is not multilingual (editing an existing row
     * stays allowed regardless of the flag, matching the platform).
     *
     * <p>The default-language short-circuit shares the read-side predicate of {@code get_document}:
     * the default row stores an empty language, so treating e.g. {@code "en"} on an English-default
     * page as a translation would create a bogus {@code en} row next to the default one.</p>
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param defaultDoc the loaded default document
     * @param reference the original reference string from the tool call, for the refusal message
     * @param requestedLocale the validated {@code locale} argument, or {@code null} when absent
     * @return the resolved target: either a refusal result, or the document row to write together with
     *     its translation locale ({@code null} for a default-language write)
     * @throws XWikiException when loading the translation row fails
     */
    static WriteTarget resolveWriteTarget(XWikiContext xcontext, XWikiDocument defaultDoc, String reference,
        Locale requestedLocale) throws XWikiException
    {
        if (requestedLocale == null || isDefaultLanguageRequest(xcontext, defaultDoc, requestedLocale)) {
            return new WriteTarget(defaultDoc, null, null);
        }
        if (defaultDoc.isNew()) {
            return new WriteTarget(defaultDoc, null, MCPToolSupport.errorResult(
                missingDefaultTranslationTargetError(reference, requestedLocale)));
        }
        XWikiDocument tdoc = loadTranslation(xcontext, defaultDoc.getDocumentReference(), requestedLocale);
        if (tdoc.isNew() && !xcontext.getWiki().isMultiLingual(xcontext)) {
            return new WriteTarget(tdoc, requestedLocale, MCPToolSupport.errorResult(NOT_MULTILINGUAL_MESSAGE));
        }
        return new WriteTarget(tdoc, requestedLocale, null);
    }

    /**
     * Tests whether the requested locale designates the default-language version of the loaded default
     * document: its real locale or its declared default locale. When the document declares NO default
     * locale (ROOT) - the platform UI stamps one on every create, but programmatic creation paths
     * (extension document initializers, REST imports, older tools) leave it undeclared - the WIKI
     * default locale decides, so a request naming the wiki default is served as a default-language
     * access instead of fabricating a translation row next to the default one. Shared by the read side
     * ({@code get_document}) and the write side ({@link #resolveWriteTarget}) so the two cannot drift.
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param defaultDoc the loaded default document
     * @param requested the requested locale
     * @return whether the request addresses the default-language row
     */
    static boolean isDefaultLanguageRequest(XWikiContext xcontext, XWikiDocument defaultDoc, Locale requested)
    {
        if (requested.equals(defaultDoc.getRealLocale()) || requested.equals(defaultDoc.getDefaultLocale())) {
            return true;
        }
        return Locale.ROOT.equals(defaultDoc.getDefaultLocale())
            && requested.equals(xcontext.getWiki().getDefaultLocale(xcontext));
    }

    /**
     * Loads the exact translation row of the given locale. Deliberately not
     * {@code getTranslatedDocument}: its parent-locale fallback would silently write the {@code fr} row
     * when {@code fr_FR} was asked. The returned row reports a locale-free
     * {@code getDocumentReference()}, the requested {@code getLocale()}, and {@code isNew()} when the
     * translation does not exist yet. This load path performs no default-locale stamping - the stamp
     * belongs to default-document creations only.
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param ref the locale-free document reference
     * @param locale the translation locale
     * @return the loaded translation row
     * @throws XWikiException when the load fails
     */
    private static XWikiDocument loadTranslation(XWikiContext xcontext, DocumentReference ref, Locale locale)
        throws XWikiException
    {
        return xcontext.getWiki().getDocument(new DocumentReference(ref, locale), xcontext);
    }

    /**
     * Builds the agent-facing subject phrase naming a translation row (e.g. {@code the fr translation}),
     * stripped of line breaks: a validated locale can still carry them in its variant segment.
     *
     * @param locale the translation locale
     * @return the subject phrase
     */
    static String translationSubject(Locale locale)
    {
        return THE_PREFIX + MCPToolSupport.stripLineBreaks(locale.toString()) + " translation";
    }

    /**
     * Builds the agent-facing subject phrase naming the current state of the written row, used by the
     * write tools' no-change results: {@code the current document} for a default-language write, or
     * e.g. {@code the current fr translation} for a translation write.
     *
     * @param locale the written translation row's locale, or {@code null} for a default-language write
     * @return the subject phrase
     */
    static String currentRowSubject(Locale locale)
    {
        String subject = locale == null ? DOCUMENT_SUBJECT : translationSubject(locale);
        return "the current " + StringUtils.removeStart(subject, THE_PREFIX);
    }

    /**
     * Formats the refusal for a {@code locale} sent for a document that does not exist at all: a
     * translation is a language row OF an existing page, so the default language version must be
     * created first.
     *
     * @param reference the original reference string from the tool call
     * @param locale the requested translation locale
     * @return the agent-facing error message
     */
    private static String missingDefaultTranslationTargetError(String reference, Locale locale)
    {
        return DOCUMENT_QUOTE + reference + "\" does not exist, so a "
            + MCPToolSupport.stripLineBreaks(locale.toString()) + " translation cannot be added to it. "
            + "Create the default language version first, without 'locale'.";
    }

    /**
     * Decides the sensitive-document denylist shared by the destructive tools: {@code WebPreferences}
     * (space rights overrides), {@code XWikiPreferences} (wiki-level rights and configuration), the main
     * wiki's wiki descriptor documents and the MCP server configuration document. The decision is made
     * from the reference alone (name, space and wiki) - never from the document's content.
     *
     * @param xcontext the XWiki context, for the main-wiki check
     * @param ref the resolved document reference
     * @param localSerializer the calling tool's wiki-local reference serializer
     * @return whether the document is denylisted
     */
    static boolean isSensitiveDocument(XWikiContext xcontext, DocumentReference ref,
        EntityReferenceSerializer<String> localSerializer)
    {
        String name = ref.getName();
        return WEB_PREFERENCES.equals(name) || XWIKI_PREFERENCES.equals(name)
            || isWikiDescriptor(xcontext, ref, name, localSerializer)
            || MCP_CONFIG_LOCAL_FULLNAME.equals(localSerializer.serialize(ref));
    }

    /**
     * Decides whether the document is a wiki descriptor: a document in the main wiki's {@code XWiki}
     * space whose name starts with {@code XWikiServer}. The rule is main-wiki-scoped - descriptors only
     * exist there.
     *
     * @param xcontext the XWiki context, for the main-wiki check
     * @param ref the resolved document reference
     * @param name the document name
     * @param localSerializer the calling tool's wiki-local reference serializer
     * @return whether the document is a wiki descriptor
     */
    private static boolean isWikiDescriptor(XWikiContext xcontext, DocumentReference ref, String name,
        EntityReferenceSerializer<String> localSerializer)
    {
        return name.startsWith(WIKI_DESCRIPTOR_PREFIX)
            && xcontext.isMainWiki(ref.getWikiReference().getName())
            && XWIKI_SPACE_DOT.equals(localSerializer.serialize(ref.getLastSpaceReference()) + ".");
    }

    /**
     * Builds the version comment for a save: the agent-supplied comment when one was given, otherwise a
     * generated summary of the change ({@code Created document} for a creation, the tool's update
     * description otherwise). The {@link #AI_COMMENT_PREFIX} marker is always prepended, and the
     * combined comment is truncated to {@value #MAX_COMMENT_CHARS} characters to fit the history
     * storage.
     *
     * @param agentComment the agent-supplied comment, or {@code null} when none was given
     * @param creating whether the document is being created
     * @param updateDescription the tool-specific summary of a non-creating change
     * @return the comment to record in the document history
     */
    static String buildComment(String agentComment, boolean creating, String updateDescription)
    {
        String combined;
        if (StringUtils.isNotBlank(agentComment)) {
            combined = AI_COMMENT_PREFIX + agentComment;
        } else if (creating) {
            combined = AI_COMMENT_PREFIX + "Created document";
        } else {
            combined = AI_COMMENT_PREFIX + updateDescription;
        }
        return StringUtils.abbreviate(combined, MAX_COMMENT_CHARS);
    }

    /**
     * Decides whether a save is recorded as a minor edit. A creation is a normal (major) save - a new
     * document is version 1.1 regardless, so an explicit {@code major} request is accepted and ignored.
     * A subsequent save is minor unless the caller explicitly asks for a major version, so iterative
     * agent writes do not inflate the major version or clutter the default history view.
     *
     * @param creating whether the document is being created
     * @param major whether the caller asked for a major version
     * @return whether the save is a minor edit
     */
    static boolean isMinorEdit(boolean creating, boolean major)
    {
        return !creating && !major;
    }

    /**
     * Formats the error message for a {@code base_version} sent for a document that does not exist:
     * the lock can only ever match an existing version, so a creation must omit it.
     *
     * @param reference the original reference string from the tool call
     * @return the agent-facing error message
     */
    static String missingDocumentBaseVersionError(String reference)
    {
        return DOCUMENT_QUOTE + reference + "\" does not exist; omit base_version when creating a document.";
    }

    /**
     * Formats the error message for a {@code base_version} sent for a translation row that does not
     * exist yet, mirroring {@link #missingDocumentBaseVersionError(String)} for the row-scoped lock:
     * every language row has its own version history, and a row creation must omit the lock.
     *
     * @param reference the original reference string from the tool call
     * @param locale the requested translation locale
     * @return the agent-facing error message
     */
    static String missingTranslationBaseVersionError(String reference, Locale locale)
    {
        return "The " + MCPToolSupport.stripLineBreaks(locale.toString()) + " translation of \"" + reference
            + "\" does not exist; omit base_version when creating a translation.";
    }

    /**
     * Formats the error message for a stale {@code base_version}: the written row moved on since the
     * agent read it, so the save is refused instead of silently overwriting the concurrent change.
     *
     * @param subject what the versions describe: {@link #DOCUMENT_SUBJECT} for a default-language
     *            write, or {@link #translationSubject(Locale)} for a translation-row write
     * @param currentVersion the row's current version
     * @param baseVersion the version the agent read
     * @param retryAction the tool-specific closing instruction, following "Re-read it with get_document
     *            and " (e.g. {@code "retry."})
     * @return the agent-facing error message
     */
    static String versionConflictError(String subject, String currentVersion, String baseVersion,
        String retryAction)
    {
        return "Version conflict: " + subject + " is now at version " + currentVersion + " but base_version is "
            + baseVersion + ". Re-read it with get_document and " + retryAction;
    }

    /**
     * Builds the review line of a default-language write result (see the locale-aware overload).
     *
     * @param documentAccessBridge the URL-building bridge of the calling tool
     * @param logger the calling tool's logger, for the debug trace of a failed URL build
     * @param ref the saved document's reference
     * @param creating whether the document was created rather than updated
     * @param oldVersion the version before the save
     * @param newVersion the version after the save
     * @return the review line, or {@code null} when no URL could be built
     */
    static String buildReviewLine(DocumentAccessBridge documentAccessBridge, Logger logger, DocumentReference ref,
        boolean creating, String oldVersion, String newVersion)
    {
        return buildReviewLine(documentAccessBridge, logger, ref, creating, oldVersion, newVersion, null);
    }

    /**
     * Builds the review line of a write result: a view URL for a created document, or a compare URL
     * showing the old-to-new version diff for an updated one. A translation write pins the written row
     * with a {@code language} query parameter on both URLs, so the link opens (and the diff shows) the
     * row the result describes; the value is URL-encoded because a validated locale's variant segment
     * can carry URL metacharacters. URL building is best-effort: a failure is logged at debug level and
     * the line is simply omitted.
     *
     * @param documentAccessBridge the URL-building bridge of the calling tool
     * @param logger the calling tool's logger, for the debug trace of a failed URL build
     * @param ref the saved document's reference
     * @param creating whether the row was created rather than updated
     * @param oldVersion the version before the save
     * @param newVersion the version after the save
     * @param locale the written translation row's locale, or {@code null} for a default-language write
     * @return the review line, or {@code null} when no URL could be built
     */
    static String buildReviewLine(DocumentAccessBridge documentAccessBridge, Logger logger, DocumentReference ref,
        boolean creating, String oldVersion, String newVersion, Locale locale)
    {
        String languageQuery = locale != null
            ? "language=" + URLEncoder.encode(locale.toString(), StandardCharsets.UTF_8) : null;
        if (creating) {
            String viewUrl = safeDocumentUrl(documentAccessBridge, logger, ref, languageQuery);
            return viewUrl != null ? "View: " + viewUrl : null;
        }
        String query = "viewer=changes&rev1=" + oldVersion + "&rev2=" + newVersion;
        if (languageQuery != null) {
            query += "&" + languageQuery;
        }
        String compareUrl = safeDocumentUrl(documentAccessBridge, logger, ref, query);
        return compareUrl != null ? "Compare: " + compareUrl : null;
    }

    /**
     * Builds an external view-mode URL for the given document, returning {@code null} instead of
     * propagating a URL-building failure: a URL in a result message is a convenience, never worth
     * failing the operation over.
     *
     * @param documentAccessBridge the URL-building bridge of the calling tool
     * @param logger the calling tool's logger
     * @param docRef the document reference
     * @param queryString the query string, or {@code null} for none
     * @return the URL, or {@code null} when it could not be built
     */
    static String safeDocumentUrl(DocumentAccessBridge documentAccessBridge, Logger logger,
        DocumentReference docRef, String queryString)
    {
        try {
            return documentAccessBridge.getDocumentURL(docRef, VIEW_ACTION, queryString, null, true);
        } catch (Exception e) {
            logger.debug("MCP write support could not build a document URL", e);
            return null;
        }
    }

    /**
     * The resolved target of a locale-aware write, as produced by
     * {@link MCPWriteSupport#resolveWriteTarget}: either a refusal result, or the document row to
     * write together with its translation locale.
     *
     * @param doc the row to write: the default document, or the exact translation row
     * @param locale the written translation row's locale, or {@code null} for a default-language write
     * @param refusal the agent-facing refusal, or {@code null} when the write may proceed
     * @version $Id$
     */
    record WriteTarget(XWikiDocument doc, Locale locale, McpSchema.CallToolResult refusal)
    {
    }

    /**
     * The tool-specific body of a document write, run by
     * {@link MCPWriteSupport#inTargetWiki(XWikiContext, DocumentReference, WriteBody)} with the context
     * switched to the target wiki and the target document loaded.
     *
     * @version $Id$
     */
    @FunctionalInterface
    interface WriteBody
    {
        /**
         * Applies the tool's changes to the loaded document and persists them (save or delete), or
         * returns an error result without persisting anything.
         *
         * @param xcontext the XWiki context, switched to the target wiki
         * @param xdoc the loaded target document (a new in-memory instance when it does not exist yet)
         * @return the tool result
         * @throws XWikiException when persisting fails
         */
        McpSchema.CallToolResult write(XWikiContext xcontext, XWikiDocument xdoc) throws XWikiException;
    }
}
