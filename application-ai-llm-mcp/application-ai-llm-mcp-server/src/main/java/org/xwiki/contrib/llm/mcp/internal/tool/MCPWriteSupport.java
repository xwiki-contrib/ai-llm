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

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.model.reference.DocumentReference;
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
     * Marker prefixed to the save comment of every write made through the MCP tools, making agent-made
     * revisions identifiable in document history (for review, filtering or reverting). Treat it as a
     * stable contract: tooling and administrators may match on it.
     */
    private static final String AI_COMMENT_PREFIX = "[AI] ";

    /**
     * Cap on the saved version comment, comfortably under the database column limit (1023 characters).
     */
    private static final int MAX_COMMENT_CHARS = 1000;

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
     * loads the document, runs the body and restores the original context wiki afterwards (also when
     * the body throws).
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
            return body.write(xcontext, xcontext.getWiki().getDocument(ref, xcontext));
        } finally {
            xcontext.setWikiId(originalWiki);
        }
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
        return "Document \"" + reference + "\" does not exist; omit base_version when creating a document.";
    }

    /**
     * Formats the error message for a stale {@code base_version}: the document moved on since the agent
     * read it, so the save is refused instead of silently overwriting the concurrent change.
     *
     * @param currentVersion the document's current version
     * @param baseVersion the version the agent read
     * @param retryAction the tool-specific closing instruction, following "Re-read it with get_document
     *            and " (e.g. {@code "retry."})
     * @return the agent-facing error message
     */
    static String versionConflictError(String currentVersion, String baseVersion, String retryAction)
    {
        return "Version conflict: the document is now at version " + currentVersion + " but base_version is "
            + baseVersion + ". Re-read it with get_document and " + retryAction;
    }

    /**
     * Builds the review line of a write result: a view URL for a created document, or a compare URL
     * showing the old-to-new version diff for an updated one. URL building is best-effort: a failure is
     * logged at debug level and the line is simply omitted.
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
        if (creating) {
            String viewUrl = safeDocumentUrl(documentAccessBridge, logger, ref, null);
            return viewUrl != null ? "View: " + viewUrl : null;
        }
        String query = "viewer=changes&rev1=" + oldVersion + "&rev2=" + newVersion;
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
