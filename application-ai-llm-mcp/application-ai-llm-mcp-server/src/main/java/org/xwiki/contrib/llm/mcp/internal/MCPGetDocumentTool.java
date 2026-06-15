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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
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
 * <p>Authorization is enforced with {@link ContextualAuthorizationManager#hasAccess(Right,
 * org.xwiki.model.reference.EntityReference)} for {@link Right#VIEW} before the document is loaded,
 * so the existence of a protected document is never leaked.</p>
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
     * Rough characters-per-token heuristic used only to surface an approximate token count to the agent.
     */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * Approximate token budget for a single response, quoted in agent-facing text; the enforced limit
     * is its character equivalent {@link #MAX_OUTPUT_CHARS}.
     */
    private static final int MAX_OUTPUT_TOKENS = 6000;

    /**
     * Cap on the source emitted in a single response. Documents at most this size are returned in full
     * automatically; above it the tool degrades to a heading outline and explicit offset/limit reads,
     * whose output is capped to the same budget. One larger read is cheaper for an agent than several
     * round trips, but an unbounded one could still flood the context window.
     */
    private static final int MAX_OUTPUT_CHARS = CHARS_PER_TOKEN * MAX_OUTPUT_TOKENS;

    private static final String REFERENCE_PARAM = "reference";

    private static final String OFFSET_PARAM = "offset";

    private static final String LIMIT_PARAM = "limit";

    private static final String OUTLINE_PARAM = "outline";

    private static final String RENDERED_PARAM = "rendered";

    private static final String FORMAT_PARAM = "format";

    private static final String PLAIN_FORMAT = "plain";

    private static final String HTML_FORMAT = "html";

    /**
     * All accepted {@code format} values in display order, used for validation and the invalid-value
     * error message.
     */
    private static final List<String> FORMAT_VALUES = List.of(PLAIN_FORMAT, HTML_FORMAT);

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String MUST_BE_AT_LEAST_ONE = "' must be >= 1.";

    private static final String UNKNOWN_SYNTAX = "unknown";

    private static final String XWIKI_SYNTAX_PREFIX = "xwiki/";

    private static final String MARKDOWN_SYNTAX_PREFIX = "markdown";

    private static final String READ_WITH_RANGE_HINT = "; read with offset/limit.";

    private static final String COULD_NOT_READ_PREFIX = "Could not read the document ";

    private static final String SHOWING_LINES_PREFIX = "Showing lines 1-";

    private static final String OF_INFIX = " of ";

    private static final String VIEW_ACTION = "view";

    private static final String VIEW_URL_FAILURE = "MCP get_document tool could not build the view URL";

    /**
     * Warning shown when a large document degrades to a heading outline instead of returning its content.
     */
    private static final String LARGE_DOC_OUTLINE_WARNING =
        "This is an OUTLINE (a map of headings) of a large document, NOT its content. "
            + "Read a section with offset/limit to see the source.";

    /**
     * Shared tail of the rendered-mode banners: the read-only warning that the executed view must not be
     * used to form {@code edit_document} match strings.
     */
    private static final String RENDERED_BANNER_TAIL =
        " READ-ONLY: do NOT copy text from here into edit_document; re-read "
            + "with rendered=false to get the editable source.";

    /**
     * Banner prepended to plain-text rendered output, warning that it is the executed view (not source).
     */
    private static final String RENDERED_BANNER =
        "RENDERED VIEW - plain text, macros executed and includes expanded (structure such as tables and "
            + "link targets is flattened)." + RENDERED_BANNER_TAIL;

    /**
     * Banner prepended to HTML rendered output ({@code format="html"}), warning that it is the executed
     * view (not source).
     */
    private static final String RENDERED_HTML_BANNER =
        "RENDERED VIEW - cleaned HTML, macros executed and includes expanded (structure such as tables, "
            + "links and message boxes is preserved)." + RENDERED_BANNER_TAIL;

    private static final String OFFSET_EQUALS = OFFSET_PARAM + "=";

    private static final String CONTINUATION_PREFIX = " Output truncated at the ~" + MAX_OUTPUT_TOKENS
        + "-token cap; continue with " + OFFSET_EQUALS;

    /**
     * Heading pattern for XWiki syntaxes: leading {@code =} run sets the level, optional trailing
     * {@code =} run is stripped.
     */
    private static final Pattern XWIKI_HEADING = Pattern.compile("^(={1,6})\\s+(.+?)\\s*=*\\s*$");

    /**
     * Heading pattern for Markdown syntaxes: leading {@code #} run sets the level, optional trailing
     * {@code #} run is stripped.
     */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /**
     * Matches XWiki group/style markers such as {@code (% class="x" %)} and {@code (%%)}, so they can be
     * stripped from an outline title. Non-greedy and bounded by {@code (%}/{@code %)}, so no catastrophic
     * backtracking.
     */
    private static final Pattern XWIKI_STYLE_MARKER = Pattern.compile("\\(%.*?%\\)");

    /**
     * Matches an XWiki link {@code [[ ... ]]} so its label (or target, when unlabelled) can replace the raw
     * markup in an outline title. Non-greedy and bounded by {@code [[}/{@code ]]}.
     */
    private static final Pattern XWIKI_LINK = Pattern.compile("\\[\\[(.*?)\\]\\]");

    /**
     * Matches a run of two or more whitespace characters, collapsed to a single space after markup stripping.
     */
    private static final Pattern MULTIPLE_SPACES = Pattern.compile("\\s{2,}");

    private static final String LINK_LABEL_SEPARATOR = ">>";

    private static final String LINK_PARAM_SEPARATOR = "||";

    /**
     * Minimum number of consecutive JVM stack-frame lines a run must reach before it is collapsed to a
     * single marker. Below it, the lines pass through verbatim, so an incidental one- or two-line
     * {@code at ...} mention in prose is never touched.
     */
    private static final int STACK_FRAME_COLLAPSE_THRESHOLD = 3;

    /**
     * Marker substituted for a collapsed run of stack frames, parameterised by the run length.
     */
    private static final String STACK_FRAMES_OMITTED_MARKER = "[... %d stack frames omitted ...]";

    /**
     * Matches a JVM stack-frame line ({@code at fully.qualified.Method(Source.java:NN)}) or a frame
     * continuation line ({@code ... N more} / {@code ... N common frames omitted}), anchored on the
     * whole line so ordinary prose containing the word "at" is not matched.
     */
    private static final Pattern STACK_FRAME_LINE =
        Pattern.compile("^\\s*at\\s+\\S.*\\(.*\\)\\s*$|^\\s*\\.\\.\\.\\s+\\d+\\s+(more|common frames omitted)\\s*$");

    /**
     * The declared parameters: one source for both the advertised input schema and the typed
     * argument accessors.
     */
    private static final MCPToolSupport PARAMS = MCPToolSupport.builder()
        .requiredString(REFERENCE_PARAM, "The document reference to read, e.g. \"Help.GettingStarted\" or "
            + "\"xwiki:Sandbox.WebHome\".")
        .integer(OFFSET_PARAM, "1-based line number to start reading from. Use with limit to read a slice "
            + "of a large document.")
        .integer(LIMIT_PARAM, "Number of lines to read from offset. Omit (with offset=1) to read the whole "
            + "document regardless of size.")
        .bool(OUTLINE_PARAM, "If true, return the document's heading outline (a map with line numbers) "
            + "instead of its content. Default false.")
        .bool(RENDERED_PARAM, "If true, return the page RENDERED to plain text (macros executed, includes "
            + "expanded) - useful for script-driven pages whose source is opaque. Plain text, so "
            + "structure (tables, link targets) is flattened. Read-only: do NOT form edit strings "
            + "from it. Default false (raw editable source).")
        .string(FORMAT_PARAM, "Output format for rendered=true: \"plain\" (default) or \"html\". HTML "
            + "preserves structure (tables, links, message boxes) at a higher token cost.")
        .build();

    @Inject
    private Logger logger;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private ContextualAuthorizationManager authorization;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private HTMLCleaner htmlCleaner;

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.inputSchema())
            .description("Read an XWiki document's raw source content. Returns the full source if it fits "
                + "the ~" + MAX_OUTPUT_TOKENS + "-token output budget; if larger, returns a heading OUTLINE "
                + "(a map, not the content) - then read a section with offset/limit. Output is line-numbered "
                + "(like cat -n); when forming edit strings later, do "
                + "NOT include the line-number prefix. Pass offset=1 with no limit to force the full document. "
                + "Set rendered=true to read a script-driven page as executed plain text (read-only); add "
                + "format=\"html\" to keep structure as cleaned HTML.")
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
            EXAMPLES
                Full read:  reference="Help.GettingStarted"
                A range:    reference="Help.GettingStarted", offset=80, limit=40
                Outline:    reference="Help.GettingStarted", outline=true
                Rendered:   reference="XWiki.XWikiSyntax", rendered=true  (macros executed; read-only)
                Rendered HTML:  reference="XWiki.XWikiSyntax", rendered=true, format="html"
                            (keeps tables, links and message boxes as cleaned HTML; costs more tokens
                            than the plain default - use it for structure-heavy pages)
                Large doc:  first read with reference only (you get an OUTLINE map of headings),
                            then read a section with reference + offset + limit.

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
            String reference = PARAMS.requireString(args, REFERENCE_PARAM);

            DocumentReference ref = this.referenceResolver.resolve(reference);
            if (!this.authorization.hasAccess(Right.VIEW, ref)) {
                return MCPToolSupport.errorResult("Not authorized to view " + QUOTE + reference + QUOTE + PERIOD);
            }

            if (!documentExists(ref, reference)) {
                return MCPToolSupport.errorResult("No such document: " + QUOTE + reference + QUOTE + PERIOD);
            }

            DocumentModelBridge doc = loadDocument(ref, reference);
            Integer offset = PARAMS.integer(args, OFFSET_PARAM);
            Integer limit = PARAMS.integer(args, LIMIT_PARAM);
            boolean outline = PARAMS.bool(args, OUTLINE_PARAM);
            Syntax renderedSyntax =
                resolveRenderedSyntax(PARAMS.string(args, FORMAT_PARAM), PARAMS.bool(args, RENDERED_PARAM));

            String title;
            String content;
            if (renderedSyntax != null) {
                RenderedDocument renderedDoc = renderDocument(ref, reference, renderedSyntax);
                title = renderedDoc.title();
                content = MCPSourceText.normalizeLineEndings(renderedDoc.content());
                // Plain-mode only: the HTML path is cleaned structurally by MCPRenderedHtml (its description
                // block, holding the stack trace, is already removed), and it has <pre>/structure we must not
                // disturb. Plain renders the trace as undifferentiated text, so collapse frame runs here.
                if (Syntax.PLAIN_1_0.equals(renderedSyntax)) {
                    content = collapseStackTraces(content);
                }
            } else {
                title = doc.getTitle();
                content = MCPSourceText.normalizeLineEndings(doc.getContent());
            }
            return render(doc, title, content, renderedSyntax, offset, limit, outline);
        } catch (IllegalArgumentException | DocumentLoadException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }
    }

    private boolean documentExists(DocumentReference ref, String reference) throws DocumentLoadException
    {
        try {
            return this.documentAccessBridge.exists(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_document tool failed to check existence of [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_document tool existence-check failure details", e);
            throw new DocumentLoadException(COULD_NOT_READ_PREFIX + QUOTE + reference + QUOTE + PERIOD);
        }
    }

    private DocumentModelBridge loadDocument(DocumentReference ref, String reference) throws DocumentLoadException
    {
        try {
            return this.documentAccessBridge.getDocumentInstance(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_document tool failed to load [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_document tool load failure details", e);
            throw new DocumentLoadException(COULD_NOT_READ_PREFIX + QUOTE + reference + QUOTE + PERIOD);
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
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + FORMAT_PARAM + "' must be one of: "
                + String.join(", ", FORMAT_VALUES) + PERIOD);
        }
        return HTML_FORMAT.equals(normalizedFormat) ? Syntax.HTML_5_0 : Syntax.PLAIN_1_0;
    }

    /**
     * Renders the document to the target output syntax the way a normal page view does: macros are executed
     * and includes expanded with the document content author's rights (via the {@code sdoc} set inside
     * {@link XWikiDocument#getRenderedContent}), not the current user's. This mirrors the platform's REST
     * rendered-content path ({@code ModelFactory}); the current user still only needs VIEW (already checked).
     *
     * <p>An output syntax (not a wiki syntax) is the target on purpose: rendering to a wiki syntax such as
     * {@code xwiki/2.1} round-trips macros back into their {@code {{...}}} source calls (they are not
     * executed), defeating the point. An output syntax like {@code plain/1.0} or {@code html/5.0} emits the
     * executed result; an HTML result is additionally stripped by {@link MCPRenderedHtml} for token
     * economy. The title is always rendered to plain text: a marked-up title buys nothing in the header.</p>
     *
     * @param ref the resolved document reference
     * @param reference the original reference string, for error messages
     * @param targetSyntax the output syntax to render the content to
     * @return the rendered title and content
     * @throws DocumentLoadException if the document cannot be loaded or rendered
     */
    private RenderedDocument renderDocument(DocumentReference ref, String reference, Syntax targetSyntax)
        throws DocumentLoadException
    {
        XWikiContext xcontext = this.contextProvider.get();
        XWikiDocument previousContextDocument = xcontext.getDoc();
        try {
            XWikiDocument xdoc = xcontext.getWiki().getDocument(ref, xcontext);
            xcontext.setDoc(xdoc);
            // Render the title too (it may itself be a macro/translation), so the header is consistent with the
            // executed body instead of showing the raw title source.
            String renderedTitle = StringUtils.trim(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext));
            String renderedContent = xdoc.getRenderedContent(targetSyntax, xcontext);
            if (Syntax.HTML_5_0.equals(targetSyntax)) {
                renderedContent = MCPRenderedHtml.strip(this.htmlCleaner, renderedContent);
            }
            return new RenderedDocument(renderedTitle, renderedContent);
        } catch (Exception e) {
            this.logger.warn("MCP get_document tool failed to render [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_document tool render failure details", e);
            throw new DocumentLoadException(COULD_NOT_READ_PREFIX + QUOTE + reference + QUOTE + PERIOD);
        } finally {
            xcontext.setDoc(previousContextDocument);
        }
    }

    private McpSchema.CallToolResult render(DocumentModelBridge doc, String title, String content,
        Syntax renderedSyntax, Integer offset, Integer limit, boolean outline)
    {
        String version = doc.getVersion();
        boolean rendered = renderedSyntax != null;
        // In rendered mode the content is the executed output (plain text or cleaned HTML) regardless of the
        // document's source syntax, so the header and the heading-scan (outline) reflect that rendered syntax.
        String syntaxId = rendered ? renderedSyntax.toIdString() : syntaxIdOf(doc.getSyntax());
        String referenceBlock = buildReferenceBlock(doc);

        boolean empty = content.isEmpty();
        String[] lines = content.split(NEW_LINE, -1);
        int totalLines = empty ? 0 : lines.length;
        int totalChars = content.length();
        int approxTokens = totalChars / CHARS_PER_TOKEN;

        String header = prependBanner(
            buildHeader(referenceBlock, title, syntaxId, version, totalLines, totalChars, approxTokens),
            renderedSyntax);

        if (outline) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + buildOutline(lines, totalLines, syntaxId));
        }
        if (offset != null || limit != null) {
            return renderRange(header, lines, totalLines, offset, limit);
        }
        if (empty) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + "Document has no content.");
        }
        if (totalChars <= MAX_OUTPUT_CHARS) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + numberedBody(lines, 1, totalLines));
        }
        return MCPToolSupport.result(header + DOUBLE_NEW_LINE + renderLargeAutoDegrade(lines, totalLines, syntaxId));
    }

    /**
     * Prepends the rendered-mode banner matching the rendered output format, or returns the header
     * unchanged in source mode.
     *
     * @param header the built header
     * @param renderedSyntax the rendered output syntax, or {@code null} in source mode
     * @return the header, with the banner prepended in rendered mode
     */
    private String prependBanner(String header, Syntax renderedSyntax)
    {
        if (renderedSyntax == null) {
            return header;
        }
        String banner = Syntax.HTML_5_0.equals(renderedSyntax) ? RENDERED_HTML_BANNER : RENDERED_BANNER;
        return banner + NEW_LINE + header;
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
        List<String> headings = collectHeadingLines(lines, totalLines, syntaxId);
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
                "offset " + start + " exceeds document length (" + totalLines + " lines).");
        }
        long endLong = (limit != null) ? Math.min((long) start + limit - 1, totalLines) : totalLines;
        int end = (int) endLong;
        int actualEnd = cappedEnd(lines, start, end, MAX_OUTPUT_CHARS);
        boolean truncated = actualEnd < end;
        String body = numberedBody(lines, start, actualEnd);
        String footer = "Showing lines " + start + "-" + actualEnd + OF_INFIX + totalLines + PERIOD;
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
        int totalLines, int totalChars, int approxTokens)
    {
        return referenceBlock
            + "Title: " + (StringUtils.isNotBlank(title) ? title : "(untitled)") + NEW_LINE
            + "Syntax: " + syntaxId + NEW_LINE
            + "Version: " + version + NEW_LINE
            + "Size: " + totalLines + " lines · " + totalChars + " chars · ~" + approxTokens + " tokens";
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
        String canonicalRef = this.serializer.serialize(doc.getDocumentReference());
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

    /**
     * Collapses runs of consecutive JVM stack-frame lines in rendered plain output for token economy. A
     * maximal run of {@value #STACK_FRAME_COLLAPSE_THRESHOLD} or more frame lines (a frame being
     * {@code at type.method(Source:NN)} or a {@code ... N more} continuation, matched anchored on the
     * line so prose is untouched) is replaced by a single {@code [... N stack frames omitted ...]} marker;
     * shorter runs and every non-frame line pass through unchanged, so the exception header, any
     * {@code Caused by:} chain and the human message are preserved.
     *
     * <p>Plain-mode only: the HTML rendered path is cleaned structurally by {@link MCPRenderedHtml}.</p>
     *
     * @param text the LF-normalized rendered plain content
     * @return the content with stack-frame runs collapsed
     */
    private static String collapseStackTraces(String text)
    {
        String[] lines = text.split(NEW_LINE, -1);
        List<String> out = new ArrayList<>();
        List<String> run = new ArrayList<>();
        for (String line : lines) {
            if (STACK_FRAME_LINE.matcher(line).matches()) {
                run.add(line);
            } else {
                flushFrameRun(out, run);
                out.add(line);
            }
        }
        flushFrameRun(out, run);
        return String.join(NEW_LINE, out);
    }

    /**
     * Flushes the accumulated frame-line run into {@code out}: a run of
     * {@value #STACK_FRAME_COLLAPSE_THRESHOLD} or more lines is replaced by a single marker, a shorter run
     * is re-emitted verbatim, and an empty run is a no-op. The run buffer is cleared either way.
     *
     * @param out the output line accumulator
     * @param run the buffered frame lines (mutated: cleared on return)
     */
    private static void flushFrameRun(List<String> out, List<String> run)
    {
        if (run.isEmpty()) {
            return;
        }
        if (run.size() >= STACK_FRAME_COLLAPSE_THRESHOLD) {
            out.add(String.format(STACK_FRAMES_OMITTED_MARKER, run.size()));
        } else {
            out.addAll(run);
        }
        run.clear();
    }

    private String buildOutline(String[] lines, int totalLines, String syntaxId)
    {
        if (headingPatternFor(syntaxId) == null) {
            return "Outline unavailable for syntax " + syntaxId + READ_WITH_RANGE_HINT;
        }
        List<String> headings = collectHeadingLines(lines, totalLines, syntaxId);
        if (headings.isEmpty()) {
            return "No headings found" + READ_WITH_RANGE_HINT;
        }
        return String.join(NEW_LINE, headings);
    }

    /**
     * Collects the formatted outline entries for a document. Returns an empty list when the syntax has no
     * heading pattern or when no line matches, so callers can branch on emptiness without sniffing a formatted
     * message.
     *
     * @param lines the document lines
     * @param totalLines the total number of lines
     * @param syntaxId the document syntax identifier
     * @return the formatted heading entries, or an empty list if none
     */
    private List<String> collectHeadingLines(String[] lines, int totalLines, String syntaxId)
    {
        List<String> headings = new ArrayList<>();
        Pattern pattern = headingPatternFor(syntaxId);
        if (pattern == null) {
            return headings;
        }
        for (int i = 1; i <= totalLines; i++) {
            appendHeading(headings, pattern, lines[i - 1], i);
        }
        return headings;
    }

    private void appendHeading(List<String> headings, Pattern pattern, String line, int lineNumber)
    {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.matches()) {
            return;
        }
        int level = matcher.group(1).length();
        String titleText = cleanHeadingTitle(matcher.group(2).trim());
        headings.add(" ".repeat(2 * (level - 1)) + "L" + lineNumber + ": " + titleText);
    }

    /**
     * Cleans noisy inline markup from an outline heading title using pure string operations (no parsing or
     * rendering). Strips XWiki group/style markers and collapses XWiki links to their label (or target, when
     * unlabelled). Falls back to the trimmed raw title if cleaning leaves the title empty, so an entry is never
     * blank.
     *
     * @param raw the extracted, already-trimmed heading title text
     * @return the cleaned title
     */
    private String cleanHeadingTitle(String raw)
    {
        String stripped = XWIKI_STYLE_MARKER.matcher(raw).replaceAll("");

        Matcher linkMatcher = XWIKI_LINK.matcher(stripped);
        StringBuilder sb = new StringBuilder();
        while (linkMatcher.find()) {
            String inner = linkMatcher.group(1);
            String label = inner.contains(LINK_LABEL_SEPARATOR)
                ? inner.substring(0, inner.indexOf(LINK_LABEL_SEPARATOR)) : inner;
            if (label.contains(LINK_PARAM_SEPARATOR)) {
                label = label.substring(0, label.indexOf(LINK_PARAM_SEPARATOR));
            }
            linkMatcher.appendReplacement(sb, Matcher.quoteReplacement(label.trim()));
        }
        linkMatcher.appendTail(sb);

        String cleaned = MULTIPLE_SPACES.matcher(sb.toString()).replaceAll(" ").trim();
        return cleaned.isEmpty() ? raw.trim() : cleaned;
    }

    private Pattern headingPatternFor(String syntaxId)
    {
        if (syntaxId.startsWith(XWIKI_SYNTAX_PREFIX)) {
            return XWIKI_HEADING;
        }
        if (syntaxId.startsWith(MARKDOWN_SYNTAX_PREFIX)) {
            return MARKDOWN_HEADING;
        }
        return null;
    }

    private String syntaxIdOf(Syntax syntax)
    {
        return syntax != null ? syntax.toIdString() : UNKNOWN_SYNTAX;
    }

    /**
     * Internal carrier for a document-load failure whose message is already agent-facing, so that
     * {@link #execute(McpSchema.CallToolRequest)} can convert it to an error result uniformly.
     *
     * @version $Id$
     */
    private static final class DocumentLoadException extends Exception
    {
        DocumentLoadException(String message)
        {
            super(message);
        }
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
