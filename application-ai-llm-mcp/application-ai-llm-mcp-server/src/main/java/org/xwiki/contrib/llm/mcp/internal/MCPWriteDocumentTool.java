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
package org.xwiki.contrib.llm.mcp.internal;

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
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that creates an XWiki document or replaces the full content of an existing one, the
 * whole-body counterpart of {@link MCPEditDocumentTool}'s exact-string search-and-replace.
 *
 * <p>This is a default tool bundled with the MCP server module. The {@code content} argument is the
 * complete new document source: on a new document it becomes the body of version 1.1; on an existing
 * document it replaces the body entirely, gated by a mandatory {@code base_version} (the version the
 * agent read with {@code get_document}), so an overwrite is always a deliberate read-modify-write
 * and never a blind clobber. The incoming content is line-ending normalized with
 * {@link MCPSourceText#normalizeLineEndings(String)}, matching the representation the read and edit
 * tools operate on.</p>
 *
 * <p>Resolution and authorization both go through {@link MCPDocumentAccess#resolveAndAuthorize(String,
 * Right)} for {@link Right#EDIT} before the document is loaded, so the per-wiki space filter is applied
 * and the existence of a protected document is never leaked. The save goes through
 * {@link com.xpn.xwiki.api.Document} so author attribution and save-time rights are applied.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPWriteDocumentTool.TOOL_ID)
@Singleton
public class MCPWriteDocumentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "write_document";

    /**
     * Marker prefixed to the save comment of every write made through this tool, making agent-made
     * revisions identifiable in document history (for review, filtering or reverting). Treat it as a
     * stable contract: tooling and administrators may match on it.
     */
    private static final String AI_COMMENT_PREFIX = "[AI] ";

    /**
     * Upper bound, in characters, on the document content accepted in a single call, so a pathological
     * call cannot persist an arbitrarily large document version. Same value as the edit tool's cap on
     * its resulting content.
     */
    private static final int MAX_CONTENT_CHARS = 1_000_000;

    /**
     * Cap on the saved version comment, comfortably under the database column limit (1023 characters).
     */
    private static final int MAX_COMMENT_CHARS = 1000;

    private static final String REFERENCE_PARAM = "reference";

    private static final String CONTENT_PARAM = "content";

    private static final String TITLE_PARAM = "title";

    private static final String BASE_VERSION_PARAM = "base_version";

    private static final String COMMENT_PARAM = "comment";

    private static final String MAJOR_PARAM = "major";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String DOCUMENT_QUOTE_PREFIX = "Document " + QUOTE;

    private static final String VIEW_ACTION = "view";

    private static final String VERSION_PREFIX = "Version: ";

    /**
     * The declared parameters for a cross-wiki-capable endpoint: one source for both the advertised input schema
     * and the typed argument accessors. This variant's {@code reference} description mentions cross-wiki reach,
     * and is also the variant used for argument parsing. The {@code content} parameter is declared here for the
     * schema but read raw in {@link #requireRawContent(Map)}, as the accessors trim string values and trimming
     * would alter the saved body.
     */
    private static final MCPToolSupport PARAMS = params(true);

    /**
     * The declared parameters advertised by a reach-off endpoint: the cross-wiki sentence is dropped from the
     * {@code reference} description so no cross-wiki capability is surfaced. Used only to build the advertised
     * schema, never for parsing.
     */
    private static final MCPToolSupport PARAMS_LOCAL = params(false);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private MCPWikiReach wikiReach;

    /**
     * Builds the declared parameter set, appending the cross-wiki sentence to the {@code reference} description
     * only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document reference to create or overwrite, e.g. \"Sandbox.WebHome\" "
            + "or \"xwiki:Help.Foo\".";
        if (crossWiki) {
            referenceDescription += " A wiki-id prefix targets another wiki when this endpoint has cross-wiki "
                + "reach (see list_wikis).";
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(CONTENT_PARAM, "The complete new document source.")
            .string(TITLE_PARAM, "Optional new title. Kept unchanged when omitted.")
            .string(BASE_VERSION_PARAM, "The version shown by get_document. Required to overwrite an existing "
                + "document; omit when creating a new one.")
            .string(COMMENT_PARAM, "Version comment shown in the document history. Stored prefixed with "
                + "[AI]. Default: a generic [AI] comment.")
            .bool(MAJOR_PARAM, "Set true to record this save as a major version. Default false (minor). "
                + "Creation is always major.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = this.wikiReach.isReachEnabled() ? PARAMS : PARAMS_LOCAL;
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description("Create a new XWiki document or replace the full content of an existing one. Write "
                + "XWiki 2.1 syntax, NOT Markdown - `man xwiki-syntax` is the reference. Overwriting requires "
                + "base_version from get_document. For small targeted changes prefer edit_document.")
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
        return "Create a document or replace its entire content.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                write_document replaces the ENTIRE document body with content; objects, attachments
                and rights set on the document are untouched. For targeted changes use edit_document
                instead.
                Write XWiki syntax: "= Heading =", "[[Label>>Target]]" - NOT "# Heading" or
                "[label](url)".

            EXAMPLES
                Create:      reference="Sandbox.New", title="Hello",
                             content="= Hello =\\n\\nBody."
                Rewrite:     get_document reference="Sandbox.WebHome" (note its Version line),
                             then write_document reference="Sandbox.WebHome", base_version="4.3",
                             content="= Rewritten =\\n\\nNew body."
                Conflict:    a "Version conflict" result means the document changed since you read
                             it - re-read it with get_document and retry with the current version.

            SEE ALSO
                man edit_document   Targeted exact-string edits; preferred for small changes.
                man get_document    Read a document's source and current version.
                man xwiki-syntax    XWiki 2.1 syntax reference for writing page source.
                man                 (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String reference = PARAMS.requireString(args, REFERENCE_PARAM);
            String rawContent = requireRawContent(args);
            if (rawContent.length() > MAX_CONTENT_CHARS) {
                throw new IllegalArgumentException("Error: the content exceeds the maximum size ("
                    + MAX_CONTENT_CHARS + " characters).");
            }
            String content = MCPSourceText.normalizeLineEndings(rawContent);
            String title = PARAMS.string(args, TITLE_PARAM);
            String baseVersion = PARAMS.string(args, BASE_VERSION_PARAM);
            String comment = PARAMS.string(args, COMMENT_PARAM);
            boolean major = PARAMS.bool(args, MAJOR_PARAM);

            DocumentReference ref;
            try {
                ref = this.documentAccess.resolveAndAuthorize(reference, Right.EDIT);
            } catch (MCPAccessDeniedException e) {
                return MCPToolSupport.errorResult(e.getMessage());
            }

            return writeAndSave(ref, reference, content, title, baseVersion, comment, major);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP write_document tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP write_document tool failure details", e);
            return MCPToolSupport.errorResult("Could not save the document. See the server logs for details.");
        }
    }

    private McpSchema.CallToolResult writeAndSave(DocumentReference ref, String reference, String content,
        String title, String baseVersion, String comment, boolean major) throws XWikiException
    {
        XWikiContext xcontext = this.contextProvider.get();
        if (xcontext == null || xcontext.getUserReference() == null) {
            return MCPToolSupport.errorResult("No authenticated user in context.");
        }
        String originalWiki = xcontext.getWikiId();
        String targetWiki = ref.getWikiReference().getName();
        // The save must run in the target wiki so save-time rights and class resolution are correct for a
        // cross-wiki write; restore the original context wiki afterwards.
        try {
            xcontext.setWikiId(targetWiki);
            XWiki xwiki = xcontext.getWiki();

            XWikiDocument xdoc = xwiki.getDocument(ref, xcontext);
            boolean creating = xdoc.isNew();
            String oldVersion = xdoc.getVersion();

            McpSchema.CallToolResult workflowProblem = checkBaseVersion(reference, baseVersion, creating, oldVersion);
            if (workflowProblem != null) {
                return workflowProblem;
            }

            boolean titleChanged = title != null && !title.equals(xdoc.getTitle());
            if (!creating && content.equals(MCPSourceText.normalizeLineEndings(xdoc.getContent())) && !titleChanged) {
                return MCPToolSupport.result("No changes: the given content is identical to the current document. "
                    + "Nothing was saved.");
            }

            Document apiDoc = new Document(xdoc, xcontext);
            apiDoc.setContent(content);
            if (titleChanged) {
                apiDoc.setTitle(title);
            }
            apiDoc.save(buildComment(creating, comment), isMinorEdit(creating, major));

            return MCPToolSupport.result(buildSuccessResult(ref, creating, title != null, titleChanged, oldVersion,
                apiDoc.getVersion(), syntaxIdOf(xdoc)));
        } finally {
            xcontext.setWikiId(originalWiki);
        }
    }

    /**
     * Checks the document's state against the {@code base_version} workflow before anything is written:
     * an overwrite must carry the version the agent read (so the agent has provably read the document
     * first), a creation must not carry one, and a stale version is refused.
     *
     * <p>The version check is best-effort: a concurrent save landing between this check and the save
     * below can still win. It protects the agent's read-modify-write loop against stale reads, not
     * transactional integrity.</p>
     *
     * @param reference the original reference string, for error messages
     * @param baseVersion the version the agent read, or {@code null} when none was given
     * @param creating whether the document does not exist yet
     * @param currentVersion the document's current version
     * @return an error result describing the problem, or {@code null} when the save may proceed
     */
    private McpSchema.CallToolResult checkBaseVersion(String reference, String baseVersion, boolean creating,
        String currentVersion)
    {
        if (creating) {
            if (baseVersion != null) {
                return MCPToolSupport.errorResult(DOCUMENT_QUOTE_PREFIX + reference + QUOTE + " does not exist; "
                    + "omit base_version when creating a document.");
            }
            return null;
        }
        if (baseVersion == null) {
            return MCPToolSupport.errorResult(DOCUMENT_QUOTE_PREFIX + reference + QUOTE + " already exists. To "
                + "overwrite it, first read it with get_document and pass the base_version it shows. For small "
                + "changes prefer edit_document.");
        }
        if (!baseVersion.equals(currentVersion)) {
            return MCPToolSupport.errorResult("Version conflict: the document is now at version " + currentVersion
                + " but base_version is " + baseVersion + ". Re-read it with get_document and retry.");
        }
        return null;
    }

    /**
     * Reads the {@code content} argument without the support layer's trimming: the value is the complete
     * document source, so leading and trailing whitespace (e.g. a final newline) is significant and must
     * be preserved byte-for-byte.
     *
     * @param args the tool call arguments
     * @return the raw content string, never {@code null} (an empty string is a valid empty body)
     * @throws IllegalArgumentException with the agent-facing message if the value is absent or not a string
     */
    private static String requireRawContent(Map<String, Object> args)
    {
        Object value = args.get(CONTENT_PARAM);
        if (value == null) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + CONTENT_PARAM
                + "' parameter is required.");
        }
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + CONTENT_PARAM
                + "' parameter must be a string.");
        }
        return str;
    }

    /**
     * Decides whether the save is recorded as a minor edit. A creation is a normal (major) save - a new
     * document is version 1.1 regardless, so an explicit {@code major} request is accepted and ignored.
     * An overwrite is minor unless the caller explicitly asks for a major version, so iterative agent
     * writes do not inflate the major version or clutter the default history view.
     *
     * @param creating whether the document is being created
     * @param major whether the caller asked for a major version
     * @return whether the save is a minor edit
     */
    private static boolean isMinorEdit(boolean creating, boolean major)
    {
        return !creating && !major;
    }

    /**
     * Builds the version comment for the save: the agent-supplied comment when one was given, otherwise a
     * generated summary of the change. The {@link #AI_COMMENT_PREFIX} marker is always prepended, and the
     * combined comment is truncated to {@link #MAX_COMMENT_CHARS} to fit the history storage.
     *
     * @param creating whether the document is being created
     * @param agentComment the agent-supplied comment, or {@code null} when none was given
     * @return the comment to record in the document history
     */
    private static String buildComment(boolean creating, String agentComment)
    {
        String combined;
        if (StringUtils.isNotBlank(agentComment)) {
            combined = AI_COMMENT_PREFIX + agentComment;
        } else if (creating) {
            combined = AI_COMMENT_PREFIX + "Created document";
        } else {
            combined = AI_COMMENT_PREFIX + "Replaced content";
        }
        return StringUtils.abbreviate(combined, MAX_COMMENT_CHARS);
    }

    private String buildSuccessResult(DocumentReference ref, boolean creating, boolean titleGiven,
        boolean titleChanged, String oldVersion, String newVersion, String syntaxId)
    {
        String canonicalRef = this.serializer.serialize(ref);
        StringBuilder sb = new StringBuilder();
        if (creating) {
            sb.append("Created document ").append(canonicalRef).append(PERIOD).append(NEW_LINE);
            sb.append(VERSION_PREFIX).append(newVersion).append(NEW_LINE);
            sb.append("Syntax: ").append(syntaxId);
            if (titleGiven) {
                sb.append(NEW_LINE).append("Title set.");
            }
        } else {
            sb.append("Overwrote document ").append(canonicalRef).append(PERIOD).append(NEW_LINE);
            sb.append(VERSION_PREFIX).append(oldVersion).append(" -> ").append(newVersion);
            if (titleChanged) {
                sb.append(NEW_LINE).append("Title updated.");
            }
        }
        String urlLine = buildReviewLine(ref, creating, oldVersion, newVersion);
        if (urlLine != null) {
            sb.append(NEW_LINE).append(urlLine);
        }
        return sb.toString();
    }

    private String buildReviewLine(DocumentReference ref, boolean creating, String oldVersion, String newVersion)
    {
        if (creating) {
            String viewUrl = safeDocumentUrl(ref, null);
            return viewUrl != null ? "View: " + viewUrl : null;
        }
        String query = "viewer=changes&rev1=" + oldVersion + "&rev2=" + newVersion;
        String compareUrl = safeDocumentUrl(ref, query);
        return compareUrl != null ? "Compare: " + compareUrl : null;
    }

    private String safeDocumentUrl(DocumentReference docRef, String queryString)
    {
        try {
            return this.documentAccessBridge.getDocumentURL(docRef, VIEW_ACTION, queryString, null, true);
        } catch (Exception e) {
            this.logger.debug("MCP write_document tool could not build a URL", e);
            return null;
        }
    }

    /**
     * Returns the syntax identifier of the given document, for the creation result (the agent learns
     * which syntax the wiki assigned to the new document).
     *
     * @param xdoc the document
     * @return the syntax identifier, or {@code "unknown"} when the document has no syntax set
     */
    private static String syntaxIdOf(XWikiDocument xdoc)
    {
        var syntax = xdoc.getSyntax();
        return syntax != null ? syntax.toIdString() : "unknown";
    }
}
