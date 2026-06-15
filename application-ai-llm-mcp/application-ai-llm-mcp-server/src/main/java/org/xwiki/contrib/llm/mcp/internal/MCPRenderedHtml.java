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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xwiki.xml.html.HTMLCleaner;
import org.xwiki.xml.html.HTMLCleanerConfiguration;
import org.xwiki.xml.html.HTMLUtils;

/**
 * Strips rendered HTML down to what carries meaning for an LLM reader, for the {@code get_document}
 * tool's {@code rendered=true, format="html"} mode.
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
     * Class tokens kept because they carry comprehension signal: message-box semantics, the broken-link
     * marker and the rendering-error message marker. All other tokens (e.g. {@code wikigeneratedid},
     * {@code wikilink}, {@code wikiexternallink}, {@code wikimodel-*}) are presentation scaffolding and
     * are dropped; the {@code class} attribute itself is removed when no token survives.
     */
    private static final Set<String> KEPT_CLASS_TOKENS = Set.of("box", "infomessage", "warningmessage",
        "errormessage", "successmessage", "wikicreatelink", "xwikirenderingerror");

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

    private static final String ID_ATTRIBUTE = "id";

    private static final String CLASS_ATTRIBUTE = "class";

    /**
     * Splits a {@code class} attribute value into its whitespace-delimited tokens.
     */
    private static final String WHITESPACE_SPLIT = "\\s+";

    private MCPRenderedHtml()
    {
    }

    /**
     * Cleans rendered HTML for agent output: parses it as HTML5 with the given cleaner (which guarantees
     * a well-formed DOM even for raw {@code {{html}}} passthrough), removes scripts, styles and comments,
     * strips every non-allowlisted attribute, and serializes the result back without the
     * html/head/body envelope.
     *
     * @param cleaner the HTML cleaner component, supplied by the calling tool
     * @param html the rendered HTML
     * @return the stripped HTML fragment
     */
    static String strip(HTMLCleaner cleaner, String html)
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

        // Remove the head/body envelope, then serialize without the XML declaration and the doctype and
        // strip the root element the way the platform's RSSContentCleaner does: with omitDeclaration=true
        // and omitDoctype=true, the serialized form is a leading newline plus "<html>" (7 characters) and
        // a trailing "</html>" plus newline (8 characters). The substring bounds are coupled to those two
        // toString() flags.
        HTMLUtils.stripHTMLEnvelope(document);
        String serialized = HTMLUtils.toString(document, true, true);
        return serialized.substring(7, serialized.length() - 8);
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
        return DROPPED_ELEMENTS.contains(tagName(element)) || isRenderingErrorDescription(element);
    }

    /**
     * Tests whether the element is a macro rendering-error description block (its {@code class} attribute
     * has the {@link #RENDERING_ERROR_DESCRIPTION_TOKEN} token), so it and its stack-trace subtree are
     * removed.
     *
     * @param element the element to test
     * @return {@code true} if the element bears the rendering-error description class token
     */
    private static boolean isRenderingErrorDescription(Element element)
    {
        String classValue = element.getAttribute(CLASS_ATTRIBUTE);
        return Arrays.asList(classValue.trim().split(WHITESPACE_SPLIT)).contains(RENDERING_ERROR_DESCRIPTION_TOKEN);
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
            .collect(Collectors.joining(" "));
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
}
