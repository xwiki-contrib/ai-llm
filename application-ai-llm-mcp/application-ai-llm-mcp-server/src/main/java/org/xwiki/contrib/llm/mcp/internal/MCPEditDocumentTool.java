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

import java.util.ArrayList;
import java.util.List;
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
 * MCP tool that creates or edits an XWiki document by exact-string search-and-replace on its raw
 * source, mirroring Claude Code's Edit tool.
 *
 * <p>This is a default tool bundled with the MCP server module. It applies an ordered array of edits
 * ({@code old_string} -&gt; {@code new_string}, optionally {@code replace_all}) to the document's raw
 * source, can set the title, and can create a document. All edits are applied to an in-memory copy and
 * the document is saved exactly once as a single new version.</p>
 *
 * <p>The source the edits operate on is line-ending normalized with
 * {@link MCPSourceText#normalizeLineEndings(String)}, so an {@code old_string} copied verbatim from the
 * read tool's output (minus the line-number prefix) matches the same representation.</p>
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
@Named(MCPEditDocumentTool.TOOL_ID)
@Singleton
public class MCPEditDocumentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "edit_document";

    /**
     * Marker prefixed to the save comment of every edit made through this tool, making agent-made
     * revisions identifiable in document history (for review, filtering or reverting). Treat it as a
     * stable contract: tooling and administrators may match on it.
     */
    private static final String AI_COMMENT_PREFIX = "[AI] ";

    /**
     * Lines of context shown around each change in the result echo.
     */
    private static final int CONTEXT_LINES = 3;

    /**
     * Cap on the total context-echo emitted in the result, so a large batch of edits cannot flood the
     * agent's context window.
     */
    private static final int MAX_ECHO_CHARS = 2000;

    /**
     * Upper bound on the number of edits accepted in a single call, so a pathological batch cannot tie up
     * the worker thread.
     */
    private static final int MAX_EDITS = 100;

    /**
     * Upper bound, in characters, on each individual {@code old_string}/{@code new_string}, bounding the work
     * of a single search-and-replace.
     */
    private static final int MAX_EDIT_STRING_CHARS = 100_000;

    /**
     * Upper bound, in characters, on the resulting document content, so a {@code replace_all} amplification
     * cannot persist an arbitrarily large document version.
     */
    private static final int MAX_CONTENT_CHARS = 1_000_000;

    /**
     * Cap on the saved version comment, comfortably under the database column limit (1023 characters).
     */
    private static final int MAX_COMMENT_CHARS = 1000;

    private static final String REFERENCE_PARAM = "reference";

    private static final String EDITS_PARAM = "edits";

    private static final String TITLE_PARAM = "title";

    private static final String BASE_VERSION_PARAM = "base_version";

    private static final String COMMENT_PARAM = "comment";

    private static final String MAJOR_PARAM = "major";

    private static final String OLD_STRING_KEY = "old_string";

    private static final String NEW_STRING_KEY = "new_string";

    private static final String REPLACE_ALL_KEY = "replace_all";

    private static final String OBJECT = "object";

    private static final String STRING = "string";

    private static final String BOOLEAN = "boolean";

    private static final String ARRAY = "array";

    private static final String TYPE = "type";

    private static final String DESCRIPTION = "description";

    private static final String ITEMS = "items";

    private static final String PROPERTIES = "properties";

    private static final String REQUIRED = "required";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String DOCUMENT_PREFIX = "Document " + QUOTE;

    private static final String OPEN_PARENTHETICAL = " (";

    private static final String VIEW_ACTION = "view";

    private static final String VERSION_PREFIX = "Version: ";

    private static final String EDIT_MARKER_PREFIX = "@@ edit ";

    /**
     * Prefix for per-edit errors caused by a malformed call (the call as constructed can never succeed).
     */
    private static final String EDIT_INFIX = "Error: edit ";

    /**
     * Prefix for per-edit errors caused by document state (the call was well-formed but the current
     * content did not permit it; the agent should re-read and retry).
     */
    private static final String EDIT_STATE_INFIX = "Edit ";

    private static final String CHARACTERS_SUFFIX = " characters).";

    private static final String NOT_FOUND_HINT =
        ": old_string not found. The document may have changed since you read it - re-read it with get_document "
            + "and retry.";

    /**
     * The declared scalar parameters for a cross-wiki-capable endpoint: one source for both the advertised
     * input schema and the typed argument accessors. This variant's {@code reference} description mentions
     * cross-wiki reach, and is also the variant used for argument parsing. The {@code edits} array parameter is
     * hand-built in {@link #getToolDefinition()} and merged into the schema, as nested schemas are out of the
     * declaration's scope.
     */
    private static final MCPToolSupport PARAMS = params(true);

    /**
     * The declared scalar parameters advertised by a reach-off endpoint: the cross-wiki sentence and the
     * wiki-prefixed reference example are dropped from the {@code reference} description so no cross-wiki
     * capability is surfaced. Used only to build the advertised schema, never for parsing.
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
     * Builds the declared scalar parameter set, using a wiki-prefixed reference example and the cross-wiki
     * sentence in the {@code reference} description only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared scalar parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document reference to edit or create, e.g. \"Sandbox.WebHome\" "
            + "or \"" + (crossWiki ? "xwiki:" : "") + "Help.Foo\".";
        if (crossWiki) {
            referenceDescription += " A wiki-id prefix reaches another wiki (see list_wikis).";
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .string(TITLE_PARAM, "Optional new title. May be set alone (retitle) or together with edits.")
            .string(BASE_VERSION_PARAM, "Optional optimistic lock: the document version you read (shown by "
                + "get_document). The save is refused (best-effort check at save time) if the document has "
                + "changed since, instead of silently overwriting the concurrent change.")
            .string(COMMENT_PARAM, "Version comment shown in the document history. Stored prefixed with "
                + "[AI]. Default: a generic [AI] comment.")
            .bool(MAJOR_PARAM, "Set true to record this edit as a major version. Default false (minor). "
                + "Creation is always major.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        Map<String, Object> editItemSchema = Map.of(
            TYPE, OBJECT,
            PROPERTIES, Map.of(
                OLD_STRING_KEY, Map.of(
                    TYPE, STRING,
                    DESCRIPTION, "Exact source text to replace, copied verbatim from get_document (no "
                        + "line-number prefix); it must match the current source exactly (whitespace included) "
                        + "and be unique unless replace_all is true. Empty old_string on a non-existent "
                        + "document writes new_string as the whole body."
                ),
                NEW_STRING_KEY, Map.of(
                    TYPE, STRING,
                    DESCRIPTION, "Replacement text."
                ),
                REPLACE_ALL_KEY, Map.of(
                    TYPE, BOOLEAN,
                    DESCRIPTION, "Replace every occurrence instead of requiring a unique match."
                )
            ),
            REQUIRED, List.of(OLD_STRING_KEY, NEW_STRING_KEY)
        );
        Map<String, Object> editsProperty = Map.of(
            TYPE, ARRAY,
            DESCRIPTION, "Edits applied in order, then saved as a single version.",
            ITEMS, editItemSchema
        );
        MCPToolSupport schema = this.wikiReach.isReachEnabled() ? PARAMS : PARAMS_LOCAL;
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema(Map.of(EDITS_PARAM, editsProperty)))
            .description("Edit an XWiki document by exact search-and-replace on its raw source (the "
                + "text returned by get_document - always read first). For targeted changes; to create a "
                + "document prefer write_document. Write XWiki 2.1 syntax, NOT Markdown - `man xwiki-syntax` "
                + "is the reference; `man edit_document` shows examples.")
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
        return "Edit a document's content and title by exact string replacement.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                Copy old_string verbatim from get_document output, without the line-number prefix.
                If old_string is not found, the document changed since you read it - re-read and retry.
                When editing, match the document's existing syntax (shown by get_document). New documents
                use XWiki syntax: "= Heading =", "[[Label>>Target]]" - NOT "# Heading" or "[label](url)".

            EXAMPLES
                Edit:        reference="Sandbox.WebHome", edits=[{"old_string":"old","new_string":"new"}]
                Replace all: edits=[{"old_string":"foo","new_string":"bar","replace_all":true}]
                Safe edit:   reference="Sandbox.WebHome", base_version="4.3", edits=[...]
                             (refused if the document is no longer at the version you read)
                Comment:     reference="Sandbox.WebHome", comment="fix typo in installation steps",
                             edits=[{"old_string":"teh","new_string":"the"}]
                Retitle:     reference="Sandbox.WebHome", title="New Title"

            SEE ALSO
                man write_document  Create a document or replace its entire content.
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
            String title = PARAMS.string(args, TITLE_PARAM);
            String baseVersion = PARAMS.string(args, BASE_VERSION_PARAM);
            String comment = PARAMS.string(args, COMMENT_PARAM);
            boolean major = PARAMS.bool(args, MAJOR_PARAM);
            List<EditOp> edits = parseEdits(args);
            if (edits.isEmpty() && title == null) {
                throw new IllegalArgumentException("Error: provide at least one edit or a title.");
            }
            if (edits.size() > MAX_EDITS) {
                throw new IllegalArgumentException("Error: too many edits (max " + MAX_EDITS + ").");
            }

            DocumentReference ref;
            try {
                ref = this.documentAccess.resolveAndAuthorize(reference, Right.EDIT);
            } catch (MCPAccessDeniedException e) {
                return MCPToolSupport.errorResult(e.getMessage());
            }

            return applyAndSave(ref, reference, title, baseVersion, edits, comment, major);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP edit_document tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP edit_document tool failure details", e);
            return MCPToolSupport.errorResult("Could not save the document. Try again; if it persists, report "
                + "it to a wiki administrator (details are in the server logs).");
        }
    }

    private McpSchema.CallToolResult applyAndSave(DocumentReference ref, String reference, String title,
        String baseVersion, List<EditOp> edits, String comment, boolean major) throws XWikiException
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
            String original = MCPSourceText.normalizeLineEndings(xdoc.getContent());

            McpSchema.CallToolResult versionConflict = checkBaseVersion(reference, baseVersion, creating, oldVersion);
            if (versionConflict != null) {
                return versionConflict;
            }

            List<AppliedEdit> appliedReplacements = new ArrayList<>();
            String newContent = computeNewContent(reference, creating, original, edits, appliedReplacements);
            if (newContent.length() > MAX_CONTENT_CHARS) {
                throw new IllegalArgumentException("Error: the resulting content exceeds the maximum size ("
                    + MAX_CONTENT_CHARS + CHARACTERS_SUFFIX);
            }

            boolean titleChanged = title != null && !title.equals(xdoc.getTitle());

            if (!creating && newContent.equals(original) && !titleChanged) {
                return MCPToolSupport.result("No changes: the edits produced content identical to the current "
                    + "document. Nothing was saved.");
            }

            Document apiDoc = prepareSave(xdoc, xcontext, newContent, original, titleChanged, title);
            apiDoc.save(buildComment(creating, edits.size(), titleChanged, comment), isMinorEdit(creating, major));

            SaveOutcome outcome = new SaveOutcome(ref, creating, title != null,
                titleChanged, oldVersion, apiDoc.getVersion(), edits.size(), newContent, appliedReplacements);
            return MCPToolSupport.result(buildSuccessResult(outcome));
        } finally {
            xcontext.setWikiId(originalWiki);
        }
    }

    /**
     * Stages the content and title changes on the API document wrapper, applying only what actually changed.
     * A title-only save must leave the body bytes untouched: the new content is the LF-normalized copy of the
     * source, so writing it back unchanged would silently rewrite a CRLF document's line endings into a
     * whole-body diff.
     *
     * @param xdoc the loaded document
     * @param xcontext the XWiki context
     * @param newContent the edited, LF-normalized content
     * @param original the LF-normalized content the document had before the edits
     * @param titleChanged whether a new title was requested and differs from the current one
     * @param title the requested title, or {@code null} when not requested
     * @return the API document wrapper with the changes staged, ready to save
     */
    private Document prepareSave(XWikiDocument xdoc, XWikiContext xcontext, String newContent, String original,
        boolean titleChanged, String title)
    {
        Document apiDoc = new Document(xdoc, xcontext);
        if (!newContent.equals(original)) {
            apiDoc.setContent(newContent);
        }
        if (titleChanged) {
            apiDoc.setTitle(title);
        }
        return apiDoc;
    }

    /**
     * Checks the optional {@code base_version} optimistic lock against the version the document actually
     * has now, before any edit is attempted.
     *
     * <p>The check is best-effort: a concurrent save landing between this check and the save below can
     * still win. It protects the agent's read-modify-write loop against stale reads, not transactional
     * integrity.</p>
     *
     * @param reference the original reference string, for error messages
     * @param baseVersion the version the agent read, or {@code null} when the lock was not requested
     * @param creating whether the document does not exist yet
     * @param currentVersion the document's current version
     * @return an error result describing the conflict, or {@code null} when the save may proceed
     */
    private McpSchema.CallToolResult checkBaseVersion(String reference, String baseVersion, boolean creating,
        String currentVersion)
    {
        if (baseVersion == null) {
            return null;
        }
        if (creating) {
            return MCPToolSupport.errorResult(DOCUMENT_PREFIX + reference + QUOTE + " does not exist; omit "
                + "base_version when creating a document.");
        }
        if (!baseVersion.equals(currentVersion)) {
            return MCPToolSupport.errorResult("Version conflict: the document is now at version "
                + currentVersion + " but base_version is " + baseVersion + ". Re-read it with get_document "
                + "and re-apply your edits.");
        }
        return null;
    }

    private List<EditOp> parseEdits(Map<String, Object> args)
    {
        Object raw = args.get(EDITS_PARAM);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException(
                MCPToolSupport.ERROR_PREFIX + EDITS_PARAM + "' parameter must be an array.");
        }
        List<EditOp> edits = new ArrayList<>();
        for (Object element : rawList) {
            edits.add(parseEdit(element));
        }
        return edits;
    }

    private EditOp parseEdit(Object element)
    {
        if (!(element instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                "Error: each edit must be an object with 'old_string' and 'new_string'.");
        }
        String oldString = requireEditString(map.get(OLD_STRING_KEY),
            "Error: each edit requires a string 'old_string'.");
        String newString = requireEditString(map.get(NEW_STRING_KEY),
            "Error: each edit requires a string 'new_string'.");
        if (oldString.length() > MAX_EDIT_STRING_CHARS || newString.length() > MAX_EDIT_STRING_CHARS) {
            throw new IllegalArgumentException("Error: an edit string exceeds the maximum length ("
                + MAX_EDIT_STRING_CHARS + CHARACTERS_SUFFIX);
        }
        boolean replaceAll = MCPToolSupport.booleanValue(map.get(REPLACE_ALL_KEY), REPLACE_ALL_KEY);
        return new EditOp(MCPSourceText.normalizeLineEndings(oldString),
            MCPSourceText.normalizeLineEndings(newString), replaceAll);
    }

    private String requireEditString(Object value, String error)
    {
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException(error);
        }
        return str;
    }

    private String computeNewContent(String reference, boolean creating, String original, List<EditOp> edits,
        List<AppliedEdit> appliedReplacements)
    {
        if (creating) {
            return computeCreateContent(reference, edits, appliedReplacements);
        }
        return computeEditContent(original, edits, appliedReplacements);
    }

    private String computeCreateContent(String reference, List<EditOp> edits, List<AppliedEdit> appliedReplacements)
    {
        if (edits.size() > 1) {
            throw new IllegalArgumentException("Error: to create a document, send at most one edit (with an "
                + "empty old_string) plus an optional title.");
        }
        if (edits.isEmpty()) {
            return "";
        }
        EditOp edit = edits.get(0);
        if (!edit.oldString().isEmpty()) {
            throw new IllegalArgumentException(DOCUMENT_PREFIX + reference + QUOTE + " does not exist. To "
                + "create it, send a single edit with an empty old_string (its new_string becomes the body).");
        }
        appliedReplacements.add(new AppliedEdit(edit.newString(), 1));
        return edit.newString();
    }

    private String computeEditContent(String original, List<EditOp> edits, List<AppliedEdit> appliedReplacements)
    {
        String working = original;
        for (int i = 0; i < edits.size(); i++) {
            EditOp edit = edits.get(i);
            int editNumber = i + 1;
            if (edit.oldString().isEmpty()) {
                throw new IllegalArgumentException(EDIT_INFIX + editNumber
                    + ": old_string cannot be empty when editing an existing document.");
            }
            int count = StringUtils.countMatches(working, edit.oldString());
            if (count == 0) {
                throw new IllegalArgumentException(EDIT_STATE_INFIX + editNumber + NOT_FOUND_HINT);
            }
            if (count > 1 && !edit.replaceAll()) {
                throw new IllegalArgumentException(EDIT_STATE_INFIX + editNumber + ": old_string found " + count
                    + " times. Add more surrounding context to make it unique, or set replace_all.");
            }
            working = edit.replaceAll()
                ? working.replace(edit.oldString(), edit.newString())
                : replaceOnce(working, edit.oldString(), edit.newString());
            appliedReplacements.add(new AppliedEdit(edit.newString(), edit.replaceAll() ? count : 1));
        }
        return working;
    }

    /**
     * Replaces the first literal occurrence of {@code search} in {@code text}. The caller has already
     * verified at least one occurrence exists, so the search is always found.
     *
     * @param text the text to edit
     * @param search the literal substring to find
     * @param replacement the replacement text
     * @return the text with the first occurrence replaced
     */
    private static String replaceOnce(String text, String search, String replacement)
    {
        int index = text.indexOf(search);
        return text.substring(0, index) + replacement + text.substring(index + search.length());
    }

    /**
     * Decides whether the save is recorded as a minor edit. A creation is a normal (major) save - a new
     * document is version 1.1 regardless, so an explicit {@code major} request is accepted and ignored.
     * Subsequent edits are minor unless the caller explicitly asks for a major version, so iterative
     * agent edits do not inflate the major version or clutter the default history view.
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
     * @param editCount the number of edits applied
     * @param titleChanged whether the title changed
     * @param agentComment the agent-supplied comment, or {@code null} when none was given
     * @return the comment to record in the document history
     */
    private String buildComment(boolean creating, int editCount, boolean titleChanged, String agentComment)
    {
        String combined;
        if (StringUtils.isNotBlank(agentComment)) {
            combined = AI_COMMENT_PREFIX + agentComment;
        } else if (creating) {
            combined = AI_COMMENT_PREFIX + "Created document";
        } else {
            combined = AI_COMMENT_PREFIX + editCount + (editCount == 1 ? " edit" : " edits")
                + (titleChanged ? ", retitled" : "");
        }
        return StringUtils.abbreviate(combined, MAX_COMMENT_CHARS);
    }

    private String buildSuccessResult(SaveOutcome outcome)
    {
        String canonicalRef = this.serializer.serialize(outcome.ref());
        StringBuilder sb = new StringBuilder();
        if (outcome.creating()) {
            sb.append("Created document ").append(canonicalRef).append(PERIOD).append(NEW_LINE);
            sb.append(VERSION_PREFIX).append(outcome.newVersion());
            if (outcome.titleGiven()) {
                sb.append(NEW_LINE).append("Title set.");
            }
        } else {
            sb.append("Updated document ").append(canonicalRef).append(PERIOD).append(NEW_LINE);
            sb.append(VERSION_PREFIX).append(outcome.oldVersion()).append(" -> ").append(outcome.newVersion())
                .append(NEW_LINE);
            sb.append(outcome.editCount()).append(" edit(s) applied");
            int totalReplacements = outcome.appliedReplacements().stream().mapToInt(AppliedEdit::count).sum();
            if (totalReplacements > outcome.editCount()) {
                sb.append(OPEN_PARENTHETICAL).append(totalReplacements).append(" replacements)");
            }
            sb.append(PERIOD);
            if (outcome.titleChanged()) {
                sb.append(" Title updated.");
            }
        }

        String urlLine = buildReviewLine(outcome.ref(), outcome.creating(), outcome.oldVersion(),
            outcome.newVersion());
        if (urlLine != null) {
            sb.append(NEW_LINE).append(urlLine);
        }

        String echo = buildContextEcho(outcome.newContent(), outcome.appliedReplacements());
        if (!echo.isEmpty()) {
            sb.append(NEW_LINE).append(NEW_LINE).append(echo);
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
            this.logger.debug("MCP edit_document tool could not build a URL", e);
            return null;
        }
    }

    /**
     * Builds a best-effort verification echo: for each applied replacement whose text is non-empty, locates its
     * first occurrence in the final content and shows a numbered window of context around it (annotated with
     * the replacement count when {@code replace_all} hit more than one site). A pure deletion (empty
     * new_string) is noted without a body, and an overwritten replacement is skipped. The echo is capped
     * at {@link #MAX_ECHO_CHARS}; the compare URL is authoritative.
     *
     * @param newContent the final saved content
     * @param appliedReplacements the applied edits, in order
     * @return the echo text, possibly empty
     */
    private String buildContextEcho(String newContent, List<AppliedEdit> appliedReplacements)
    {
        String[] finalLines = newContent.split(NEW_LINE, -1);
        int totalLines = finalLines.length;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < appliedReplacements.size(); i++) {
            int editNumber = i + 1;
            AppliedEdit applied = appliedReplacements.get(i);
            String block = buildEchoBlock(newContent, finalLines, totalLines, editNumber, applied);
            if (block == null) {
                continue;
            }
            if (sb.length() + block.length() > MAX_ECHO_CHARS) {
                sb.append("(further changes omitted)");
                break;
            }
            if (sb.length() > 0) {
                sb.append(NEW_LINE);
            }
            sb.append(block);
        }
        return sb.toString();
    }

    private String buildEchoBlock(String newContent, String[] finalLines, int totalLines, int editNumber,
        AppliedEdit applied)
    {
        String replacement = applied.newString();
        if (replacement.isEmpty()) {
            return EDIT_MARKER_PREFIX + editNumber + " (deletion" + multiCountSuffix(applied) + ") @@";
        }
        int charIndex = newContent.indexOf(replacement);
        if (charIndex < 0) {
            return null;
        }
        int spanStart = 1 + countLines(newContent, charIndex);
        int spanEnd = spanStart + countLines(replacement, replacement.length()) - (replacement.endsWith(NEW_LINE)
            ? 1 : 0);
        int from = Math.max(1, spanStart - CONTEXT_LINES);
        int to = Math.min(totalLines, spanEnd + CONTEXT_LINES);
        String annotation = applied.count() > 1
            ? OPEN_PARENTHETICAL + applied.count() + " replacements, showing the first)" : "";
        return EDIT_MARKER_PREFIX + editNumber + annotation + " @@" + NEW_LINE
            + MCPSourceText.numberedLines(finalLines, from, to);
    }

    private String multiCountSuffix(AppliedEdit applied)
    {
        return applied.count() > 1 ? ", " + applied.count() + " occurrences" : "";
    }

    private int countLines(String text, int upToIndex)
    {
        int count = 0;
        for (int i = 0; i < upToIndex && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * A single search-and-replace operation parsed from the request, with LF-normalized strings.
     *
     * @param oldString the exact source text to find (already LF-normalized)
     * @param newString the replacement text (already LF-normalized)
     * @param replaceAll whether to replace every occurrence rather than requiring a unique match
     * @version $Id$
     */
    private record EditOp(String oldString, String newString, boolean replaceAll)
    {
    }

    /**
     * One applied edit, as needed by the result rendering: its replacement text and how many occurrences
     * it actually replaced (more than one only for {@code replace_all}).
     *
     * @param newString the replacement text of the applied edit
     * @param count the number of occurrences replaced
     * @version $Id$
     */
    private record AppliedEdit(String newString, int count)
    {
    }

    /**
     * The carrier of everything the success result needs after a save, so that result rendering is a pure
     * function of this value.
     *
     * @param ref the resolved document reference, used both for the canonical display reference and the review URL
     * @param creating whether the document was created rather than updated
     * @param titleGiven whether a title argument was supplied (relevant only on create)
     * @param titleChanged whether the title actually changed
     * @param oldVersion the version before the save
     * @param newVersion the version after the save
     * @param editCount the number of edits applied
     * @param newContent the final saved content, used for the context echo
     * @param appliedReplacements the applied edits, in order, used for the context echo and replacement totals
     * @version $Id$
     */
    private record SaveOutcome(DocumentReference ref, boolean creating,
        boolean titleGiven, boolean titleChanged, String oldVersion, String newVersion, int editCount,
        String newContent, List<AppliedEdit> appliedReplacements)
    {
    }
}
