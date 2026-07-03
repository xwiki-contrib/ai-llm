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
 * {@link #sectionOutline(String)}) and serializes either the whole document ({@link #serialize()}) or
 * one heading-addressed section ({@link #sectionHtml(String)}).
 *
 * <p>Serialization mutates the retained DOM, so {@link #serialize()} and {@link #sectionHtml(String)}
 * are terminal: at most one of them may be called, once, per instance.</p>
 *
 * <p>The allowlist rationale: attributes and class tokens that carry comprehension signal for an agent
 * survive (link and image targets, captions, table geometry, heading anchors, message-box semantics,
 * broken-link and rendering-error markers); everything else is presentation scaffolding and is dropped
 * for token economy. Scripts, styles and comments are removed entirely. The macro rendering-error
 * <em>message</em> block ({@code xwikirenderingerror}) is kept as a useful signal, but its sibling
 * <em>description</em> block ({@code xwikirenderingerrordescription}) is removed with its subtree: it
 * carries the full Java stack trace, which is token spam with no comprehension value for an agent.</p>
 *
 * @version $Id$
 * @since 0.9
 */
final class MCPRenderedHtml
{
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
     * marker and the rendering-error message marker. All other tokens (e.g. {@code wikigeneratedid},
     * {@code wikilink}, {@code wikiexternallink}, {@code wikimodel-*}) are presentation scaffolding and
     * are dropped; the {@code class} attribute itself is removed when no token survives.
     */
    private static final Set<String> KEPT_CLASS_TOKENS = Set.of("box", "infomessage", "warningmessage",
        "errormessage", "successmessage", "wikicreatelink", RENDERING_ERROR_TOKEN);

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

    private static final String ID_ATTRIBUTE = "id";

    private static final String CLASS_ATTRIBUTE = "class";

    private static final String BODY_TAG = "body";

    private static final String TABLE_TAG = "table";

    private static final String HASH = "#";

    private static final String NEW_LINE = "\n";

    private static final String SPACE = " ";

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
     * Whether a terminal call ({@link #serialize()} or {@link #sectionHtml(String)}) already consumed
     * the retained document.
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
     * Cleans rendered HTML for agent output: parses it as HTML5 with the given cleaner (which guarantees
     * a well-formed DOM even for raw {@code {{html}}} passthrough), removes scripts, styles and comments,
     * strips every non-allowlisted attribute, and indexes the heading sections of the cleaned DOM in one
     * document-order walk.
     *
     * @param cleaner the HTML cleaner component, supplied by the calling tool
     * @param html the rendered HTML
     * @return the parsed wrapper, ready for outline queries and one terminal serialization
     */
    static MCPRenderedHtml parse(HTMLCleaner cleaner, String html)
    {
        HTMLCleanerConfiguration configuration = cleaner.getDefaultConfiguration();
        Map<String, String> parameters = new HashMap<>(configuration.getParameters());
        // Parse as HTML5, since the rendered content is html/5.0. Restricted mode is not used on purpose:
        // it converts script/style elements to pre elements, preserving their payload, whereas the walk
        // below removes them entirely.
        parameters.put(HTMLCleanerConfiguration.HTML_VERSION, "5");
        configuration.setParameters(parameters);

        Document document = cleaner.clean(new StringReader(html), configuration);
        clean(document);
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
        Range range = buildSectionRange(documentRange, body, index);
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
        String statsSuffix = "  (" + stats(section) + ")";
        if (section.heading() == null) {
            return HASH + INTRO_ANCHOR + statsSuffix;
        }
        return OUTLINE_INDENT.repeat(section.level() - 1) + HASH + section.anchor() + ": " + section.title()
            + statsSuffix;
    }

    /**
     * Formats a section's aggregate statistics: the approximate token count always, then the table, image
     * and rendering-error counts only when non-zero.
     *
     * @param section the section
     * @return the formatted statistics
     */
    private static String stats(SectionInfo section)
    {
        StringBuilder stats = new StringBuilder("~").append(section.chars() / CHARS_PER_TOKEN).append(" tokens");
        appendCount(stats, section.tables(), TABLE_TAG);
        appendCount(stats, section.images(), "image");
        appendCount(stats, section.errors(), "rendering error");
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
     * Walks the subtree once, removing dropped elements and comment nodes and stripping non-allowlisted
     * attributes from the remaining elements.
     *
     * @param node the subtree root
     */
    private static void clean(Node node)
    {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (isDropped(child)) {
                node.removeChild(child);
            } else {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    cleanAttributes((Element) child);
                }
                clean(child);
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
}
