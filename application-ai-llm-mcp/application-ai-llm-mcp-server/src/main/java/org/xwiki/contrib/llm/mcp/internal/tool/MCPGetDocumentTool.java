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
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPReachAwareParams;
import org.xwiki.contrib.llm.mcp.MCPSourceText;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.contrib.llm.mcp.internal.server.MCPServerConfiguration;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.security.authorization.Right;
import org.xwiki.sheet.SheetManager;
import org.xwiki.xml.html.HTMLCleaner;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that reads an XWiki document's raw source content for an agent.
 *
 * <p>This is a default tool bundled with the MCP server module. It maps an agent's native
 * {@code cat}/{@code sed -n} read workflow onto document content: small documents are returned
 * in full, large documents degrade to a heading outline (a map, not the content), and an explicit
 * {@code offset}/{@code limit} range reads an arbitrary slice.</p>
 *
 * <p>The content returned is the document's raw, unrendered source with line endings normalized to LF,
 * so that an agent can later match the exact source it read when forming edits.</p>
 *
 * <p>Rendered modes complement the source read: {@code rendered=true} returns the executed view as
 * plain text, and adding {@code format="html"} keeps its structure (tables, links, message boxes) as
 * presentation-stripped HTML. Rendered HTML is not line-addressable, so large documents follow an
 * outline-then-section workflow instead: {@code outline=true} maps the heading anchors with
 * per-section sizes, and {@code section="#H..."} fetches one section. An over-budget section with no
 * sub-headings degrades to a chunk map whose positional anchors ({@code section="#(H.../2)"}) fetch
 * deterministic, budget-sized chunks of its content.</p>
 *
 * <p>Resolution and authorization both go through {@link MCPDocumentAccess#resolveAndAuthorize(String,
 * Right)} for {@link Right#VIEW} before the document is loaded, so the per-wiki space filter is applied
 * and the existence of a protected document is never leaked.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPGetDocumentTool.TOOL_ID)
@Singleton
public class MCPGetDocumentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "get_document";

    /**
     * Line separator of the composed response texts, shared with {@link MCPRenderedHtmlResponses}.
     */
    static final String NEW_LINE = "\n";

    /**
     * Separates the response header from the body, shared with {@link MCPRenderedHtmlResponses}.
     */
    static final String DOUBLE_NEW_LINE = "\n\n";

    /**
     * Double quote of the echoed argument renderings, shared with {@link MCPRenderedHtmlResponses}.
     */
    static final String QUOTE = "\"";

    /**
     * Sentence terminator of the composed message texts, shared with {@link MCPRenderedHtmlResponses}.
     */
    static final String PERIOD = ".";

    /**
     * Range separator of the shown-lines and shown-chunks texts, shared with
     * {@link MCPRenderedHtmlResponses}.
     */
    static final String DASH = "-";

    /**
     * Infix of the x-of-y range texts, shared with {@link MCPRenderedHtmlResponses}.
     */
    static final String OF_INFIX = " of ";

    /**
     * Body of a read whose emitted content is empty, shared by the source and rendered-HTML paths.
     */
    static final String NO_CONTENT_BODY = "Document has no content.";

    /**
     * Shared tail of the rendered-mode banners: the read-only warning that the executed view must not be
     * used to form {@code edit_document} match strings. Also closes the HTML banners homed in
     * {@link MCPRenderedHtmlResponses}.
     */
    static final String RENDERED_BANNER_TAIL =
        " READ-ONLY: do NOT copy text from here into edit_document; re-read "
            + "with rendered=false to get the editable source.";

    /**
     * Rough characters-per-token heuristic used only to surface an approximate token count to the agent.
     */
    private static final int CHARS_PER_TOKEN = MCPSourceText.CHARS_PER_TOKEN;

    /**
     * Approximate token budget for a single response, quoted in agent-facing text; the enforced limit
     * is its character equivalent {@link #MAX_OUTPUT_CHARS}. Homed in {@link MCPSourceText} so the
     * rendered-HTML chunk-map pages share the same budget.
     */
    private static final int MAX_OUTPUT_TOKENS = MCPSourceText.MAX_OUTPUT_TOKENS;

    /**
     * Cap on the source emitted in a single response. Documents at most this size are returned in full
     * automatically; above it the tool degrades to a heading outline and explicit offset/limit reads,
     * whose output is capped to the same budget. One larger read is cheaper for an agent than several
     * round trips, but an unbounded one could still flood the context window.
     */
    private static final int MAX_OUTPUT_CHARS = MCPSourceText.MAX_OUTPUT_CHARS;

    private static final String REFERENCE_PARAM = "reference";

    private static final String OFFSET_PARAM = "offset";

    private static final String LIMIT_PARAM = "limit";

    private static final String OUTLINE_PARAM = "outline";

    private static final String RENDERED_PARAM = "rendered";

    private static final String FORMAT_PARAM = "format";

    private static final String SECTION_PARAM = "section";

    private static final String DETAIL_PARAM = "detail";

    private static final String PLAIN_FORMAT = "plain";

    private static final String HTML_FORMAT = "html";

    /**
     * All accepted {@code format} values in display order, used for validation and the invalid-value
     * error message.
     */
    private static final List<String> FORMAT_VALUES = List.of(PLAIN_FORMAT, HTML_FORMAT);

    private static final String STRIPPED_DETAIL = "stripped";

    private static final String FULL_DETAIL = "full";

    /**
     * All accepted {@code detail} values in display order, used for validation and the invalid-value
     * error message.
     */
    private static final List<String> DETAIL_VALUES = List.of(STRIPPED_DETAIL, FULL_DETAIL);

    /**
     * The unit suffix of the token counts in the Size header lines.
     */
    private static final String TOKENS_UNIT = " tokens";

    /**
     * Separates the character and token counts in the Size header lines.
     */
    private static final String CHARS_TOKENS_INFIX = " chars · ~";

    private static final String WEBHOME = "WebHome";

    private static final String MUST_BE_AT_LEAST_ONE = "' must be >= 1.";

    private static final String UNKNOWN_SYNTAX = "unknown";

    private static final String READ_WITH_RANGE_HINT = "; read with offset/limit.";

    private static final String COULD_NOT_READ_PREFIX = "Could not read the document ";

    private static final String SHOWING_LINES_PREFIX = "Showing lines 1-";

    private static final String VIEW_ACTION = "view";

    private static final String VIEW_URL_FAILURE = "MCP get_document tool could not build the view URL";

    /**
     * Hint appended to a sparse (0- or 1-heading) source outline: a script-driven page's source often carries
     * no headings while its executed view does, so steer the agent to the rendered outline. Only appended when
     * rendered content is allowed on this wiki, so the advice never dead-ends in the rendered-disabled refusal.
     */
    private static final String SPARSE_OUTLINE_HINT =
        "Sparse outline; if this page is script-driven (macros/Velocity), try rendered=true, format=\"html\", "
            + "outline=true for the executed view's outline.";

    /**
     * Warning shown when a large document degrades to a heading outline instead of returning its content.
     */
    private static final String LARGE_DOC_OUTLINE_WARNING =
        "This is an OUTLINE (a map of headings) of a large document, NOT its content. "
            + "Read a section with offset/limit to see the source.";

    /**
     * Banner prepended to plain-text rendered output, warning that it is the executed view (not source).
     */
    private static final String RENDERED_BANNER =
        "RENDERED VIEW - plain text, macros executed and includes expanded (structure such as tables and "
            + "link targets is flattened)." + RENDERED_BANNER_TAIL;

    private static final String OFFSET_EQUALS = OFFSET_PARAM + "=";

    private static final String CONTINUATION_PREFIX = " Output truncated at the ~" + MAX_OUTPUT_TOKENS
        + "-token cap; continue with " + OFFSET_EQUALS;

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description so
     * no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPGetDocumentTool::params);

    /**
     * Error returned when an agent requests rendered output on an endpoint where rendered content is disabled
     * (the gate reads the source endpoint's flag).
     */
    private static final String RENDERED_DISABLED_ERROR =
        "Rendered content is disabled for this endpoint. Read the document without rendered=true (the raw "
            + "wiki source) instead.";

    /**
     * Shared truncation note of the full-read texts: a start-to-end read is still capped by the output budget
     * and continues with an offset.
     */
    private static final String BUDGET_TRUNCATION_NOTE =
        "output beyond the token budget is truncated with a continuation offset";

    /**
     * Shared tail of the requires-html-mode errors: {@code section} and {@code detail} are both only
     * meaningful in the rendered HTML mode.
     */
    private static final String REQUIRES_HTML_SUFFIX = "' requires rendered=true and format=\"html\".";

    /**
     * Error returned when {@code section} is given without the rendered HTML mode it addresses into.
     */
    private static final String SECTION_REQUIRES_HTML_ERROR = MCPToolSupport.ERROR_PREFIX
        + SECTION_PARAM + REQUIRES_HTML_SUFFIX + " Get the anchors first with "
        + "rendered=true, format=\"html\", outline=true.";

    /**
     * Error returned when {@code detail} is given without the rendered HTML mode it configures.
     */
    private static final String DETAIL_REQUIRES_HTML_ERROR =
        MCPToolSupport.ERROR_PREFIX + DETAIL_PARAM + REQUIRES_HTML_SUFFIX;

    /**
     * Error returned when {@code section} and {@code outline} are combined.
     */
    private static final String SECTION_WITH_OUTLINE_ERROR = MCPToolSupport.ERROR_PREFIX
        + SECTION_PARAM + "' cannot be combined with outline=true; request the outline first, then one section.";

    /**
     * Error returned when {@code offset}/{@code limit} are combined with the rendered HTML mode, whose
     * output has no meaningful line structure.
     */
    private static final String HTML_RANGE_ERROR = MCPToolSupport.ERROR_PREFIX
        + OFFSET_PARAM + "'/'" + LIMIT_PARAM + "' do not apply to format=\"html\" (rendered HTML is not "
        + "line-addressable). Use outline=true to map the sections, then section=\"#H...\" to read one.";

    /**
     * Error returned when the {@code section} value is blank once normalized.
     */
    private static final String BLANK_SECTION_ERROR = MCPToolSupport.ERROR_PREFIX
        + SECTION_PARAM + "' must be a heading anchor such as \"#HInstallation\" (see outline=true).";

    /**
     * Provenance note on a source read of an empty-body document displayed through a sheet. One of the
     * two tails below completes the sentence, depending on whether rendered content is allowed here.
     */
    private static final String SOURCE_SHEET_NOTE = "Note: the body source is empty; the displayed content is "
        + "produced by the sheet \"%s\".";

    /**
     * Provenance note on a source read of an empty-body document whose content lives in its xobjects.
     * One of the two tails below completes the sentence, depending on whether rendered content is
     * allowed here.
     */
    private static final String SOURCE_XOBJECT_NOTE = "Note: the body source is empty; this document's content "
        + "lives in its structured data (xobjects).";

    /**
     * Tail of the source provenance note when rendered content is allowed on this wiki: steer the agent
     * to the rendered view.
     */
    private static final String SOURCE_NOTE_RENDERED_ADVICE = " Read it with rendered=true, format=\"html\"; "
        + "editing the body will not change what users see.";

    /**
     * Tail of the source provenance note when rendered content is disabled on this wiki: the rendered
     * view cannot be offered, so only the edit warning remains.
     */
    private static final String SOURCE_NOTE_NO_RENDER_TAIL = " Editing the body will not change what users see.";

    /**
     * Provenance note on a rendered read of an empty-body document displayed through a sheet.
     */
    private static final String RENDERED_SHEET_NOTE = "Note: the body source is empty; this view is produced by "
        + "the sheet \"%s\" - body edits will not change it.";

    /**
     * Provenance note on a rendered read of an empty-body document whose content lives in its xobjects.
     */
    private static final String RENDERED_XOBJECT_NOTE = "Note: the body source is empty; this view is produced "
        + "from the document's structured data - body edits will not change it.";

    /**
     * Appended to the rendered provenance note in plain mode, where a sheet's raw-HTML output is dropped.
     */
    private static final String PLAIN_EMPTY_HINT = " If this view looks empty, the sheet's HTML output cannot be "
        + "shown as plain text - use format=\"html\".";

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private MCPServerConfiguration mcpConfig;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private HTMLCleaner htmlCleaner;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private SheetManager sheetManager;

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example and the cross-wiki sentence in
     * the {@code reference} description only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document reference to read, e.g. \"Help.GettingStarted\" or \""
            + (crossWiki ? "xwiki:" : "") + "Sandbox.WebHome\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .integer(OFFSET_PARAM, "1-based line number to start reading from. Use with limit to read a slice "
                + "of a large document.")
            .integer(LIMIT_PARAM, "Number of lines to read from offset. Omit (with offset=1) to read from the "
                + "start; " + BUDGET_TRUNCATION_NOTE + PERIOD)
            .bool(OUTLINE_PARAM, "If true, return the document's heading outline (a map with line numbers) "
                + "instead of its content. Default false.")
            .bool(RENDERED_PARAM, "If true, return the page RENDERED to plain text (macros executed, includes "
                + "expanded) - useful for script-driven pages whose source is opaque. Plain text, so "
                + "structure (tables, link targets) is flattened. Read-only: do NOT form edit strings "
                + "from it. Default false (raw editable source).")
            .string(FORMAT_PARAM, "Output format for rendered=true: \"plain\" (default) or \"html\". HTML "
                + "preserves structure (tables, links, message boxes) at a higher token cost.")
            .string(SECTION_PARAM, "With rendered=true and format=\"html\": return one section by its heading "
                + "anchor as listed by outline=true (e.g. \"#HInstallation\" or \"#(intro)\"), from that heading "
                + "to the next heading of the same or higher level.")
            .string(DETAIL_PARAM, "With rendered=true and format=\"html\": \"stripped\" (default) removes "
                + "presentation attributes for token economy; \"full\" keeps all markup attributes (long "
                + "values shortened) so you can verify attributes you wrote. Not browser-faithful either "
                + "way.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.advertised(this.wikiReach.isReachEnabled()).inputSchema())
            .description("Read an XWiki document's raw source content. Returns the full source if it fits "
                + "the ~" + MAX_OUTPUT_TOKENS + "-token output budget; if larger, returns a heading OUTLINE "
                + "(a map, not the content), or a capped head when the document has no headings. Output is "
                + "line-numbered (like cat -n); when forming edit strings later, do NOT include the "
                + "line-number prefix. Pass offset=1 with no limit to read from the start instead of the "
                + "outline (" + BUDGET_TRUNCATION_NOTE + "). For format=\"html\", offset/limit do not apply: "
                + "use outline=true, then section.")
            .build();
    }

    @Override
    public String getCategory()
    {
        return "Search & Navigation";
    }

    @Override
    public String getSummary()
    {
        return "Read an XWiki document's content (full, by line range, or outline).";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                A page whose body source is empty may still display content: a sheet or the page's
                structured data (xobjects) produces the view. Such reads carry a note; read the page
                with rendered=true, format="html" - editing the body will not change what users see.

            EXAMPLES
                Full read:  reference="Help.GettingStarted"
                A range:    reference="Help.GettingStarted", offset=80, limit=40
                Outline:    reference="Help.GettingStarted", outline=true
                Rendered:   reference="XWiki.XWikiSyntax", rendered=true  (macros executed; read-only)
                Rendered HTML:  reference="XWiki.XWikiSyntax", rendered=true, format="html"
                            (keeps tables, links and message boxes as HTML, but presentation - CSS
                            classes, styles, colors - is stripped and NOT browser-faithful; costs more
                            tokens than the plain default - use it for structure-heavy pages)
                Large doc:  first read with reference only (you get an OUTLINE map of headings),
                            then read a section with reference + offset + limit.
                HTML outline:   reference="XWiki.XWikiSyntax", rendered=true, format="html", outline=true
                            (a map of heading anchors with per-section sizes; "#(intro)" covers the
                            content before the first heading)
                HTML section:   reference="XWiki.XWikiSyntax", rendered=true, format="html",
                            section="#HHeadings"
                            (one section by its anchor, from the outline; offset/limit do not apply
                            to format="html")
                HTML chunk: an over-budget section with no sub-headings returns a CHUNK MAP (not
                            content); fetch one chunk with section="#((h3)/2)". Chunk anchors are
                            positional: re-read the map after the document is edited. Row chunks of
                            a table carry a Columns: header line naming its column headers.
                HTML full detail: after an edit_document call that writes markup attributes, verify
                            them with rendered=true, format="html", section="#HHeadings",
                            detail="full" (keeps ALL attributes - class, style, data-* - instead of
                            stripping presentation; long attribute values are shortened).

            SEE ALSO
                man xwiki-syntax    XWiki 2.1 syntax reference (for the editable source you read and write).
                man                 (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);

            DocumentReference ref;
            try {
                ref = this.documentAccess.resolveAndAuthorize(reference, Right.VIEW);
            } catch (MCPAccessDeniedException e) {
                return MCPToolSupport.errorResult(e.getMessage());
            }

            if (!documentExists(ref, reference)) {
                return MCPToolSupport.errorResult(notFoundMessage(ref, reference));
            }

            DocumentModelBridge doc = loadDocument(ref, reference);
            return read(args, ref, reference, doc);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }
    }

    /**
     * Reads the loaded document per the request arguments: parses and cross-validates the read
     * parameters, gates rendered mode, and dispatches to the source, rendered-plain or rendered-HTML
     * rendering path.
     *
     * @param args the tool call arguments
     * @param ref the resolved document reference
     * @param reference the original reference string, for error messages
     * @param doc the loaded document
     * @return the tool result
     * @throws IllegalArgumentException with an agent-facing message on invalid arguments or a failed read
     */
    private McpSchema.CallToolResult read(Map<String, Object> args, DocumentReference ref, String reference,
        DocumentModelBridge doc)
    {
        Integer offset = PARAMS.parser().integer(args, OFFSET_PARAM);
        Integer limit = PARAMS.parser().integer(args, LIMIT_PARAM);
        boolean outline = PARAMS.parser().bool(args, OUTLINE_PARAM);
        String section = PARAMS.parser().string(args, SECTION_PARAM);
        Syntax renderedSyntax = resolveRenderedSyntax(PARAMS.parser().string(args, FORMAT_PARAM),
            PARAMS.parser().bool(args, RENDERED_PARAM));
        boolean htmlMode = Syntax.HTML_5_0.equals(renderedSyntax);
        boolean fullDetail = resolveFullDetail(PARAMS.parser().string(args, DETAIL_PARAM), htmlMode);
        validateHtmlModeParams(htmlMode, section, outline, offset, limit);

        // Gate rendered mode after resolution/authorization (and existence), so a disabled-rendering refusal
        // never leaks existence beyond the normal flow. The gate reads the SOURCE endpoint's (context wiki's)
        // flag, since rendered mode is a capability of the endpoint being used, not of the wiki the document
        // happens to live in. Refuse clearly rather than silently returning the raw source, so the agent
        // re-requests with rendered=false.
        if (renderedSyntax != null
            && !this.mcpConfig.isRenderedContentAllowed(this.contextProvider.get().getWikiId())) {
            return MCPToolSupport.errorResult(RENDERED_DISABLED_ERROR);
        }

        String title;
        String content;
        if (renderedSyntax != null) {
            RenderedDocument renderedDoc = renderDocument(ref, reference, renderedSyntax);
            title = renderedDoc.title();
            content = MCPSourceText.normalizeLineEndings(renderedDoc.content());
            if (htmlMode) {
                return MCPRenderedHtmlResponses.parse(this, this.htmlCleaner, doc, title, content, fullDetail)
                    .respond(outline, section);
            }
            // Plain-mode only: the HTML path is cleaned structurally by MCPRenderedHtml (its description
            // block, holding the stack trace, is already removed), and it has <pre>/structure we must not
            // disturb. Plain renders the trace as undifferentiated text, so collapse frame runs here.
            content = MCPSourceText.collapseStackTraces(content);
        } else {
            title = doc.getTitle();
            content = MCPSourceText.normalizeLineEndings(doc.getContent());
        }
        return render(doc, title, content, renderedSyntax, offset, limit, outline);
    }

    /**
     * Cross-validates the parameters that only combine in specific ways with the rendered HTML mode:
     * {@code section} needs it, and {@code offset}/{@code limit} are meaningless within it (rendered
     * HTML has no meaningful line structure).
     *
     * @param htmlMode whether the rendered HTML mode was requested
     * @param section the raw {@code section} argument, or {@code null}
     * @param outline whether an outline was requested
     * @param offset the {@code offset} argument, or {@code null}
     * @param limit the {@code limit} argument, or {@code null}
     * @throws IllegalArgumentException with the agent-facing message on an invalid combination
     */
    private void validateHtmlModeParams(boolean htmlMode, String section, boolean outline, Integer offset,
        Integer limit)
    {
        if (section != null && !htmlMode) {
            throw new IllegalArgumentException(SECTION_REQUIRES_HTML_ERROR);
        }
        if (htmlMode && (offset != null || limit != null)) {
            throw new IllegalArgumentException(HTML_RANGE_ERROR);
        }
        if (section == null) {
            return;
        }
        if (outline) {
            throw new IllegalArgumentException(SECTION_WITH_OUTLINE_ERROR);
        }
        if (StringUtils.isBlank(MCPRenderedHtmlResponses.normalizeAnchor(section))) {
            throw new IllegalArgumentException(BLANK_SECTION_ERROR);
        }
    }

    private boolean documentExists(DocumentReference ref, String reference)
    {
        try {
            return this.documentAccessBridge.exists(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_document tool failed to check existence of [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_document tool existence-check failure details", e);
            throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE + reference + QUOTE + PERIOD);
        }
    }

    /**
     * Builds the not-found error, appending a space-home suggestion when the missing reference looks like a
     * space path an agent meant as the space home.
     *
     * @param ref the resolved reference that was found not to exist
     * @param reference the raw reference argument, echoed verbatim
     * @return the agent-facing error message
     */
    private String notFoundMessage(DocumentReference ref, String reference)
    {
        String message = "No such document: " + QUOTE + reference + QUOTE + PERIOD;
        String spaceHome = suggestSpaceHome(ref);
        if (spaceHome != null) {
            message += " Did you mean " + QUOTE + spaceHome + QUOTE + " (the home page of that space)?";
        }
        return message;
    }

    /**
     * When a reference resolves to a terminal document that does not exist, an agent has often passed a
     * space path (e.g. {@code "Docs.Guides"}) meaning the space home {@code Docs.Guides.WebHome}. This
     * reinterprets the missing document's own name as a nested space and returns that {@code WebHome}
     * reference when the page exists and the caller may view it, so the error can offer it as a
     * suggestion. Returns {@code null} when there is nothing safe to suggest.
     *
     * @param ref the resolved reference that was found not to exist
     * @return the serialized space-home reference to suggest, or {@code null}
     */
    private String suggestSpaceHome(DocumentReference ref)
    {
        // A missing "...WebHome" would only yield "...WebHome.WebHome"; never suggest that.
        if (WEBHOME.equals(ref.getName())) {
            return null;
        }
        // Reinterpret the missing document's own name as a nested space: "A.B" -> "A.B.WebHome". Appending
        // to the serialized (already-escaped) reference keeps names with dots correct.
        String candidate = this.serializer.serialize(ref) + PERIOD + WEBHOME;
        try {
            // Route the candidate through the same door as a real read so a space-filtered or view-denied
            // page is never surfaced as a suggestion.
            DocumentReference spaceHome = this.documentAccess.resolveAndAuthorize(candidate, Right.VIEW);
            return this.documentAccessBridge.exists(spaceHome) ? candidate : null;
        } catch (MCPAccessDeniedException e) {
            return null;
        } catch (Exception e) {
            this.logger.debug("MCP get_document tool space-home suggestion check failed", e);
            return null;
        }
    }

    private DocumentModelBridge loadDocument(DocumentReference ref, String reference)
    {
        try {
            return this.documentAccessBridge.getDocumentInstance(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_document tool failed to load [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_document tool load failure details", e);
            throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE + reference + QUOTE + PERIOD);
        }
    }

    /**
     * Resolves the {@code format} parameter into the rendering target syntax, or {@code null} when the
     * raw source is requested. {@code format} is only meaningful together with {@code rendered=true}, so
     * a format given without it is rejected as a malformed call rather than silently ignored.
     *
     * @param format the trimmed format value, or {@code null} when absent or blank
     * @param rendered whether rendered mode was requested
     * @return the rendering target syntax, or {@code null} when not rendered
     * @throws IllegalArgumentException with the agent-facing message when the format is given without
     *         rendered mode or is not an accepted value
     */
    private Syntax resolveRenderedSyntax(String format, boolean rendered)
    {
        if (format != null && !rendered) {
            throw new IllegalArgumentException(
                MCPToolSupport.ERROR_PREFIX + FORMAT_PARAM + "' requires rendered=true.");
        }
        if (!rendered) {
            return null;
        }
        String normalizedFormat = format == null ? null : format.toLowerCase(Locale.ROOT);
        if (normalizedFormat != null && !FORMAT_VALUES.contains(normalizedFormat)) {
            throw new IllegalArgumentException(invalidValueError(FORMAT_PARAM, FORMAT_VALUES));
        }
        return HTML_FORMAT.equals(normalizedFormat) ? Syntax.HTML_5_0 : Syntax.PLAIN_1_0;
    }

    /**
     * Resolves the {@code detail} parameter into the full-markup-detail flag. {@code detail} only
     * configures the rendered HTML mode, so a value given outside it is rejected as a malformed call
     * rather than silently ignored; an explicit {@code "stripped"} in HTML mode is the default spelled
     * out.
     *
     * @param detail the trimmed detail value, or {@code null} when absent or blank
     * @param htmlMode whether the rendered HTML mode was requested
     * @return whether the full markup detail was requested
     * @throws IllegalArgumentException with the agent-facing message when the detail is given outside
     *         the rendered HTML mode or is not an accepted value
     */
    private boolean resolveFullDetail(String detail, boolean htmlMode)
    {
        if (detail == null) {
            return false;
        }
        if (!htmlMode) {
            throw new IllegalArgumentException(DETAIL_REQUIRES_HTML_ERROR);
        }
        String normalizedDetail = detail.toLowerCase(Locale.ROOT);
        if (!DETAIL_VALUES.contains(normalizedDetail)) {
            throw new IllegalArgumentException(invalidValueError(DETAIL_PARAM, DETAIL_VALUES));
        }
        return FULL_DETAIL.equals(normalizedDetail);
    }

    /**
     * Formats the invalid-enum-value error of a parameter with a closed value set, listing the accepted
     * values in their display order.
     *
     * @param param the parameter name
     * @param values the accepted values
     * @return the agent-facing error message
     */
    private static String invalidValueError(String param, List<String> values)
    {
        return MCPToolSupport.ERROR_PREFIX + param + "' must be one of: " + String.join(", ", values) + PERIOD;
    }

    /**
     * Renders the document to the target output syntax the way a normal page view does: macros are executed
     * and includes expanded with the document content author's rights (via the {@code sdoc} set inside
     * {@link XWikiDocument#displayDocument}), not the current user's; the current user still only needs
     * VIEW (already checked). {@code displayDocument} is deterministic: it renders the addressed document
     * instance's own content, with no language-preference translation lookup - unlike
     * {@code getRenderedContent}, which can silently swap in a translation selected by the request's
     * language preference while the title, version and base_version all describe the default-locale
     * document. The rendered title is consistent by construction: {@code getRenderedTitle} never
     * translates.
     *
     * <p>An output syntax (not a wiki syntax) is the target on purpose: rendering to a wiki syntax such as
     * {@code xwiki/2.1} round-trips macros back into their {@code {{...}}} source calls (they are not
     * executed), defeating the point. An output syntax like {@code plain/1.0} or {@code html/5.0} emits the
     * executed result; an HTML result is subsequently parsed, stripped and composed into a response by
     * {@link MCPRenderedHtmlResponses}. The title is always rendered to plain text: a marked-up title buys
     * nothing in the header.</p>
     *
     * @param ref the resolved document reference
     * @param reference the original reference string, for error messages
     * @param targetSyntax the output syntax to render the content to
     * @return the rendered title and content
     * @throws IllegalArgumentException with an agent-facing message if the document cannot be loaded or rendered
     */
    private RenderedDocument renderDocument(DocumentReference ref, String reference, Syntax targetSyntax)
    {
        XWikiContext xcontext = this.contextProvider.get();
        XWikiDocument previousContextDocument = xcontext.getDoc();
        String originalWiki = xcontext.getWikiId();
        try {
            // Rendering must resolve in the document's own wiki (macros, rights, class resolution) for a
            // cross-wiki read, so switch the context wiki to the target for the duration of the render.
            xcontext.setWikiId(ref.getWikiReference().getName());
            XWikiDocument xdoc = xcontext.getWiki().getDocument(ref, xcontext);
            xcontext.setDoc(xdoc);
            // Render the title too (it may itself be a macro/translation), so the header is consistent with the
            // executed body instead of showing the raw title source.
            String renderedTitle = StringUtils.trim(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext));
            String renderedContent = xdoc.displayDocument(targetSyntax, xcontext);
            return new RenderedDocument(renderedTitle, renderedContent);
        } catch (Exception e) {
            this.logger.warn("MCP get_document tool failed to render [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_document tool render failure details", e);
            throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE + reference + QUOTE + PERIOD);
        } finally {
            xcontext.setDoc(previousContextDocument);
            xcontext.setWikiId(originalWiki);
        }
    }

    private McpSchema.CallToolResult render(DocumentModelBridge doc, String title, String content,
        Syntax renderedSyntax, Integer offset, Integer limit, boolean outline)
    {
        String syntaxId = headerSyntaxId(doc, renderedSyntax);
        String header = composeHeader(doc, title, content, renderedSyntax, null, false);

        boolean empty = content.isEmpty();
        String[] lines = content.split(NEW_LINE, -1);
        int totalLines = empty ? 0 : lines.length;

        if (outline) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + buildOutline(lines, totalLines, syntaxId));
        }
        if (offset != null || limit != null) {
            return renderRange(header, lines, totalLines, offset, limit);
        }
        if (empty) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + NO_CONTENT_BODY);
        }
        if (content.length() <= MAX_OUTPUT_CHARS) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + numberedBody(lines, 1, totalLines));
        }
        return MCPToolSupport.result(header + DOUBLE_NEW_LINE + renderLargeAutoDegrade(lines, totalLines, syntaxId));
    }

    /**
     * Prepends the rendered-mode banner matching the rendered output format and attribute detail, or
     * returns the header unchanged in source mode.
     *
     * @param header the built header
     * @param renderedSyntax the rendered output syntax, or {@code null} in source mode
     * @param fullDetail whether the full markup detail was requested (only meaningful in HTML mode)
     * @return the header, with the banner prepended in rendered mode
     */
    private String prependBanner(String header, Syntax renderedSyntax, boolean fullDetail)
    {
        if (renderedSyntax == null) {
            return header;
        }
        String banner;
        if (Syntax.HTML_5_0.equals(renderedSyntax)) {
            banner = fullDetail ? MCPRenderedHtmlResponses.RENDERED_FULL_HTML_BANNER
                : MCPRenderedHtmlResponses.RENDERED_HTML_BANNER;
        } else {
            banner = RENDERED_BANNER;
        }
        return banner + NEW_LINE + header;
    }

    /**
     * Composes the complete response header for the given emitted content: the reference block, the
     * metadata lines sized from the content, an optional extra line (after the Size line), the
     * provenance note when the body source is empty but the page displays sheet- or xobject-produced
     * content, and the rendered-mode banner. Package-private because the rendered-HTML responses
     * ({@link MCPRenderedHtmlResponses}) share this composition.
     *
     * @param doc the loaded document
     * @param title the title to display
     * @param content the content the response emits (or summarizes), used for the Size line
     * @param renderedSyntax the rendered output syntax, or {@code null} in source mode
     * @param extraLine an extra header line to insert after the Size line, or {@code null}
     * @param fullDetail whether the full markup detail was requested, for the banner
     * @return the composed header
     */
    String composeHeader(DocumentModelBridge doc, String title, String content, Syntax renderedSyntax,
        String extraLine, boolean fullDetail)
    {
        int totalLines = content.isEmpty() ? 0 : content.split(NEW_LINE, -1).length;
        int totalChars = content.length();
        String size = totalLines + " lines · " + totalChars + CHARS_TOKENS_INFIX
            + totalChars / CHARS_PER_TOKEN + TOKENS_UNIT;
        return composeHeaderLines(doc, title, size, renderedSyntax, extraLine, fullDetail);
    }

    /**
     * Composes the header of a rendered-HTML response that does not emit the content it describes (an
     * outline or a chunk map produced without serializing), sizing the Size line from the parse walk's
     * character estimate. Package-private because only the rendered-HTML responses
     * ({@link MCPRenderedHtmlResponses}) compose such headers.
     *
     * @param doc the loaded document
     * @param title the rendered title
     * @param approxChars the estimated character count of the described content
     * @param extraLine an extra header line to insert after the Size line, or {@code null}
     * @param fullDetail whether the full markup detail was requested, for the banner
     * @return the composed header
     */
    String composeEstimatedHeader(DocumentModelBridge doc, String title, int approxChars,
        String extraLine, boolean fullDetail)
    {
        String size = "~" + approxChars + CHARS_TOKENS_INFIX + approxChars / CHARS_PER_TOKEN + TOKENS_UNIT
            + " (estimated)";
        return composeHeaderLines(doc, title, size, Syntax.HTML_5_0, extraLine, fullDetail);
    }

    /**
     * Shared tail of the header composition: the reference block, the metadata lines with the given
     * Size description, an optional extra line, the provenance note and the rendered-mode banner.
     *
     * @param doc the loaded document
     * @param title the title to display
     * @param sizeDescription the formatted value of the Size line
     * @param renderedSyntax the rendered output syntax, or {@code null} in source mode
     * @param extraLine an extra header line to insert after the Size line, or {@code null}
     * @param fullDetail whether the full markup detail was requested, for the banner
     * @return the composed header
     */
    private String composeHeaderLines(DocumentModelBridge doc, String title, String sizeDescription,
        Syntax renderedSyntax, String extraLine, boolean fullDetail)
    {
        String header = buildHeader(buildReferenceBlock(doc), title, headerSyntaxId(doc, renderedSyntax),
            doc.getVersion(), sizeDescription);
        if (extraLine != null) {
            header += NEW_LINE + extraLine;
        }
        String note = provenanceNote(doc, renderedSyntax);
        if (note != null) {
            header += NEW_LINE + note;
        }
        return prependBanner(header, renderedSyntax, fullDetail);
    }

    /**
     * Resolves the syntax identifier the header (and the source outline's heading scan) reports: in
     * rendered mode the content is the executed output regardless of the document's source syntax, so
     * the rendered syntax is reported.
     *
     * @param doc the loaded document
     * @param renderedSyntax the rendered output syntax, or {@code null} in source mode
     * @return the syntax identifier
     */
    private String headerSyntaxId(DocumentModelBridge doc, Syntax renderedSyntax)
    {
        return renderedSyntax != null ? renderedSyntax.toIdString() : syntaxIdOf(doc.getSyntax());
    }

    /**
     * Builds the provenance note for a document whose body source is empty while its displayed content
     * is produced by a sheet or by its structured data (xobjects) - the situation that otherwise traps
     * an agent between a full view and an empty source. Returns {@code null} when the body has content
     * or when neither a sheet nor xobjects are present; the sheet lookup is only consulted for
     * empty-body documents.
     *
     * @param doc the loaded document
     * @param renderedSyntax the rendered output syntax, or {@code null} in source mode
     * @return the note, or {@code null} when no note applies
     */
    private String provenanceNote(DocumentModelBridge doc, Syntax renderedSyntax)
    {
        if (StringUtils.isNotBlank(doc.getContent())) {
            return null;
        }
        String sheetName = firstViewableSheetName(doc);
        if (sheetName == null && !hasXObjects(doc)) {
            return null;
        }
        if (renderedSyntax == null) {
            String base = sheetName != null ? String.format(SOURCE_SHEET_NOTE, sheetName) : SOURCE_XOBJECT_NOTE;
            // Only advise the rendered view when this wiki actually allows it; otherwise the advice
            // dead-ends in the rendered-disabled refusal.
            boolean renderingAllowed =
                this.mcpConfig.isRenderedContentAllowed(this.contextProvider.get().getWikiId());
            return base + (renderingAllowed ? SOURCE_NOTE_RENDERED_ADVICE : SOURCE_NOTE_NO_RENDER_TAIL);
        }
        String note = sheetName != null ? String.format(RENDERED_SHEET_NOTE, sheetName) : RENDERED_XOBJECT_NOTE;
        if (Syntax.PLAIN_1_0.equals(renderedSyntax)) {
            note += PLAIN_EMPTY_HINT;
        }
        return note;
    }

    /**
     * Names the first view sheet of the document that the current user may view, mirroring what the
     * display path applies. Lookup failures degrade silently (debug log) so a provenance nicety can
     * never break a read.
     *
     * @param doc the loaded document
     * @return the serialized sheet reference, or {@code null} when there is none (or the lookup failed)
     */
    private String firstViewableSheetName(DocumentModelBridge doc)
    {
        try {
            return this.sheetManager.getSheets(doc, VIEW_ACTION).stream()
                .filter(this.documentAccessBridge::isDocumentViewable)
                .findFirst()
                .map(this.serializer::serialize)
                .orElse(null);
        } catch (Exception e) {
            this.logger.debug("MCP get_document tool sheet lookup failed", e);
            return null;
        }
    }

    /**
     * Tests whether the document carries at least one xobject, tolerating the null slots left by
     * deleted objects.
     *
     * @param doc the loaded document
     * @return whether at least one xobject is present
     */
    private boolean hasXObjects(DocumentModelBridge doc)
    {
        return doc instanceof XWikiDocument xdoc
            && xdoc.getXObjects().values().stream()
                .filter(objects -> objects != null)
                .flatMap(List::stream)
                .anyMatch(object -> object != null);
    }

    /**
     * Builds the body for the auto-degrade path of a large document (no offset/limit, outline not requested).
     * When the document has headings, returns the outline warning plus the heading map; when it has none (or an
     * unsupported syntax), returns a conservative head window of content so the agent still gets something to
     * work with rather than an empty outline.
     *
     * @param lines the document lines
     * @param totalLines the total number of lines
     * @param syntaxId the document syntax identifier
     * @return the formatted auto-degrade body
     */
    private String renderLargeAutoDegrade(String[] lines, int totalLines, String syntaxId)
    {
        List<String> headings = MCPSourceText.collectHeadingLines(lines, totalLines, syntaxId);
        if (!headings.isEmpty()) {
            return LARGE_DOC_OUTLINE_WARNING + NEW_LINE + String.join(NEW_LINE, headings);
        }
        int headEnd = cappedEnd(lines, 1, totalLines, MAX_OUTPUT_CHARS);
        String body = numberedBody(lines, 1, headEnd);
        String footer = SHOWING_LINES_PREFIX + headEnd + OF_INFIX + totalLines + PERIOD;
        if (headEnd < totalLines) {
            footer += " Large document with no headings; continue with " + OFFSET_EQUALS + (headEnd + 1) + PERIOD;
        }
        return body + NEW_LINE + footer;
    }

    private McpSchema.CallToolResult renderRange(String header, String[] lines, int totalLines, Integer offset,
        Integer limit)
    {
        int start = offset != null ? offset : 1;
        if (start < 1) {
            return MCPToolSupport.errorResult(MCPToolSupport.ERROR_PREFIX + OFFSET_PARAM + MUST_BE_AT_LEAST_ONE);
        }
        if (limit != null && limit <= 0) {
            return MCPToolSupport.errorResult(MCPToolSupport.ERROR_PREFIX + LIMIT_PARAM + MUST_BE_AT_LEAST_ONE);
        }
        if (start > totalLines) {
            return MCPToolSupport.errorResult(
                "offset " + start + " exceeds document length (" + totalLines + " lines). Use an offset of at "
                    + "most " + totalLines + PERIOD);
        }
        long endLong = (limit != null) ? Math.min((long) start + limit - 1, totalLines) : totalLines;
        int end = (int) endLong;
        int actualEnd = cappedEnd(lines, start, end, MAX_OUTPUT_CHARS);
        boolean truncated = actualEnd < end;
        String body = numberedBody(lines, start, actualEnd);
        String footer = "Showing lines " + start + DASH + actualEnd + OF_INFIX + totalLines + PERIOD;
        if (truncated) {
            footer += CONTINUATION_PREFIX + (actualEnd + 1) + PERIOD;
        }
        return MCPToolSupport.result(header + DOUBLE_NEW_LINE + body + NEW_LINE + footer);
    }

    /**
     * Returns the largest line index in {@code [start..requestedEnd]} whose cumulative character count
     * (including a newline per line) does not exceed {@code maxChars}. Always returns at least {@code start},
     * so a single oversized line is emitted whole rather than truncated mid-line, preserving edit-ability.
     *
     * @param lines the document lines
     * @param start the 1-based first line to emit
     * @param requestedEnd the 1-based last line the caller asked for
     * @param maxChars the cumulative character budget for the emitted window
     * @return the 1-based capped end line index
     */
    private int cappedEnd(String[] lines, int start, int requestedEnd, int maxChars)
    {
        long total = 0;
        for (int i = start; i <= requestedEnd; i++) {
            total += (long) lines[i - 1].length() + 1;
            if (total > maxChars && i > start) {
                return i - 1;
            }
        }
        return requestedEnd;
    }

    private String buildHeader(String referenceBlock, String title, String syntaxId, String version,
        String sizeDescription)
    {
        return referenceBlock
            + "Title: " + (StringUtils.isNotBlank(title) ? title : "(untitled)") + NEW_LINE
            + "Syntax: " + syntaxId + NEW_LINE
            + "Version: " + version + NEW_LINE
            + "Size: " + sizeDescription;
    }

    /**
     * Builds the {@code Reference:} line plus an optional {@code URL:} line (when an absolute view URL is
     * available), each terminated by a newline, so the caller can prepend it to the rest of the header.
     *
     * @param doc the loaded document
     * @return the reference block, ending with a newline
     */
    private String buildReferenceBlock(DocumentModelBridge doc)
    {
        // Strip any newline/control chars from the serialized reference: an entity name can contain a newline,
        // which would otherwise break the single Reference: line into forged extra lines.
        String canonicalRef = MCPToolSupport.stripLineBreaks(this.serializer.serialize(doc.getDocumentReference()));
        String url = safeDocumentUrl(doc.getDocumentReference());
        String urlLine = StringUtils.isNotBlank(url) ? "URL: " + url + NEW_LINE : "";
        return "Reference: " + canonicalRef + NEW_LINE + urlLine;
    }

    private String safeDocumentUrl(DocumentReference docRef)
    {
        try {
            return this.documentAccessBridge.getDocumentURL(docRef, VIEW_ACTION, null, null, true);
        } catch (Exception e) {
            this.logger.debug(VIEW_URL_FAILURE, e);
            return null;
        }
    }

    private String numberedBody(String[] lines, int start, int end)
    {
        return MCPSourceText.numberedLines(lines, start, end);
    }

    private String buildOutline(String[] lines, int totalLines, String syntaxId)
    {
        if (!MCPSourceText.hasHeadingPattern(syntaxId)) {
            return "Outline unavailable for syntax " + syntaxId + READ_WITH_RANGE_HINT;
        }
        List<String> headings = MCPSourceText.collectHeadingLines(lines, totalLines, syntaxId);
        if (headings.isEmpty()) {
            return "No headings found" + READ_WITH_RANGE_HINT + sparseOutlineHint();
        }
        String outline = String.join(NEW_LINE, headings);
        if (headings.size() == 1) {
            outline += sparseOutlineHint();
        }
        return outline;
    }

    /**
     * Builds the hint line appended to a sparse (0- or 1-heading) source outline, steering to the rendered
     * outline. Empty when rendered content is disabled on this wiki (gated exactly like the provenance
     * advice), so the hint never steers into the rendered-disabled refusal.
     *
     * @return the hint preceded by a newline, or an empty string when rendered content is disabled
     */
    private String sparseOutlineHint()
    {
        if (!this.mcpConfig.isRenderedContentAllowed(this.contextProvider.get().getWikiId())) {
            return "";
        }
        return NEW_LINE + SPARSE_OUTLINE_HINT;
    }

    private String syntaxIdOf(Syntax syntax)
    {
        return syntax != null ? syntax.toIdString() : UNKNOWN_SYNTAX;
    }

    /**
     * The rendered title and content of a document, produced together in rendered mode so the header title is
     * consistent with the executed body.
     *
     * @param title the rendered plain-text title, may be {@code null}
     * @param content the rendered plain-text content
     * @version $Id$
     */
    private record RenderedDocument(String title, String content)
    {
    }
}
