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
import java.util.Comparator;
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

    private static final String OBJECT = "object";

    private static final String STRING = "string";

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

    private static final String OPTIONAL = "optional";

    private static final String NAME_SEPARATOR = " - ";

    private static final String COMMA_SPACE = ", ";

    private static final String ERROR_PREFIX = "Error: '";

    private static final String STRING_PARAM_ERROR_SUFFIX = "' parameter must be a string.";

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
        Map<String, Object> properties = Map.of(
            TOOL_PARAM, Map.of(
                TYPE, STRING,
                DESCRIPTION, "Name of the tool to show the manual page for. Omit to list all "
                    + "available tools by category."
            )
        );
        return McpSchema.Tool.builder()
            .name(TOOL_ID)
            .description("Show documentation for the available MCP tools. Call with no arguments for a "
                + "categorized list of all tools; pass 'tool' with a tool name to get that tool's full "
                + "manual page (synopsis, options, description, examples).")
            .inputSchema(new McpSchema.JsonSchema(OBJECT, properties, List.of(), null, null, null))
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
                One tool's page:  tool="query_pages\"""";
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        String toolName;
        try {
            toolName = getOptionalStringParam(args, TOOL_PARAM);
        } catch (IllegalArgumentException e) {
            return buildErrorResult(e.getMessage());
        }

        List<MCPTool> enabledTools = getEnabledTools();

        if (StringUtils.isBlank(toolName)) {
            return success(renderCatalog(enabledTools));
        }

        MCPTool match = findByName(enabledTools, toolName);
        if (match != null) {
            return success(renderPage(match));
        }
        return buildErrorResult(renderUnknown(toolName, enabledTools));
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
        sb.append("Available tools — use `man <tool>` for a tool's full documentation:").append(NEW_LINE);

        Map<String, List<MCPTool>> byCategory = new TreeMap<>();
        for (MCPTool tool : tools) {
            String category = StringUtils.defaultIfBlank(tool.getCategory(), DEFAULT_CATEGORY);
            byCategory.computeIfAbsent(category, key -> new ArrayList<>()).add(tool);
        }

        for (Map.Entry<String, List<MCPTool>> entry : byCategory.entrySet()) {
            sb.append(NEW_LINE).append(entry.getKey()).append(NEW_LINE);
            entry.getValue().stream()
                .sorted(Comparator.comparing(tool -> tool.getToolDefinition().name()))
                .forEach(tool -> appendCatalogLine(sb, tool));
        }
        return sb.toString().stripTrailing();
    }

    private void appendCatalogLine(StringBuilder sb, MCPTool tool)
    {
        sb.append("  ").append(tool.getToolDefinition().name()).append(NAME_SEPARATOR).append(tagline(tool))
            .append(NEW_LINE);
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
     * order, followed by the remaining (optional) params sorted alphabetically.
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
            .sorted()
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

    private String renderUnknown(String name, List<MCPTool> tools)
    {
        String available = tools.stream()
            .map(tool -> tool.getToolDefinition().name())
            .sorted()
            .collect(Collectors.joining(COMMA_SPACE));
        return "No manual entry for \"" + name + "\". Available tools: " + available + ".";
    }

    private String typeSuffix(Object property)
    {
        String type = stringValue(asPropertyMap(property).get(TYPE));
        return type == null ? "" : "=<" + type + ">";
    }

    private Map<String, Object> properties(McpSchema.Tool definition)
    {
        McpSchema.JsonSchema schema = definition.inputSchema();
        if (schema == null || schema.properties() == null) {
            return Map.of();
        }
        return schema.properties();
    }

    private List<String> required(McpSchema.Tool definition)
    {
        McpSchema.JsonSchema schema = definition.inputSchema();
        if (schema == null || schema.required() == null) {
            return List.of();
        }
        return schema.required();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asPropertyMap(Object value)
    {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Map.of();
    }

    private String stringValue(Object value)
    {
        return value instanceof String str ? str : null;
    }

    private String getOptionalStringParam(Map<String, Object> args, String key)
    {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException(ERROR_PREFIX + key + STRING_PARAM_ERROR_SUFFIX);
        }
        return StringUtils.trimToNull(str);
    }

    private McpSchema.CallToolResult success(String message)
    {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .build();
    }

    private McpSchema.CallToolResult buildErrorResult(String message)
    {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .isError(true)
            .build();
    }
}
