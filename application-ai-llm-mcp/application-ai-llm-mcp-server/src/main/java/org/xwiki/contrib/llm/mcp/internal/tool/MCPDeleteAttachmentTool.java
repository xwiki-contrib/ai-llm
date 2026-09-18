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

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

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

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that deletes one attachment from a document, by its exact filename.
 *
 * <p>This is a default tool bundled with the MCP server module. In XWiki's model an attachment removal
 * is a document edit: the document is saved as a new version (so the removal appears in the document
 * history) and the attachment itself - with its own version history - moves to the attachment recycle
 * bin, restorable from the wiki UI. That is why the tool requires the edit right, not the delete right
 * (the platform maps the attachment-deletion action to EDIT), resolved through {@link MCPDocumentAccess}
 * so the per-wiki space filter is applied and the existence of a protected document is never leaked. On
 * a wiki without an attachment recycle bin the deletion would be permanent, so it is refused
 * entirely.</p>
 *
 * <p>A mandatory {@code base_version} (the version the agent read) doubles as proof of a recent read, so
 * a removal is never based on a stale or absent view of the document. Rights- and configuration-bearing
 * documents are refused outright (the sensitive-document denylist shared with the other destructive
 * tools). The removal is staged through {@link com.xpn.xwiki.api.Document}, whose own lazy internal
 * clone is the cache-safety barrier (the loaded instance may be the store cache's, so it is never
 * mutated in place) and whose save applies author attribution and save-time rights.</p>
 *
 * @version $Id$
 * @since 0.10
 */
@Component
@Named(MCPDeleteAttachmentTool.TOOL_ID)
@Singleton
public class MCPDeleteAttachmentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "delete_attachment";

    private static final String REFERENCE_PARAM = "reference";

    private static final String FILENAME_PARAM = "filename";

    private static final String BASE_VERSION_PARAM = "base_version";

    private static final String COMMENT_PARAM = "comment";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    /**
     * The refusal for a wiki without an attachment recycle bin: this tool never performs an
     * unrecoverable delete, mirroring {@code delete_document}'s recycle-bin gate.
     */
    private static final String NO_ATTACHMENT_RECYCLE_BIN_MESSAGE = "This wiki has no attachment recycle "
        + "bin, so deletion would be permanent. Refusing; delete via the wiki UI if you really intend "
        + "this.";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description so
     * no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPDeleteAttachmentTool::params);

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
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private MCPWikiReach wikiReach;

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example and the cross-wiki
     * sentence in the {@code reference} description only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document holding the attachment, e.g. \"Sandbox.WebHome\" or \""
            + (crossWiki ? "xwiki:" : "") + "Help.Media\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(FILENAME_PARAM, "Exact attachment filename, as listed by get_document.")
            .requiredString(BASE_VERSION_PARAM, "The document version you last read, shown by get_document "
                + "and get_attachment. Always required: read the document first and pass its version, so a "
                + "removal is always based on a recent read. The removal is refused if the document has "
                + "changed since.")
            .string(COMMENT_PARAM, "Version comment shown in the document history. Stored prefixed with "
                + "[AI]. Default: a generic [AI] comment.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = PARAMS.advertised(this.wikiReach.isReachEnabled());
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description("Delete one attachment from a document, by its exact filename. Requires "
                + "base_version - read the document first. The attachment moves to the attachment "
                + "recycle bin (restorable from the wiki UI; on a wiki without one the deletion is "
                + "refused), and the removal is saved as a new document version.")
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
        return "Delete an attachment from a document.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                Deletes ONE attachment from a document, by its EXACT filename (as listed by
                get_document's Attachments header line - there is no fuzzy matching). The
                document is saved as a new version, so the removal appears in its history; the
                attachment itself - WITH its own version history - moves to the attachment
                recycle bin, restorable from the wiki UI. On a wiki without an attachment
                recycle bin the deletion would be permanent, so it is refused. Removals are
                recorded as minor versions.
                base_version is always required: read the document first (get_document and
                get_attachment show the version) and pass what you read, so a removal is always
                based on a recent read. The removal is refused if the document has changed since.
                Pages that define access rights or wiki configuration are refused; manage those
                in the wiki UI.

            EXAMPLES
                Delete:      get_attachment reference="Sandbox.WebHome", filename="report.pdf",
                             metadata=true  (note the document version, e.g. 3.2), then
                             delete_attachment reference="Sandbox.WebHome",
                             filename="report.pdf", base_version="3.2"
                Conflict:    a "Version conflict" result means the document changed since you
                             read it - get_history (diff mode) shows what changed; re-read it
                             and retry if you still intend the removal.

            SEE ALSO
                man get_attachment      Read an attachment's content or metadata (and the version).
                man write_attachment    Attach a file or overwrite an existing attachment.
                man get_history         See what changed after a version conflict.
                man                     (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
            String filename = PARAMS.parser().requireString(args, FILENAME_PARAM);
            String baseVersion = PARAMS.parser().requireString(args, BASE_VERSION_PARAM);
            String comment = PARAMS.parser().string(args, COMMENT_PARAM);

            DocumentReference ref = MCPWriteSupport.resolveForEdit(this.documentAccess, reference);

            return removeAndSave(ref, reference, filename, baseVersion, comment);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP delete_attachment tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP delete_attachment tool failure details", e);
            return MCPToolSupport.errorResult(MCPWriteSupport.SAVE_FAILED_MESSAGE);
        }
    }

    /**
     * Runs the guarded removal inside the target wiki: the document must exist, the sensitive-document
     * refusal fires next (before the version check, so a denylisted target is refused regardless of
     * version), then the mandatory {@code base_version} is compared, and the attachment removal is
     * staged and saved (see {@link #removeAttachmentAndSave}).
     *
     * @param ref the resolved and authorized document reference
     * @param reference the original reference string, for error messages
     * @param filename the exact filename of the attachment to remove
     * @param baseVersion the version the agent read
     * @param comment the agent-supplied version comment, or {@code null}
     * @return the tool result
     * @throws XWikiException when loading or saving fails
     */
    private McpSchema.CallToolResult removeAndSave(DocumentReference ref, String reference, String filename,
        String baseVersion, String comment) throws XWikiException
    {
        return MCPWriteSupport.inTargetWiki(this.contextProvider.get(), ref, (xcontext, xdoc) -> {
            if (xdoc.isNew()) {
                return MCPToolSupport.errorResult("Document " + QUOTE + MCPTextGuards.fragment(reference)
                    + QUOTE + " does not exist; there is no attachment to delete.");
            }
            if (MCPWriteSupport.isSensitiveDocument(xcontext, ref, this.localSerializer)) {
                return sensitiveRefusal(ref);
            }
            String oldVersion = xdoc.getVersion();
            if (!baseVersion.equals(oldVersion)) {
                return MCPToolSupport.errorResult(MCPWriteSupport.versionConflictError(
                    MCPWriteSupport.DOCUMENT_SUBJECT, oldVersion, baseVersion,
                    "retry the removal if you still intend it."));
            }

            return removeAttachmentAndSave(xcontext, xdoc, ref, reference, filename, comment, oldVersion);
        });
    }

    /**
     * Removes the attachment and saves the removal, the tail of the guarded removal once the
     * document-level refusals have passed: the exact attachment must exist, the attachment recycle bin
     * must be enabled (BEFORE any mutation - without it the deletion would be permanent), then the
     * removal is staged through the api wrapper's own clone and saved. The platform queues the removal
     * on the saved instance and moves the attachment - with its history - to the attachment recycle bin
     * inside the save.
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param xdoc the loaded target document
     * @param ref the resolved and authorized document reference
     * @param reference the original reference string, for error messages
     * @param filename the exact filename of the attachment to remove
     * @param comment the agent-supplied version comment, or {@code null}
     * @param oldVersion the document's version before the save
     * @return the tool result
     * @throws XWikiException when saving fails and the failure is not re-routable
     */
    private McpSchema.CallToolResult removeAttachmentAndSave(XWikiContext xcontext, XWikiDocument xdoc,
        DocumentReference ref, String reference, String filename, String comment, String oldVersion)
        throws XWikiException
    {
        // Read-only against the cached xdoc: the miss check and the echo values mutate nothing (mirror
        // delete_object's read-only-against-cache discipline).
        XWikiAttachment cached = xdoc.getExactAttachment(filename);
        if (cached == null) {
            return MCPToolSupport.errorResult(MCPAttachmentSupport.missingAttachmentMessage(filename,
                reference, xdoc.getAttachmentList()));
        }
        if (!xcontext.getWiki().hasAttachmentRecycleBin(xcontext)) {
            return MCPToolSupport.errorResult(NO_ATTACHMENT_RECYCLE_BIN_MESSAGE);
        }
        String name = QUOTE + MCPTextGuards.fragment(cached.getFilename()) + QUOTE;
        String sizeAndType = " (" + MCPAttachmentSupport.humanSize(cached.getLongSize()) + ", "
            + MCPTextGuards.fragment(cached.getMimeType(xcontext)) + ")";

        // The removal is staged THROUGH the api wrapper, whose lazy internal clone is the exact
        // instance save() persists. write_attachment's clone-then-wrap pattern is NOT safe here: an
        // added attachment lives in the attachment list, which XWikiDocument.clone() copies, but a
        // removal is staged in the attachmentsToRemove queue, which clone() does NOT carry - staging on
        // a manual pre-clone would let the wrapper re-clone it at save time and silently drop the
        // queue (no recycle-bin copy, no deletion event, and the store never deletes the rows).
        Document apiDoc = new Document(xdoc, xcontext);
        // The exact-match pre-check above guarantees the wrapper's own filename lookup resolves the
        // same attachment. Guard the null defensively like delete_object: a null here would mean the
        // wrapper's internal clone diverged, and execute() catches only IllegalArgumentException and
        // XWikiException, not a stray NullPointerException.
        if (apiDoc.removeAttachment(filename) == null) {
            return MCPToolSupport.errorResult(MCPAttachmentSupport.missingAttachmentMessage(filename,
                reference, xdoc.getAttachmentList()));
        }
        try {
            apiDoc.save(MCPWriteSupport.buildComment(comment, false,
                "deleted attachment " + MCPTextGuards.fragment(filename)),
                MCPWriteSupport.isMinorEdit(false, false));
        } catch (XWikiException e) {
            // This tool never creates its document (creating = false), so the only possible
            // re-route is the concurrency-collision message; there is no create race to detect.
            McpSchema.CallToolResult rerouted =
                MCPWriteSupport.reroutedSaveFailure(e, xcontext, ref, null, false, null, this.logger);
            if (rerouted != null) {
                return rerouted;
            }
            throw e;
        }

        return MCPToolSupport.result(
            buildSuccessResult(ref, name, sizeAndType, oldVersion, apiDoc.getVersion()));
    }

    /**
     * Formats the refusal for a rights- or configuration-bearing target document, mirroring the other
     * destructive tools' wording.
     *
     * @param ref the resolved document reference
     * @return the refusal result
     */
    private McpSchema.CallToolResult sensitiveRefusal(DocumentReference ref)
    {
        String url = MCPWriteSupport.safeDocumentUrl(this.documentAccessBridge, this.logger, ref, null);
        return MCPToolSupport.errorResult("Refusing to delete attachments on " + QUOTE
            + MCPTextGuards.fragment(this.serializer.serialize(ref)) + QUOTE
            + ": this page defines access rights or wiki configuration. If you really intend to change "
            + "it, do it manually in the wiki UI" + (url != null ? ": " + url : PERIOD));
    }

    /**
     * Builds the success result: what was deleted from which document with the restore pointer, the
     * version transition with the {@code base_version} hint, and the review line (the compare URL shows
     * the removal as a diff).
     *
     * @param ref the saved document's reference
     * @param name the deleted attachment's quoted, fragment-guarded filename
     * @param sizeAndType the deleted attachment's parenthesized size and mimetype
     * @param oldVersion the version before the save
     * @param newVersion the version after the save
     * @return the result text
     */
    private String buildSuccessResult(DocumentReference ref, String name, String sizeAndType,
        String oldVersion, String newVersion)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Deleted attachment ").append(name).append(sizeAndType).append(" from ")
            .append(MCPToolSupport.stripLineBreaks(this.serializer.serialize(ref))).append(PERIOD)
            .append(" It can be restored from the attachment recycle bin via the wiki UI.").append(NEW_LINE);
        sb.append(MCPWriteSupport.VERSION_PREFIX).append(oldVersion).append(" -> ").append(newVersion)
            .append(MCPWriteSupport.baseVersionHint(newVersion));
        String urlLine = MCPWriteSupport.buildReviewLine(this.documentAccessBridge, this.logger, ref, false,
            oldVersion, newVersion);
        if (urlLine != null) {
            sb.append(NEW_LINE).append(urlLine);
        }
        return sb.toString();
    }
}
