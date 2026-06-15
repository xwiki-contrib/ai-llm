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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that documents the other MCP tools, in the style of the Unix {@code man} command.
 *
 * <p>Called with no arguments it prints a catalog of all enabled tools grouped by
 * {@link MCPTool#getCategory()}. Called with a {@code tool} argument it prints that tool's full
 * manual page: a NAME/SYNOPSIS/OPTIONS block derived from the tool's
 * {@link MCPTool#getToolDefinition() input schema}, a DESCRIPTION section taken from the tool's
 * {@link MCPTool#getToolDefinition() description}, followed by the tool's
 * {@link MCPTool#getManPage() long-form prose} if it has any.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPManTool.TOOL_ID)
@Singleton
public class MCPManTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "man";

    private static final String TYPE = "type";

    private static final String DESCRIPTION = "description";

    private static final String TOOL_PARAM = "tool";

    private static final String DEFAULT_CATEGORY = "General";

    private static final String NEW_LINE = "\n";

    private static final String INDENT = "    ";

    private static final String INDENT_DEEP = "        ";

    private static final String SECTION_NAME = "NAME";

    private static final String SECTION_SYNOPSIS = "SYNOPSIS";

    private static final String SECTION_OPTIONS = "OPTIONS";

    private static final String SECTION_DESCRIPTION = "DESCRIPTION";

    private static final String SENTENCE_TERMINATOR = ". ";

    private static final String REQUIRED = "required";

    private static final String PROPERTIES = "properties";

    private static final String OPTIONAL = "optional";

    private static final String NAME_SEPARATOR = " - ";

    private static final String COMMA_SPACE = ", ";

    private static final String SECTION_REFERENCE = "Reference";

    private static final String XWIKI_SYNTAX_ID = "xwiki-syntax";

    private static final String XWIKI_SYNTAX_SUMMARY = "XWiki Syntax 2.1 quick reference for writing page source.";

    private static final String XWIKI_SYNTAX_CONTENT = """
        XWiki Syntax 2.1 - the source syntax you write when creating or editing a page (syntax id xwiki/2.1).
        It is NOT Markdown. Use the forms below; read a real page with get_document to see live examples.

        PARAGRAPHS
            Separate paragraphs with a blank line. A single newline continues the same paragraph.

        HEADINGS
            = Level 1 =
            == Level 2 ==
            ====== Level 6 ======

        TEXT FORMATTING
            **bold**  //italic//  __underline__  --strikethrough--  ##monospace##
            super^^script^^   sub,,subscript,,

        LINE BREAK
            A forced line break inside a paragraph is two backslashes: \\\\

        LISTS
            * bullet
            ** nested bullet
            1. numbered
            11. nested numbered
            1*. mixed (numbered then bullet)
            Definition list:
            ; term
            : definition

        LINKS
            [[Space.Page]]                        link to a page (label defaults to the page name)
            [[Label>>Space.Page]]                 link with a label
            [[Label>>https://xwiki.org]]          link to an external URL
            [[Space.Page||target="_blank"]]       open in a new window
            [[Space.Page||anchor="HMyHeading"]]   link to a heading (anchor = "H" + heading text, letters only)
            [[Space.Page||queryString="a=1&b=2"]] link with a query string
            [[john@x.net>>mailto:john@x.net]]     email link
            [[attach:Space.Page@file.pdf]]        link to an attachment
            Reference forms: Space.Page, Space.Page.WebHome (terminal), wiki:Space.Page (cross-wiki),
            .Child or ../Sibling (relative to the current page).

        IMAGES
            image:file.png                        attachment on the current page
            image:Space.Page@file.png             attachment on another page
            image:https://host/img.png            image at a URL
            [[image:file.png||width="200" alt="logo"]]   image with parameters

        TABLES
            |=Header 1|=Header 2
            |Cell 1|Cell 2
            (|= is a header cell, | a normal cell)

        VERBATIM (content is shown as-is, no formatting)
            Inline:  {{{ **not rendered** }}}
            Block:   {{{
                     multi-line raw text
                     }}}

        QUOTATIONS
            > quoted line
            >> nested quote

        GROUPS (embed block content where one is not normally allowed, e.g. a table cell or list item)
            (((
            = embedded heading =
            block content
            )))

        PARAMETERS / STYLING (apply to the block that follows; (%%) resets inline styling)
            These parameters, and ||key="value" parameters on links and images, are mostly passed
            through as HTML attributes on the generated element - any HTML attribute works
            (class, style, id, title, ...). Macro parameters are macro-specific, not attributes.
            (% class="myClass" style="color:blue" %)
            = styled heading =
            Inline: some (% style="color:red" %)red(%%) text.

        ESCAPING
            Escape one character with a tilde: ~[~[not a link~]~]
            A literal tilde is two tildes: ~~

        MACROS
            With content:  {{code language="java"}}int total = a + b;{{/code}}
            Self-closing:  {{toc/}}   {{include reference="Space.Page"/}}
            Common: {{info}}...{{/info}}, {{warning}}, {{error}}, {{success}} (message boxes);
                    {{toc/}} (table of contents); {{code}} (highlighted code); {{html}}...{{/html}} (raw HTML).
            Script macros ({{velocity}}, {{groovy}}) run only with the right script/programming rights.
        """;

    private static final Map<String, ReferencePage> REFERENCE_PAGES = Map.of(
        XWIKI_SYNTAX_ID, new ReferencePage(XWIKI_SYNTAX_ID, XWIKI_SYNTAX_SUMMARY, XWIKI_SYNTAX_CONTENT));

    /**
     * The declared parameters: one source for both the advertised input schema and the typed
     * argument accessors.
     */
    private static final MCPToolSupport PARAMS = MCPToolSupport.builder()
        .string(TOOL_PARAM, "Name of the tool to show the manual page for. Omit to list all "
            + "available tools by category.")
        .build();

    @Inject
    private Logger logger;

    /**
     * Farm-scoped component manager used to enumerate {@link MCPTool} implementations, mirroring
     * {@link XWikiMCPServerManager}.
     */
    @Inject
    private ComponentManager componentManager;

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.inputSchema())
            .description("Show documentation for the available MCP tools. Call with no arguments for a "
                + "categorized list of all tools; pass 'tool' with a tool name to get that tool's full "
                + "manual page (synopsis, options, description, examples).")
            .build();
    }

    @Override
    public String getCategory()
    {
        return "Help";
    }

    @Override
    public String getSummary()
    {
        return "Show documentation for the available MCP tools.";
    }

    @Override
    public String getManPage()
    {
        return """
            EXAMPLES
                List all tools:   (call with no arguments)
                One tool's page:  tool="query_documents\"""";
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        String toolName;
        try {
            toolName = PARAMS.string(args, TOOL_PARAM);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }

        List<MCPTool> enabledTools = getEnabledTools();

        if (StringUtils.isBlank(toolName)) {
            return MCPToolSupport.result(renderCatalog(enabledTools));
        }

        MCPTool match = findByName(enabledTools, toolName);
        if (match != null) {
            return MCPToolSupport.result(renderPage(match));
        }
        ReferencePage page = REFERENCE_PAGES.get(toolName);
        if (page != null) {
            return MCPToolSupport.result(renderReferencePage(page));
        }
        return MCPToolSupport.errorResult(renderUnknown(toolName, enabledTools));
    }

    private List<MCPTool> getEnabledTools()
    {
        List<MCPTool> tools;
        try {
            tools = this.componentManager.getInstanceList(MCPTool.class);
        } catch (ComponentLookupException e) {
            this.logger.warn("Failed to look up MCPTool components for the man tool: [{}]",
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP man tool failed to enumerate MCPTool components", e);
            return List.of();
        }
        return tools.stream().filter(MCPTool::isEnabled).collect(Collectors.toList());
    }

    private MCPTool findByName(List<MCPTool> tools, String name)
    {
        return tools.stream()
            .filter(tool -> name.equals(tool.getToolDefinition().name()))
            .findFirst()
            .orElse(null);
    }

    private String renderCatalog(List<MCPTool> tools)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools and reference pages — use `man <name>` for full documentation:")
            .append(NEW_LINE);

        Map<String, List<String>> byCategory = new TreeMap<>();
        for (MCPTool tool : tools) {
            String category = StringUtils.defaultIfBlank(tool.getCategory(), DEFAULT_CATEGORY);
            byCategory.computeIfAbsent(category, key -> new ArrayList<>())
                .add(catalogLine(tool.getToolDefinition().name(), tagline(tool)));
        }
        for (ReferencePage page : REFERENCE_PAGES.values()) {
            byCategory.computeIfAbsent(SECTION_REFERENCE, key -> new ArrayList<>())
                .add(catalogLine(page.id(), page.summary()));
        }

        for (Map.Entry<String, List<String>> entry : byCategory.entrySet()) {
            sb.append(NEW_LINE).append(entry.getKey()).append(NEW_LINE);
            entry.getValue().stream().sorted().forEach(line -> sb.append(line).append(NEW_LINE));
        }
        return sb.toString().stripTrailing();
    }

    private String catalogLine(String name, String tagline)
    {
        return "  " + name + NAME_SEPARATOR + tagline;
    }

    private String renderPage(MCPTool tool)
    {
        McpSchema.Tool definition = tool.getToolDefinition();
        List<String> ordered = orderedParamNames(definition);

        StringBuilder sb = new StringBuilder();
        sb.append(SECTION_NAME).append(NEW_LINE);
        sb.append(INDENT).append(definition.name()).append(NAME_SEPARATOR).append(tagline(tool))
            .append(NEW_LINE).append(NEW_LINE);

        sb.append(SECTION_SYNOPSIS).append(NEW_LINE);
        sb.append(INDENT).append(renderSynopsis(definition, ordered)).append(NEW_LINE);

        String options = renderOptions(definition, ordered);
        if (StringUtils.isNotBlank(options)) {
            sb.append(NEW_LINE).append(SECTION_OPTIONS).append(NEW_LINE).append(options);
        }

        String description = definition.description();
        if (StringUtils.isNotBlank(description)) {
            sb.append(NEW_LINE).append(SECTION_DESCRIPTION).append(NEW_LINE)
                .append(INDENT).append(description.strip()).append(NEW_LINE);
        }

        String manPage = tool.getManPage();
        if (StringUtils.isNotBlank(manPage)) {
            sb.append(NEW_LINE).append(manPage.stripTrailing());
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Resolves the one-line tagline for a tool: its {@link MCPTool#getSummary() summary} when set,
     * otherwise the first sentence of its description.
     *
     * @param tool the tool
     * @return the tagline, never {@code null}
     */
    private String tagline(MCPTool tool)
    {
        String summary = tool.getSummary();
        if (StringUtils.isNotBlank(summary)) {
            return summary;
        }
        return firstSentence(tool.getToolDefinition().description());
    }

    /**
     * Returns the first sentence of the given text: everything up to and including the first
     * period that is followed by a space. If there is no such terminator the whole (trimmed)
     * text is returned.
     *
     * @param text the text to truncate, may be {@code null}
     * @return the first sentence, or an empty string when {@code text} is {@code null}
     */
    private String firstSentence(String text)
    {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        int index = trimmed.indexOf(SENTENCE_TERMINATOR);
        if (index < 0) {
            return trimmed;
        }
        return trimmed.substring(0, index + 1);
    }

    /**
     * Returns parameter names in render order: required params in their declared {@code required()}
     * order, followed by the remaining (optional) params in the schema's own property order. Tool
     * authors declare parameters in importance order, so the synopsis and options list lead with what
     * matters most (a schema built from an unordered map simply yields an arbitrary order).
     *
     * @param definition the tool definition
     * @return the ordered parameter names, possibly empty
     */
    private List<String> orderedParamNames(McpSchema.Tool definition)
    {
        Map<String, Object> properties = properties(definition);
        if (properties.isEmpty()) {
            return List.of();
        }

        List<String> required = required(definition);
        List<String> ordered = new ArrayList<>();
        for (String name : required) {
            if (properties.containsKey(name)) {
                ordered.add(name);
            }
        }
        properties.keySet().stream()
            .filter(name -> !required.contains(name))
            .forEach(ordered::add);
        return ordered;
    }

    private String renderSynopsis(McpSchema.Tool definition, List<String> ordered)
    {
        if (ordered.isEmpty()) {
            return definition.name();
        }

        Map<String, Object> properties = properties(definition);
        List<String> required = required(definition);
        StringBuilder sb = new StringBuilder(definition.name());
        for (String name : ordered) {
            String token = name + typeSuffix(properties.get(name));
            sb.append(' ').append(required.contains(name) ? token : "[" + token + "]");
        }
        return sb.toString();
    }

    private String renderOptions(McpSchema.Tool definition, List<String> ordered)
    {
        if (ordered.isEmpty()) {
            return null;
        }

        Map<String, Object> properties = properties(definition);
        List<String> required = required(definition);
        StringBuilder sb = new StringBuilder();
        for (String name : ordered) {
            Map<String, Object> property = asPropertyMap(properties.get(name));
            String type = stringValue(property.get(TYPE));
            String requiredness = required.contains(name) ? REQUIRED : OPTIONAL;
            sb.append(INDENT).append(name).append(" (").append(optionParenthetical(type, requiredness))
                .append(')').append(NEW_LINE);
            String description = stringValue(property.get(DESCRIPTION));
            if (StringUtils.isNotBlank(description)) {
                sb.append(INDENT_DEEP).append(description).append(NEW_LINE);
            }
        }
        return sb.toString();
    }

    private String optionParenthetical(String type, String requiredness)
    {
        if (StringUtils.isBlank(type)) {
            return requiredness;
        }
        return type + COMMA_SPACE + requiredness;
    }

    private String renderReferencePage(ReferencePage page)
    {
        return SECTION_NAME + NEW_LINE
            + INDENT + page.id() + NAME_SEPARATOR + page.summary() + NEW_LINE + NEW_LINE
            + page.content().stripTrailing();
    }

    private String renderUnknown(String name, List<MCPTool> tools)
    {
        String available = tools.stream()
            .map(tool -> tool.getToolDefinition().name())
            .sorted()
            .collect(Collectors.joining(COMMA_SPACE));
        String pages = String.join(COMMA_SPACE, REFERENCE_PAGES.keySet());
        return "No manual entry for \"" + name + "\". Available tools: " + available
            + ". Reference pages: " + pages + ".";
    }

    private String typeSuffix(Object property)
    {
        String type = stringValue(asPropertyMap(property).get(TYPE));
        return type == null ? "" : "=<" + type + ">";
    }

    private Map<String, Object> properties(McpSchema.Tool definition)
    {
        Map<String, Object> schema = definition.inputSchema();
        if (schema == null) {
            return Map.of();
        }
        return asPropertyMap(schema.get(PROPERTIES));
    }

    /**
     * Reads the {@code required} names from a tool's input schema map, null-safe and type-safe:
     * third-party tools may advertise arbitrary maps, so anything that is not a collection of
     * strings degrades to "nothing is required" rather than failing the page render.
     *
     * @param definition the tool definition
     * @return the required parameter names, possibly empty
     */
    private List<String> required(McpSchema.Tool definition)
    {
        Map<String, Object> schema = definition.inputSchema();
        Object value = schema == null ? null : schema.get(REQUIRED);
        if (!(value instanceof Collection<?> names)) {
            return List.of();
        }
        return names.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .collect(Collectors.toList());
    }

    private Map<String, Object> asPropertyMap(Object value)
    {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private String stringValue(Object value)
    {
        return value instanceof String str ? str : null;
    }

    /**
     * A built-in reference page served by {@code man <id>} and listed in the catalog under the
     * {@value #SECTION_REFERENCE} category. Unlike a tool page it has no schema-derived synopsis or
     * options; its body is fixed prose provided by the man tool itself.
     *
     * @param id the page identifier, used as the man argument and the catalog name
     * @param summary the one-line tagline shown in the catalog and on the NAME line
     * @param content the page body
     * @version $Id$
     */
    private record ReferencePage(String id, String summary, String content)
    {
    }
}
