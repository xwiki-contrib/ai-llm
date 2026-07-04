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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.ranges.DocumentRange;
import org.w3c.dom.ranges.Range;
import org.xwiki.xml.html.HTMLCleaner;
import org.xwiki.xml.html.HTMLCleanerConfiguration;
import org.xwiki.xml.html.HTMLUtils;

/**
 * Parse-once wrapper around a cleaned rendered-HTML document, for the {@code get_document} tool's
 * {@code rendered=true, format="html"} mode. {@link #parse(HTMLCleaner, String)} strips the rendered
 * HTML down to what carries meaning for an LLM reader and walks the resulting DOM once, in document
 * order, to index its heading sections; the instance then answers outline queries ({@link #outline()},
 * {@link #sectionOutline(String)}) and chunk queries ({@link #chunkParent(String)},
 * {@link #chunkMapEntries(String, int)}) and serializes either the whole document
 * ({@link #serialize()}), one heading-addressed section ({@link #sectionHtml(String)}) or one chunk
 * of a partitioned section ({@link #chunkHtml(String, int)}).
 *
 * <p>Serialization mutates the retained DOM, so {@link #serialize()}, {@link #sectionHtml(String)}
 * and {@link #chunkHtml(String, int)} are terminal: at most one of them may be called, once, per
 * instance.</p>
 *
 * <p>The allowlist rationale: attributes and class tokens that carry comprehension signal for an agent
 * survive (link and image targets, captions, table geometry, heading anchors, message-box semantics,
 * broken-link and rendering-error markers); everything else is presentation scaffolding and is dropped
 * for token economy. Scripts, styles and comments are removed entirely. The macro rendering-error
 * <em>message</em> block ({@code xwikirenderingerror}) is kept as a useful signal, but its sibling
 * <em>description</em> block ({@code xwikirenderingerrordescription}) is removed with its subtree: it
 * carries the full Java stack trace, which is token spam with no comprehension value for an agent.</p>
 *
 * <p>The full markup detail ({@link #parse(HTMLCleaner, String, boolean)} with {@code fullDetail =
 * true}) keeps every attribute instead of the allowlist - shortening values longer than
 * {@value #MAX_FULL_ATTRIBUTE_CHARS} characters at parse time, so the retained DOM, the character
 * estimator and the serialization all agree - for an agent verifying markup attributes it wrote.
 * Scripts, styles, comments and the error-description subtree are still removed: they carry no
 * comprehension value in either detail. Everything downstream of the clean walk (section indexing,
 * outlines, chunk partitioning, serialization) is detail-agnostic; its results reflect whichever
 * detail the DOM was cleaned in, so sizes and chunk boundaries differ between the two details.</p>
 *
 * @version $Id$
 * @since 0.9
 */
final class MCPRenderedHtml
{
    /**
     * Longest attribute value kept whole in the full markup detail; longer values are cut at this
     * length (backing off one character rather than splitting a surrogate pair) and end with
     * {@link #SHORTENED_MARKER}. Shared with the calling tool's full-detail banner so the advertised
     * threshold and the applied one cannot drift.
     */
    static final int MAX_FULL_ATTRIBUTE_CHARS = 500;

    /**
     * Marker ending an attribute value shortened at {@link #MAX_FULL_ATTRIBUTE_CHARS} in the full
     * markup detail.
     */
    static final String SHORTENED_MARKER = "[...shortened]";

    /**
     * Attributes kept on every element: they carry content semantics (link and image targets, captions,
     * table geometry) rather than presentation.
     */
    private static final Set<String> KEPT_ATTRIBUTES =
        Set.of("href", "src", "alt", "title", "colspan", "rowspan", "scope");

    /**
     * The class token marking the macro rendering-error <em>message</em> block, kept as a comprehension
     * signal and counted in the outline's per-section statistics.
     */
    private static final String RENDERING_ERROR_TOKEN = "xwikirenderingerror";

    /**
     * Class tokens kept because they carry comprehension signal: message-box semantics, the broken-link
     * marker, the rendering-error message marker and the Live Data placeholder marker (Live Data is
     * rendered client-side, so the server-rendered page holds only an empty placeholder div - the
     * {@code liveData} token turns that bare empty div into a legible "dynamic content, populated in
     * the browser, not visible to this tool" signal). All other tokens (e.g. {@code wikigeneratedid},
     * {@code wikilink}, {@code wikiexternallink}, {@code wikimodel-*}) are presentation scaffolding and
     * are dropped; the {@code class} attribute itself is removed when no token survives.
     */
    private static final Set<String> KEPT_CLASS_TOKENS = Set.of("box", "infomessage", "warningmessage",
        "errormessage", "successmessage", "wikicreatelink", "liveData", RENDERING_ERROR_TOKEN);

    /**
     * The class token marking the macro rendering-error <em>description</em> block, which carries the full
     * Java stack trace. Any element bearing this token is removed with its entire subtree.
     */
    private static final String RENDERING_ERROR_DESCRIPTION_TOKEN = "xwikirenderingerrordescription";

    /**
     * Elements removed entirely (with their payload): they are executable or presentational and carry no
     * comprehension value.
     */
    private static final Set<String> DROPPED_ELEMENTS = Set.of("script", "style");

    /**
     * The heading elements, the only ones that keep their {@code id}: it is the live section anchor.
     */
    private static final Set<String> HEADING_ELEMENTS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    /**
     * The anchor of the pseudo-section covering the content before the first heading.
     */
    private static final String INTRO_ANCHOR = "(intro)";

    /**
     * Rough characters-per-token heuristic used for the outline's per-section size estimates, shared
     * with the tool's header sizes so both use the same scale.
     */
    private static final int CHARS_PER_TOKEN = MCPSourceText.CHARS_PER_TOKEN;

    /**
     * Target estimated character size of one chunk (~4000 tokens). Deliberately smaller than the
     * ~6000-token output cap ({@link MCPSourceText#MAX_OUTPUT_CHARS}), so a fetched chunk plus its
     * response header and footer always fits the budget and leaves the agent reasoning room.
     */
    private static final int CHUNK_TARGET_CHARS = 4000 * CHARS_PER_TOKEN;

    /**
     * Number of leading words of a chunk's text content used as its map-entry title.
     */
    private static final int CHUNK_TITLE_WORDS = 8;

    /**
     * Longest ordinal accepted in a chunk anchor, guarding the digit parse against integer overflow.
     */
    private static final int MAX_ORDINAL_DIGITS = 9;

    /**
     * The ordinal prefix distinguishing a chunk-map page anchor ({@code (parent/map2)}) from a chunk
     * anchor ({@code (parent/2)}).
     */
    private static final String MAP_ORDINAL_PREFIX = "map";

    /**
     * The bare spelling of the intro pseudo-anchor, accepted as a chunk-anchor parent for the whole
     * body of a headingless document.
     */
    private static final String BARE_INTRO = "intro";

    private static final String ID_ATTRIBUTE = "id";

    private static final String CLASS_ATTRIBUTE = "class";

    private static final String BODY_TAG = "body";

    private static final String TABLE_TAG = "table";

    private static final String TR_TAG = "tr";

    private static final String TH_TAG = "th";

    private static final String THEAD_TAG = "thead";

    /**
     * The table row-group elements whose direct {@code tr} children are rows of the enclosing table
     * (as opposed to rows of a nested table).
     */
    private static final Set<String> ROW_GROUP_TAGS = Set.of(THEAD_TAG, "tbody", "tfoot");

    private static final String HASH = "#";

    private static final String NEW_LINE = "\n";

    private static final String SPACE = " ";

    private static final String SLASH = "/";

    private static final String OPEN_PAREN = "(";

    private static final String CLOSE_PAREN = ")";

    private static final String QUOTE = "\"";

    private static final String ELLIPSIS = "...";

    /**
     * Separates an anchor from its title in outline lines and chunk-map entries.
     */
    private static final String TITLE_SEPARATOR = ": ";

    /**
     * Opens the trailing statistics parenthesis of outline lines and chunk-map entries; closed by
     * {@link #CLOSE_PAREN}.
     */
    private static final String STATS_OPEN = "  (";

    /**
     * Two-space unit of outline indentation per heading level.
     */
    private static final String OUTLINE_INDENT = "  ";

    /**
     * Splits a {@code class} attribute value into its whitespace-delimited tokens.
     */
    private static final String WHITESPACE_SPLIT = "\\s+";

    /**
     * Matches a whitespace run, collapsed to a single space in outline titles.
     */
    private static final Pattern WHITESPACE_RUN = Pattern.compile(WHITESPACE_SPLIT);

    /**
     * The cleaned document; mutated (and thus consumed) by the terminal serialization calls.
     */
    private final Document document;

    /**
     * The indexed sections in document order; index 0 may be the intro pseudo-section.
     */
    private final List<SectionInfo> sections;

    /**
     * Estimated serialized character count of the whole cleaned body.
     */
    private final int approxChars;

    /**
     * Lazily computed chunk partitions, keyed by canonical parent anchor. A tool request touches a
     * single parent, so the cache typically holds one entry; it exists so the count, map and fetch
     * queries of one request share the partition.
     */
    private final Map<String, List<Chunk>> chunkPartitions = new HashMap<>();

    /**
     * Lazily computed chunk-map pages (the formatted entry lines, split at the output budget), keyed
     * by canonical parent anchor.
     */
    private final Map<String, List<List<String>>> chunkMapPages = new HashMap<>();

    /**
     * Whether a terminal call ({@link #serialize()}, {@link #sectionHtml(String)} or
     * {@link #chunkHtml(String, int)}) already consumed the retained document.
     */
    private boolean serialized;

    private MCPRenderedHtml(Document document)
    {
        this.document = document;
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(null, 0));
        Node body = document.getElementsByTagName(BODY_TAG).item(0);
        if (body != null) {
            walk(body, segments);
        }
        int total = 0;
        for (Segment segment : segments) {
            total += segment.chars;
        }
        this.approxChars = total;
        this.sections = buildSections(segments);
    }

    /**
     * Cleans rendered HTML in the default stripped detail: shorthand for
     * {@link #parse(HTMLCleaner, String, boolean)} with {@code fullDetail = false}.
     *
     * @param cleaner the HTML cleaner component, supplied by the calling tool
     * @param html the rendered HTML
     * @return the parsed wrapper, ready for outline queries and one terminal serialization
     */
    static MCPRenderedHtml parse(HTMLCleaner cleaner, String html)
    {
        return parse(cleaner, html, false);
    }

    /**
     * Cleans rendered HTML for agent output: parses it as HTML5 with the given cleaner (which guarantees
     * a well-formed DOM even for raw {@code {{html}}} passthrough), removes scripts, styles, comments and
     * rendering-error description blocks, applies the requested attribute detail, and indexes the heading
     * sections of the cleaned DOM in one document-order walk. The stripped detail removes every
     * non-allowlisted attribute; the full detail keeps every attribute, shortening values longer than
     * {@value #MAX_FULL_ATTRIBUTE_CHARS} characters.
     *
     * @param cleaner the HTML cleaner component, supplied by the calling tool
     * @param html the rendered HTML
     * @param fullDetail whether to keep all element attributes instead of the stripped allowlist
     * @return the parsed wrapper, ready for outline queries and one terminal serialization
     */
    static MCPRenderedHtml parse(HTMLCleaner cleaner, String html, boolean fullDetail)
    {
        HTMLCleanerConfiguration configuration = cleaner.getDefaultConfiguration();
        Map<String, String> parameters = new HashMap<>(configuration.getParameters());
        // Parse as HTML5, since the rendered content is html/5.0. Restricted mode is not used on purpose:
        // it converts script/style elements to pre elements, preserving their payload, whereas the walk
        // below removes them entirely.
        parameters.put(HTMLCleanerConfiguration.HTML_VERSION, "5");
        configuration.setParameters(parameters);

        Document document = cleaner.clean(new StringReader(html), configuration);
        clean(document, fullDetail);
        return new MCPRenderedHtml(document);
    }

    /**
     * Normalizes a caller-supplied section anchor: trims whitespace and strips one leading {@code #}.
     *
     * @param raw the raw anchor argument, possibly {@code null}
     * @return the normalized anchor, never {@code null}, possibly empty
     */
    static String normalizeAnchor(String raw)
    {
        String anchor = StringUtils.trimToEmpty(raw);
        if (anchor.startsWith(HASH)) {
            anchor = anchor.substring(1);
        }
        return anchor;
    }

    /**
     * @return whether the cleaned document contains at least one heading
     */
    boolean hasHeadings()
    {
        return this.sections.stream().anyMatch(section -> section.heading() != null);
    }

    /**
     * Tests whether a section with the given (already normalized) anchor exists, resolving by first
     * match in document order.
     *
     * @param anchor the normalized anchor
     * @return whether the anchor resolves to a section
     */
    boolean hasSection(String anchor)
    {
        return indexOf(anchor) >= 0;
    }

    /**
     * @return the estimated serialized character count of the whole cleaned body
     */
    int approxChars()
    {
        return this.approxChars;
    }

    /**
     * Returns the estimated serialized character count of one section (sub-sections included), or of
     * the whole body for the whole-body intro parent of a headingless document.
     *
     * @param anchor the resolved section or chunk-parent anchor
     * @return the estimated character count
     */
    int sectionApproxChars(String anchor)
    {
        int index = indexOf(anchor);
        if (index >= 0) {
            return this.sections.get(index).chars();
        }
        return this.approxChars;
    }

    /**
     * Builds the document's heading outline: one line per section, indented by heading level, with the
     * anchor, the heading text and the section's aggregate size statistics (sub-sections included).
     *
     * @return the outline, or an empty string when the document has no sections
     */
    String outline()
    {
        List<String> lines = new ArrayList<>();
        for (SectionInfo section : this.sections) {
            lines.add(outlineLine(section));
        }
        return String.join(NEW_LINE, lines);
    }

    /**
     * Builds the sub-outline of one section: its own outline entry plus the entries of its sub-headings,
     * with the same absolute indentation as {@link #outline()}.
     *
     * @param anchor the normalized anchor of the section, resolved by first match in document order
     * @return the sub-outline, or an empty string when the section has no sub-headings (always empty for
     *         the intro pseudo-section or an unknown anchor)
     */
    String sectionOutline(String anchor)
    {
        int index = indexOf(anchor);
        if (index < 0) {
            return "";
        }
        SectionInfo section = this.sections.get(index);
        if (section.heading() == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (int i = index; i < this.sections.size(); i++) {
            if (i > index && this.sections.get(i).level() <= section.level()) {
                break;
            }
            lines.add(outlineLine(this.sections.get(i)));
        }
        if (lines.size() == 1) {
            return "";
        }
        return String.join(NEW_LINE, lines);
    }

    /**
     * Parses a normalized anchor as a chunk reference: {@code (parent/K)} addresses chunk {@code K}
     * of the parent section and {@code (parent/mapP)} addresses page {@code P} of its chunk map. The
     * outer synthetic parentheses are optional and the split happens at the last {@code /}, so a
     * heading id containing {@code /} still resolves. The parent resolves like a section anchor, in
     * its full or bare spelling ({@code (h3)} or {@code h3}); the intro parent additionally resolves
     * to the whole body of a headingless document. Meant to be tried only after the whole anchor
     * failed to match a heading, so a heading whose id looks like a chunk reference wins.
     *
     * @param anchor the normalized anchor
     * @return the parsed request carrying the canonical parent anchor, or {@code null} when the
     *         anchor is not chunk-shaped, its ordinal is not a positive integer or its parent is
     *         unknown
     */
    private ChunkRequest parseChunkAnchor(String anchor)
    {
        String payload = anchor;
        if (payload.startsWith(OPEN_PAREN) && payload.endsWith(CLOSE_PAREN)) {
            payload = payload.substring(1, payload.length() - 1);
        }
        int slash = payload.lastIndexOf(SLASH);
        if (slash <= 0 || slash == payload.length() - 1) {
            return null;
        }
        String parent = resolveParentAnchor(payload.substring(0, slash));
        if (parent == null) {
            return null;
        }
        String ordinal = payload.substring(slash + 1);
        if (ordinal.startsWith(MAP_ORDINAL_PREFIX)) {
            int page = parsePositiveOrdinal(ordinal.substring(MAP_ORDINAL_PREFIX.length()));
            return page > 0 ? new ChunkRequest(parent, 0, page) : null;
        }
        int index = parsePositiveOrdinal(ordinal);
        return index > 0 ? new ChunkRequest(parent, index, 0) : null;
    }

    /**
     * Resolves the canonical parent anchor of a chunk-shaped anchor: the flat companion of
     * {@link #parseChunkAnchor(String)} for callers that avoid naming the parsed request type.
     *
     * @param anchor the normalized anchor
     * @return the canonical parent anchor, or {@code null} when the anchor is not a resolvable chunk
     *         reference
     */
    String chunkParent(String anchor)
    {
        ChunkRequest request = parseChunkAnchor(anchor);
        return request == null ? null : request.parentAnchor();
    }

    /**
     * @param anchor the normalized anchor
     * @return the 1-based chunk ordinal of a chunk-fetch anchor, or 0 when the anchor is not a
     *         resolvable chunk reference or addresses a map page
     */
    int chunkOrdinal(String anchor)
    {
        ChunkRequest request = parseChunkAnchor(anchor);
        return request == null ? 0 : request.chunkIndex();
    }

    /**
     * @param anchor the normalized anchor
     * @return the 1-based map page of a chunk-map anchor, or 0 when the anchor is not a resolvable
     *         chunk reference or addresses a chunk
     */
    int chunkMapPage(String anchor)
    {
        ChunkRequest request = parseChunkAnchor(anchor);
        return request == null ? 0 : request.mapPage();
    }

    /**
     * Formats the display form of a chunk anchor: {@code #(<parent>/<ordinal>)}.
     *
     * @param parentAnchor the canonical parent anchor
     * @param ordinal the 1-based chunk ordinal, or a placeholder such as {@code K}
     * @return the display anchor
     */
    static String chunkAnchorRef(String parentAnchor, String ordinal)
    {
        return HASH + OPEN_PAREN + parentAnchor + SLASH + ordinal + CLOSE_PAREN;
    }

    /**
     * Formats the display form of a chunk-map page anchor: {@code #(<parent>/map<page>)}.
     *
     * @param parentAnchor the canonical parent anchor
     * @param page the 1-based map page
     * @return the display anchor
     */
    static String mapAnchorRef(String parentAnchor, int page)
    {
        return chunkAnchorRef(parentAnchor, MAP_ORDINAL_PREFIX + page);
    }

    /**
     * @param parentAnchor the canonical parent anchor
     * @return the number of chunks the parent's content partitions into
     */
    int chunkCount(String parentAnchor)
    {
        return chunksFor(parentAnchor).size();
    }

    /**
     * Returns the column headers a chunk carries: the header-row {@code th} texts of the table the
     * partition descended into to produce it (see {@link #tableColumns(Element)}). A chunk produced
     * outside a table, or inside a table without a header row, carries none.
     *
     * @param parentAnchor the canonical parent anchor
     * @param index the 1-based chunk ordinal
     * @return the column header texts, empty when the chunk carries none or the ordinal is out of range
     */
    List<String> chunkColumns(String parentAnchor, int index)
    {
        List<Chunk> chunks = chunksFor(parentAnchor);
        if (index < 1 || index > chunks.size()) {
            return List.of();
        }
        return chunks.get(index - 1).columns();
    }

    /**
     * @param parentAnchor the canonical parent anchor
     * @return the number of pages of the parent's chunk map, at least 1
     */
    int chunkMapPageCount(String parentAnchor)
    {
        return pagesFor(parentAnchor).size();
    }

    /**
     * Returns the 1-based ordinal of the first chunk listed on the given map page, so a paginated
     * map can report which slice of the chunk list it shows.
     *
     * @param parentAnchor the canonical parent anchor
     * @param page the 1-based map page, assumed within range
     * @return the 1-based ordinal of the page's first chunk
     */
    int chunkMapPageStart(String parentAnchor, int page)
    {
        List<List<String>> pages = pagesFor(parentAnchor);
        int start = 1;
        for (int i = 0; i < page - 1 && i < pages.size(); i++) {
            start += pages.get(i).size();
        }
        return start;
    }

    /**
     * Returns the formatted chunk-map entry lines of one map page. Each entry carries the chunk's
     * display anchor, a title made of the first {@value #CHUNK_TITLE_WORDS} words of its text (or the
     * tag of its first element when it has no text) and its size statistics. Pages hold as many whole
     * entries as fit the {@link MCPSourceText#MAX_OUTPUT_CHARS} output budget.
     *
     * @param parentAnchor the canonical parent anchor
     * @param page the 1-based map page
     * @return the entry lines of the page, empty when the page is out of range
     */
    List<String> chunkMapEntries(String parentAnchor, int page)
    {
        List<List<String>> pages = pagesFor(parentAnchor);
        if (page < 1 || page > pages.size()) {
            return List.of();
        }
        return pages.get(page - 1);
    }

    /**
     * Serializes the whole cleaned document without the html/head/body envelope. Terminal: mutates the
     * retained document.
     *
     * @return the stripped HTML fragment
     * @throws IllegalStateException if a terminal call was already made on this instance
     */
    String serialize()
    {
        beginTerminal();
        return serializeDocument();
    }

    /**
     * Serializes one section: the heading element through the last node before the next heading of the
     * same or a higher level, in document order (a DOM Range walk, so headings nested at different div
     * depths are handled). Terminal: mutates the retained document.
     *
     * @param anchor the normalized anchor of the section, resolved by first match in document order
     * @return the stripped HTML fragment of the section, an empty string when the body is empty, or
     *         {@code null} when the runtime DOM implementation does not support ranges
     * @throws IllegalStateException if a terminal call was already made on this instance
     * @throws IllegalArgumentException if the anchor does not resolve to a section
     */
    String sectionHtml(String anchor)
    {
        beginTerminal();
        if (!(this.document instanceof DocumentRange documentRange)) {
            return null;
        }
        int index = indexOf(anchor);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown section anchor [" + anchor + "].");
        }
        Node body = this.document.getElementsByTagName(BODY_TAG).item(0);
        if (body == null || body.getFirstChild() == null) {
            return "";
        }
        return extractRange(body, buildSectionRange(documentRange, body, index));
    }

    /**
     * Serializes one chunk of a partitioned parent: the run of consecutive sibling subtrees the
     * partition assigned to the given ordinal, extracted with the same DOM Range recipe as a section.
     * Terminal: mutates the retained document.
     *
     * @param parentAnchor the canonical parent anchor
     * @param index the 1-based chunk ordinal
     * @return the stripped HTML fragment of the chunk, or {@code null} when the runtime DOM
     *         implementation does not support ranges
     * @throws IllegalStateException if a terminal call was already made on this instance
     * @throws IllegalArgumentException if the ordinal is out of the partition's range
     */
    String chunkHtml(String parentAnchor, int index)
    {
        beginTerminal();
        if (!(this.document instanceof DocumentRange documentRange)) {
            return null;
        }
        List<Chunk> chunks = chunksFor(parentAnchor);
        if (index < 1 || index > chunks.size()) {
            throw new IllegalArgumentException(
                "Chunk index [" + index + "] is out of range for section [" + parentAnchor + ']');
        }
        Chunk chunk = chunks.get(index - 1);
        Node body = this.document.getElementsByTagName(BODY_TAG).item(0);
        Range range = documentRange.createRange();
        range.setStartBefore(chunk.first());
        range.setEndAfter(chunk.last());
        return extractRange(body, range);
    }

    /**
     * Clones the range's contents, replaces the body's children with the clone and serializes the
     * document: the shared tail of the terminal section and chunk extractions.
     *
     * @param body the body element
     * @param range the range to extract
     * @return the stripped HTML fragment
     */
    private String extractRange(Node body, Range range)
    {
        DocumentFragment fragment = range.cloneContents();
        while (body.getFirstChild() != null) {
            body.removeChild(body.getFirstChild());
        }
        body.appendChild(fragment);
        return serializeDocument();
    }

    /**
     * Builds the DOM range spanning the section at the given index: from before its heading (or the first
     * body node for the intro) to before the next same-or-higher-level heading, or to the end of the body
     * when there is none.
     *
     * @param documentRange the range factory of the retained document
     * @param body the body element
     * @param index the section index
     * @return the section range
     */
    private Range buildSectionRange(DocumentRange documentRange, Node body, int index)
    {
        SectionInfo section = this.sections.get(index);
        Range range = documentRange.createRange();
        if (section.heading() == null) {
            range.setStartBefore(body.getFirstChild());
            range.setEndBefore(firstHeadingElement());
        } else {
            range.setStartBefore(section.heading());
            Element boundary = boundaryHeading(index);
            if (boundary != null) {
                range.setEndBefore(boundary);
            } else {
                range.setEndAfter(body.getLastChild());
            }
        }
        return range;
    }

    /**
     * Returns the first heading element of the document. Only called for the intro pseudo-section, which
     * exists only when the document has at least one heading.
     *
     * @return the first heading element
     */
    private Element firstHeadingElement()
    {
        for (SectionInfo section : this.sections) {
            if (section.heading() != null) {
                return section.heading();
            }
        }
        return null;
    }

    /**
     * Finds the heading that ends the section at the given index: the next heading, in document order,
     * with the same or a higher level.
     *
     * @param index the section index
     * @return the boundary heading element, or {@code null} when the section extends to the end
     */
    private Element boundaryHeading(int index)
    {
        SectionInfo section = this.sections.get(index);
        for (int i = index + 1; i < this.sections.size(); i++) {
            SectionInfo candidate = this.sections.get(i);
            if (candidate.heading() != null && candidate.level() <= section.level()) {
                return candidate.heading();
            }
        }
        return null;
    }

    /**
     * Resolves a chunk-anchor parent to its canonical section anchor: an exact section anchor, the
     * parenthesized form of a bare synthetic spelling ({@code h3} for {@code (h3)}), or the intro
     * pseudo-anchor for the whole body of a headingless document.
     *
     * @param parent the parent portion of a chunk anchor
     * @return the canonical parent anchor, or {@code null} when it resolves to nothing
     */
    private String resolveParentAnchor(String parent)
    {
        if (indexOf(parent) >= 0) {
            return parent;
        }
        String wrapped = OPEN_PAREN + parent + CLOSE_PAREN;
        if (indexOf(wrapped) >= 0) {
            return wrapped;
        }
        if (!hasHeadings() && (BARE_INTRO.equals(parent) || INTRO_ANCHOR.equals(parent))) {
            return INTRO_ANCHOR;
        }
        return null;
    }

    /**
     * Parses a chunk-anchor ordinal: a non-empty all-digit string of at most
     * {@value #MAX_ORDINAL_DIGITS} digits.
     *
     * @param value the ordinal portion of a chunk anchor
     * @return the parsed value, or 0 when malformed (0 itself is thereby rejected too: ordinals are
     *         1-based)
     */
    private static int parsePositiveOrdinal(String value)
    {
        if (value.isEmpty() || value.length() > MAX_ORDINAL_DIGITS
            || !value.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private List<Chunk> chunksFor(String parentAnchor)
    {
        return this.chunkPartitions.computeIfAbsent(parentAnchor, this::partitionParent);
    }

    /**
     * Partitions the parent's span into chunks: the span's maximal complete subtrees are packed
     * greedily into runs of consecutive same-parent siblings within the chunk target, descending into
     * a subtree that alone exceeds the target. Depends only on the cleaned DOM, so recomputation on a
     * fresh parse of the same content in the same detail yields the same partition (the two details
     * clean different DOMs, so their partitions differ).
     *
     * @param parentAnchor the canonical parent anchor
     * @return the chunks, in document order
     */
    private List<Chunk> partitionParent(String parentAnchor)
    {
        Node body = this.document.getElementsByTagName(BODY_TAG).item(0);
        if (body == null || body.getFirstChild() == null) {
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>();
        packRuns(spanSubtrees(body, parentAnchor), chunks, List.of());
        return chunks;
    }

    /**
     * Lists the maximal complete subtrees of the parent's span: a heading section spans its heading
     * (inclusive) to the next same-or-higher heading (exclusive), the intro section spans the body
     * start to the first heading, and the whole-body parent of a headingless document spans all of
     * the body.
     *
     * @param body the body element
     * @param parentAnchor the canonical parent anchor
     * @return the span's subtree roots, in document order
     */
    private List<Node> spanSubtrees(Node body, String parentAnchor)
    {
        int index = indexOf(parentAnchor);
        if (index < 0) {
            return maximalSubtrees(body, body.getFirstChild(), null);
        }
        SectionInfo section = this.sections.get(index);
        if (section.heading() == null) {
            return maximalSubtrees(body, body.getFirstChild(), firstHeadingElement());
        }
        return maximalSubtrees(body, section.heading(), boundaryHeading(index));
    }

    /**
     * Decomposes the span {@code [start, endBefore)} into its document-order sequence of maximal
     * complete subtrees: the start node, its following siblings, then the following siblings of each
     * ancestor up to the body, descending into any subtree that contains the boundary so the boundary
     * itself is excluded - the classic range decomposition.
     *
     * @param body the body element bounding the climb
     * @param start the first node of the span
     * @param endBefore the node ending the span (exclusive), or {@code null} for the end of the body
     * @return the subtree roots, in document order
     */
    private static List<Node> maximalSubtrees(Node body, Node start, Node endBefore)
    {
        List<Node> subtrees = new ArrayList<>();
        Node node = start;
        while (node != null && node != endBefore) {
            if (endBefore != null && containsNode(node, endBefore)) {
                node = node.getFirstChild();
            } else {
                subtrees.add(node);
                node = nextSubtree(node, body);
            }
        }
        return subtrees;
    }

    /**
     * Advances to the next subtree root in document order: the node's next sibling, or the next
     * sibling of the closest ancestor that has one, never climbing past the body.
     *
     * @param node the current subtree root
     * @param body the body element bounding the climb
     * @return the next subtree root, or {@code null} at the end of the body
     */
    private static Node nextSubtree(Node node, Node body)
    {
        Node current = node;
        while (current != null && current != body && current.getNextSibling() == null) {
            current = current.getParentNode();
        }
        if (current == null || current == body) {
            return null;
        }
        return current.getNextSibling();
    }

    private static boolean containsNode(Node ancestor, Node node)
    {
        for (Node current = node; current != null; current = current.getParentNode()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Packs the subtrees into chunk runs: consecutive same-parent siblings accumulate until the chunk
     * target, and a subtree alone over the target is descended into (or, when it has no element
     * children, becomes its own oversized chunk - the atomic floor, emitted capped at fetch time).
     *
     * @param subtrees the subtree roots, in document order
     * @param chunks the chunk accumulator
     * @param columns the column headers every emitted chunk carries (empty outside a table's subtree)
     */
    private static void packRuns(List<Node> subtrees, List<Chunk> chunks, List<String> columns)
    {
        Run run = new Run(columns);
        for (Node node : subtrees) {
            Segment stats = subtreeStats(node);
            if (stats.chars > CHUNK_TARGET_CHARS) {
                run.flush(chunks);
                descendOrFloor(node, stats, chunks, columns);
            } else {
                run.append(node, stats, chunks);
            }
        }
        run.flush(chunks);
    }

    /**
     * Handles a subtree that alone exceeds the chunk target: descends into its child nodes and packs
     * them the same way (staying inside the subtree), or - when it has no element children to descend
     * into - emits it as a single oversized chunk. Entering a {@code table} element extracts its
     * column headers once; they replace the inherited ones for everything inside the table's subtree,
     * so its row-run chunks carry the column semantics they would otherwise lose.
     *
     * @param node the oversized subtree root
     * @param stats the subtree's statistics
     * @param chunks the chunk accumulator
     * @param columns the inherited column headers (empty outside a table's subtree)
     */
    private static void descendOrFloor(Node node, Segment stats, List<Chunk> chunks, List<String> columns)
    {
        List<String> scopeColumns = columns;
        if (node.getNodeType() == Node.ELEMENT_NODE && TABLE_TAG.equals(tagName((Element) node))) {
            scopeColumns = tableColumns((Element) node);
        }
        if (!hasElementChildren(node)) {
            chunks.add(new Chunk(node, node, stats.chars, stats.tables, stats.images, stats.errors,
                scopeColumns));
            return;
        }
        List<Node> children = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            children.add(child);
        }
        packRuns(children, chunks, scopeColumns);
    }

    /**
     * Extracts a table's column headers: the {@code th} cell texts (whitespace-collapsed) of its
     * header row - the first of the table's own rows, a {@code thead} child's rows searched first,
     * whose cells include at least one {@code th}. A table with no such row yields no headers.
     *
     * @param table the table element
     * @return the column header texts, empty when the table has no header row
     */
    private static List<String> tableColumns(Element table)
    {
        for (Element row : tableRows(table)) {
            List<String> columns = headerCellTexts(row);
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return List.of();
    }

    /**
     * Lists the table's own rows in header-search order: the direct {@code tr} children of a
     * {@code thead} child first, then the remaining rows (direct {@code tr} children of the table or
     * of its other row groups) in document order. Rows of nested tables are not the table's own rows
     * and are excluded by construction.
     *
     * @param table the table element
     * @return the rows in header-search order
     */
    private static List<Element> tableRows(Element table)
    {
        List<Element> headRows = new ArrayList<>();
        List<Element> bodyRows = new ArrayList<>();
        for (Node child = table.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            String tag = tagName(element);
            if (TR_TAG.equals(tag)) {
                bodyRows.add(element);
            } else if (ROW_GROUP_TAGS.contains(tag)) {
                collectRows(element, THEAD_TAG.equals(tag) ? headRows : bodyRows);
            }
        }
        headRows.addAll(bodyRows);
        return headRows;
    }

    /**
     * Adds the row group's direct {@code tr} children to the given row list.
     *
     * @param rowGroup the {@code thead}, {@code tbody} or {@code tfoot} element
     * @param rows the row accumulator
     */
    private static void collectRows(Element rowGroup, List<Element> rows)
    {
        for (Node child = rowGroup.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && TR_TAG.equals(tagName((Element) child))) {
                rows.add((Element) child);
            }
        }
    }

    /**
     * Collects the trimmed, whitespace-collapsed texts of a row's {@code th} cells.
     *
     * @param row the row element
     * @return the header cell texts, empty when the row has no {@code th} cell
     */
    private static List<String> headerCellTexts(Element row)
    {
        List<String> columns = new ArrayList<>();
        for (Node child = row.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && TH_TAG.equals(tagName((Element) child))) {
                columns.add(WHITESPACE_RUN.matcher(child.getTextContent()).replaceAll(SPACE).trim());
            }
        }
        return columns;
    }

    private static boolean hasElementChildren(Node node)
    {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    private static Segment subtreeStats(Node node)
    {
        Segment stats = new Segment(null, 0);
        accumulateSubtree(node, stats);
        return stats;
    }

    /**
     * Accumulates a whole subtree's statistics, headings included as ordinary elements (unlike the
     * section walk, which opens a new segment at each heading).
     *
     * @param node the subtree root
     * @param stats the accumulator
     */
    private static void accumulateSubtree(Node node, Segment stats)
    {
        if (node.getNodeType() == Node.TEXT_NODE) {
            accumulateText(stats, node.getNodeValue());
            return;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return;
        }
        accumulateElement(stats, (Element) node);
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            accumulateSubtree(child, stats);
        }
    }

    private List<List<String>> pagesFor(String parentAnchor)
    {
        return this.chunkMapPages.computeIfAbsent(parentAnchor, this::buildMapPages);
    }

    /**
     * Formats all chunk-map entries of the parent and splits them into pages holding as many whole
     * entries as fit the output budget, so a map page can never overflow a response.
     *
     * @param parentAnchor the canonical parent anchor
     * @return the pages, each a list of entry lines; a single (possibly empty) page at minimum
     */
    private List<List<String>> buildMapPages(String parentAnchor)
    {
        List<Chunk> chunks = chunksFor(parentAnchor);
        List<List<String>> pages = new ArrayList<>();
        List<String> page = new ArrayList<>();
        int pageChars = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String entry = mapEntry(parentAnchor, i + 1, chunks.get(i));
            if (!page.isEmpty() && pageChars + entry.length() + 1 > MCPSourceText.MAX_OUTPUT_CHARS) {
                pages.add(page);
                page = new ArrayList<>();
                pageChars = 0;
            }
            page.add(entry);
            pageChars += entry.length() + 1;
        }
        pages.add(page);
        return pages;
    }

    /**
     * Formats one chunk-map entry: the chunk's display anchor, its quoted title and its statistics in
     * the outline's format.
     *
     * @param parentAnchor the canonical parent anchor
     * @param index the 1-based chunk ordinal
     * @param chunk the chunk
     * @return the formatted entry line
     */
    private static String mapEntry(String parentAnchor, int index, Chunk chunk)
    {
        return chunkAnchorRef(parentAnchor, String.valueOf(index)) + TITLE_SEPARATOR + chunkTitle(chunk)
            + STATS_OPEN + formatStats(chunk.chars(), chunk.tables(), chunk.images(), chunk.errors())
            + CLOSE_PAREN;
    }

    /**
     * Builds a chunk's map-entry title: the first {@value #CHUNK_TITLE_WORDS} words of its collapsed
     * text content, quoted, with an ellipsis when cut; a chunk with no text falls back to the tag of
     * its first element in brackets.
     *
     * @param chunk the chunk
     * @return the title
     */
    private static String chunkTitle(Chunk chunk)
    {
        String text = WHITESPACE_RUN.matcher(chunkText(chunk)).replaceAll(SPACE).trim();
        if (text.isEmpty()) {
            return fallbackTitle(chunk);
        }
        String[] words = text.split(SPACE);
        if (words.length <= CHUNK_TITLE_WORDS) {
            return QUOTE + text + QUOTE;
        }
        return QUOTE + String.join(SPACE, Arrays.asList(words).subList(0, CHUNK_TITLE_WORDS)) + ELLIPSIS
            + QUOTE;
    }

    private static String chunkText(Chunk chunk)
    {
        StringBuilder text = new StringBuilder();
        for (Node node = chunk.first(); node != null; node = node.getNextSibling()) {
            text.append(node.getTextContent()).append(' ');
            if (node == chunk.last()) {
                break;
            }
        }
        return text.toString();
    }

    /**
     * Names a text-less chunk by its first element's tag ({@code [table]}), or by the first node's
     * node name when the run holds no element at all.
     *
     * @param chunk the chunk
     * @return the bracketed fallback title
     */
    private static String fallbackTitle(Chunk chunk)
    {
        String name = chunk.first().getNodeName().toLowerCase(Locale.ROOT);
        for (Node node = chunk.first(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                name = tagName((Element) node);
                break;
            }
            if (node == chunk.last()) {
                break;
            }
        }
        return "[" + name + "]";
    }

    private int indexOf(String anchor)
    {
        for (int i = 0; i < this.sections.size(); i++) {
            if (this.sections.get(i).anchor().equals(anchor)) {
                return i;
            }
        }
        return -1;
    }

    private void beginTerminal()
    {
        if (this.serialized) {
            throw new IllegalStateException(
                "This parsed document was already serialized; parse the HTML again for another terminal call.");
        }
        this.serialized = true;
    }

    private String serializeDocument()
    {
        // Remove the head/body envelope, then serialize without the XML declaration and the doctype and
        // strip the root element the way the platform's RSSContentCleaner does: with omitDeclaration=true
        // and omitDoctype=true, the serialized form is a leading newline plus "<html>" (7 characters) and
        // a trailing "</html>" plus newline (8 characters). The substring bounds are coupled to those two
        // toString() flags.
        HTMLUtils.stripHTMLEnvelope(this.document);
        String output = HTMLUtils.toString(this.document, true, true);
        return output.substring(7, output.length() - 8);
    }

    /**
     * Formats one outline line: indentation by heading level, the anchor, the title and the aggregate
     * statistics; the intro pseudo-section has no indentation and no title.
     *
     * @param section the section
     * @return the formatted outline line
     */
    private static String outlineLine(SectionInfo section)
    {
        String statsSuffix = STATS_OPEN
            + formatStats(section.chars(), section.tables(), section.images(), section.errors()) + CLOSE_PAREN;
        if (section.heading() == null) {
            return HASH + INTRO_ANCHOR + statsSuffix;
        }
        return OUTLINE_INDENT.repeat(section.level() - 1) + HASH + section.anchor() + TITLE_SEPARATOR
            + section.title() + statsSuffix;
    }

    /**
     * Formats aggregate content statistics: the approximate token count always, then the table, image
     * and rendering-error counts only when non-zero. Shared by the outline lines and the chunk-map
     * entries so all sizes read the same.
     *
     * @param chars the estimated serialized character count
     * @param tables the number of tables
     * @param images the number of images
     * @param errors the number of rendering-error message blocks
     * @return the formatted statistics
     */
    private static String formatStats(int chars, int tables, int images, int errors)
    {
        StringBuilder stats = new StringBuilder("~").append(chars / CHARS_PER_TOKEN).append(" tokens");
        appendCount(stats, tables, TABLE_TAG);
        appendCount(stats, images, "image");
        appendCount(stats, errors, "rendering error");
        return stats.toString();
    }

    private static void appendCount(StringBuilder stats, int count, String noun)
    {
        if (count > 0) {
            stats.append(", ").append(count).append(' ').append(noun);
            if (count > 1) {
                stats.append('s');
            }
        }
    }

    /**
     * Turns the walk's raw segments into the indexed section list: the intro pseudo-section (kept only
     * when non-empty and at least one heading exists) followed by one section per heading, each carrying
     * the aggregate statistics of its segment plus every lower-level segment up to the next
     * same-or-higher-level heading.
     *
     * @param segments the raw walk segments, the intro segment first
     * @return the section list, in document order
     */
    private static List<SectionInfo> buildSections(List<Segment> segments)
    {
        List<SectionInfo> sections = new ArrayList<>();
        Segment intro = segments.get(0);
        boolean introKept = segments.size() > 1
            && (intro.hasText || intro.tables + intro.images + intro.errors > 0);
        if (introKept) {
            sections.add(new SectionInfo(0, INTRO_ANCHOR, "", null, intro.chars, intro.tables, intro.images,
                intro.errors));
        }
        for (int i = 1; i < segments.size(); i++) {
            sections.add(headingSection(segments, i));
        }
        return sections;
    }

    /**
     * Builds the section of the heading segment at the given index: a blank heading id yields a synthetic
     * {@code (h<N>)} anchor from the heading's 1-based document-order position, and the statistics
     * aggregate the segments up to (exclusive) the next same-or-higher-level heading.
     *
     * @param segments the raw walk segments, the intro segment first
     * @param index the heading segment index, equal to the heading's 1-based document-order position
     * @return the section
     */
    private static SectionInfo headingSection(List<Segment> segments, int index)
    {
        Segment segment = segments.get(index);
        String id = segment.heading.getAttribute(ID_ATTRIBUTE);
        String anchor = StringUtils.isNotBlank(id) ? id : String.format("(h%d)", index);
        String title = WHITESPACE_RUN.matcher(segment.heading.getTextContent()).replaceAll(SPACE).trim();
        int chars = 0;
        int tables = 0;
        int images = 0;
        int errors = 0;
        for (int i = index; i < segments.size(); i++) {
            Segment part = segments.get(i);
            if (i > index && part.level <= segment.level) {
                break;
            }
            chars += part.chars;
            tables += part.tables;
            images += part.images;
            errors += part.errors;
        }
        return new SectionInfo(segment.level, anchor, title, segment.heading, chars, tables, images, errors);
    }

    /**
     * Walks the cleaned subtree in document order, opening a new segment at every heading and
     * accumulating the character estimates and the table/image/error counts of every node into the
     * current (last) segment.
     *
     * @param node the subtree root (its own markup is not accumulated)
     * @param segments the segment accumulator, never empty
     */
    private static void walk(Node node, List<Segment> segments)
    {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE) {
                accumulateText(segments.get(segments.size() - 1), child.getNodeValue());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) child;
                if (HEADING_ELEMENTS.contains(tagName(element))) {
                    segments.add(new Segment(element, headingLevel(element)));
                }
                accumulateElement(segments.get(segments.size() - 1), element);
                walk(element, segments);
            }
        }
    }

    private static void accumulateText(Segment segment, String text)
    {
        segment.chars += text.length();
        if (StringUtils.isNotBlank(text)) {
            segment.hasText = true;
        }
    }

    private static void accumulateElement(Segment segment, Element element)
    {
        segment.chars += elementCharEstimate(element);
        String tag = tagName(element);
        if (TABLE_TAG.equals(tag)) {
            segment.tables++;
        }
        if ("img".equals(tag)) {
            segment.images++;
        }
        if (hasClassToken(element, RENDERING_ERROR_TOKEN)) {
            segment.errors++;
        }
    }

    /**
     * Estimates the serialized character footprint of an element's own markup: the opening and closing
     * tags plus each surviving attribute (name, value, quotes, equals sign and separating space). The
     * element's children are accumulated separately by the walk.
     *
     * @param element the element
     * @return the estimated character count
     */
    private static int elementCharEstimate(Element element)
    {
        int estimate = 2 * (element.getTagName().length() + 2) + 3;
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            estimate += attribute.getNodeName().length() + attribute.getNodeValue().length() + 4;
        }
        return estimate;
    }

    private static int headingLevel(Element element)
    {
        return tagName(element).charAt(1) - '0';
    }

    /**
     * Walks the subtree once, removing dropped elements and comment nodes and applying the requested
     * attribute detail to the remaining elements: the stripped detail removes every non-allowlisted
     * attribute, the full detail keeps them all but shortens over-long values.
     *
     * @param node the subtree root
     * @param fullDetail whether to keep all attributes instead of the stripped allowlist
     */
    private static void clean(Node node, boolean fullDetail)
    {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (isDropped(child)) {
                node.removeChild(child);
            } else {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    if (fullDetail) {
                        shortenAttributes((Element) child);
                    } else {
                        cleanAttributes((Element) child);
                    }
                }
                clean(child, fullDetail);
            }
            child = next;
        }
    }

    private static boolean isDropped(Node node)
    {
        if (node.getNodeType() == Node.COMMENT_NODE) {
            return true;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return false;
        }
        Element element = (Element) node;
        return DROPPED_ELEMENTS.contains(tagName(element))
            || hasClassToken(element, RENDERING_ERROR_DESCRIPTION_TOKEN);
    }

    /**
     * Tests whether the element's {@code class} attribute contains the given whitespace-delimited token.
     *
     * @param element the element to test
     * @param token the class token to look for
     * @return {@code true} if the element bears the token
     */
    private static boolean hasClassToken(Element element, String token)
    {
        String classValue = element.getAttribute(CLASS_ATTRIBUTE);
        return Arrays.asList(classValue.trim().split(WHITESPACE_SPLIT)).contains(token);
    }

    /**
     * Shortens the element's over-long attribute values in the full markup detail: a value longer than
     * {@value #MAX_FULL_ATTRIBUTE_CHARS} characters is cut at that length - backing off one character
     * rather than splitting a surrogate pair - with {@link #SHORTENED_MARKER} appended. Applied at
     * parse time so the retained DOM, the character estimator and the serialization all agree on the
     * shortened value. The {@code class} attribute is exempt: its tokens are the section walk's
     * semantics (rendering-error detection), and class lists never legitimately reach this length.
     *
     * @param element the element whose attributes to shorten
     */
    private static void shortenAttributes(Element element)
    {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (CLASS_ATTRIBUTE.equals(attribute.getNodeName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String value = attribute.getNodeValue();
            if (value.length() > MAX_FULL_ATTRIBUTE_CHARS) {
                int cut = MAX_FULL_ATTRIBUTE_CHARS;
                if (Character.isHighSurrogate(value.charAt(cut - 1))) {
                    cut--;
                }
                attribute.setNodeValue(value.substring(0, cut) + SHORTENED_MARKER);
            }
        }
    }

    private static void cleanAttributes(Element element)
    {
        NamedNodeMap attributes = element.getAttributes();
        // Iterate backwards: removing an attribute mutates the map.
        for (int i = attributes.getLength() - 1; i >= 0; i--) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName().toLowerCase(Locale.ROOT);
            if (KEPT_ATTRIBUTES.contains(name)
                || (ID_ATTRIBUTE.equals(name) && HEADING_ELEMENTS.contains(tagName(element)))) {
                continue;
            }
            if (CLASS_ATTRIBUTE.equals(name)) {
                cleanClassAttribute(element, attribute);
            } else {
                element.removeAttribute(attribute.getNodeName());
            }
        }
    }

    /**
     * Keeps only the allowlisted class tokens, preserving their order; removes the attribute entirely
     * when no token survives.
     *
     * @param element the element holding the attribute
     * @param attribute the {@code class} attribute node
     */
    private static void cleanClassAttribute(Element element, Node attribute)
    {
        String kept = Arrays.stream(attribute.getNodeValue().trim().split(WHITESPACE_SPLIT))
            .filter(KEPT_CLASS_TOKENS::contains)
            .collect(Collectors.joining(SPACE));
        if (kept.isEmpty()) {
            element.removeAttribute(attribute.getNodeName());
        } else {
            attribute.setNodeValue(kept);
        }
    }

    private static String tagName(Element element)
    {
        return element.getTagName().toLowerCase(Locale.ROOT);
    }

    /**
     * One indexed section, in document order: the heading element (or {@code null} for the intro
     * pseudo-section), its resolved anchor, and the aggregate content statistics of the section
     * including its sub-sections.
     *
     * @param level the heading level (1 to 6), or 0 for the intro pseudo-section
     * @param anchor the resolved anchor: the heading id, a synthetic {@code (h<N>)} for an id-less
     *            heading, or {@code (intro)}
     * @param title the whitespace-collapsed heading text, empty for the intro pseudo-section
     * @param heading the heading element, or {@code null} for the intro pseudo-section
     * @param chars the estimated serialized character count of the section
     * @param tables the number of tables in the section
     * @param images the number of images in the section
     * @param errors the number of rendering-error message blocks in the section
     * @version $Id$
     */
    private record SectionInfo(int level, String anchor, String title, Element heading, int chars, int tables,
        int images, int errors)
    {
    }

    /**
     * Mutable walk accumulator for one document-order segment: the intro (before the first heading) or
     * a heading and the content following it up to the next heading of any level.
     *
     * @version $Id$
     */
    private static final class Segment
    {
        private final Element heading;

        private final int level;

        private int chars;

        private int tables;

        private int images;

        private int errors;

        private boolean hasText;

        /**
         * Opens a new segment.
         *
         * @param heading the heading element opening the segment, or {@code null} for the intro segment
         * @param level the heading level, or 0 for the intro segment
         */
        Segment(Element heading, int level)
        {
            this.heading = heading;
            this.level = level;
        }
    }

    /**
     * A parsed chunk-anchor request: either one chunk ({@code chunkIndex >= 1}) or one chunk-map page
     * ({@code mapPage >= 1}) of the resolved parent.
     *
     * @param parentAnchor the canonical parent anchor, as accepted by the chunk queries
     * @param chunkIndex the 1-based chunk ordinal, or 0 for a map-page request
     * @param mapPage the 1-based map page, or 0 for a chunk request
     * @version $Id$
     */
    private record ChunkRequest(String parentAnchor, int chunkIndex, int mapPage)
    {
    }

    /**
     * One computed chunk: a run of consecutive sibling subtrees, addressed positionally, plus its
     * aggregate statistics.
     *
     * @param first the first node of the run
     * @param last the last node of the run (a following sibling of {@code first}, or {@code first}
     *            itself)
     * @param chars the estimated serialized character count
     * @param tables the number of tables
     * @param images the number of images
     * @param errors the number of rendering-error message blocks
     * @param columns the column headers of the table the run was descended from, empty (never
     *            {@code null}) for a run outside a table's subtree
     * @version $Id$
     */
    private record Chunk(Node first, Node last, int chars, int tables, int images, int errors,
        List<String> columns)
    {
    }

    /**
     * Mutable accumulator for one chunk run being packed: the first and last node of the run and its
     * aggregate statistics, plus the column headers every chunk of this packing level carries.
     *
     * @version $Id$
     */
    private static final class Run
    {
        private final List<String> columns;

        private Node first;

        private Node last;

        private Segment stats = new Segment(null, 0);

        /**
         * Opens an accumulator for one packing level.
         *
         * @param columns the column headers every emitted chunk carries (empty outside a table's
         *            subtree)
         */
        Run(List<String> columns)
        {
            this.columns = columns;
        }

        /**
         * Appends a subtree to the run, first flushing the run when the subtree starts under a new
         * parent or would overflow the chunk target.
         *
         * @param node the subtree root
         * @param nodeStats the subtree's statistics
         * @param chunks the chunk accumulator
         */
        void append(Node node, Segment nodeStats, List<Chunk> chunks)
        {
            if (this.first != null && (node.getParentNode() != this.last.getParentNode()
                || this.stats.chars + nodeStats.chars > CHUNK_TARGET_CHARS)) {
                flush(chunks);
            }
            if (this.first == null) {
                this.first = node;
            }
            this.last = node;
            this.stats.chars += nodeStats.chars;
            this.stats.tables += nodeStats.tables;
            this.stats.images += nodeStats.images;
            this.stats.errors += nodeStats.errors;
        }

        /**
         * Closes the run into a chunk, when non-empty, and resets the accumulator.
         *
         * @param chunks the chunk accumulator
         */
        void flush(List<Chunk> chunks)
        {
            if (this.first != null) {
                chunks.add(new Chunk(this.first, this.last, this.stats.chars, this.stats.tables,
                    this.stats.images, this.stats.errors, this.columns));
            }
            this.first = null;
            this.last = null;
            this.stats = new Segment(null, 0);
        }
    }
}
