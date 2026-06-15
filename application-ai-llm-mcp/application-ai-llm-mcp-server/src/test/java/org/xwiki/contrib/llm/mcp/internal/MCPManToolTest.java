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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.mockito.MockitoComponentManager;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPManTool}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPManToolTest
{
    private static final String QUERY_DOCUMENTS = "query_documents";

    private static final String TOOL_PARAM = "tool";

    private static final String TYPE = "type";

    private static final String DESCRIPTION = "description";

    private static final String STRING = "string";

    private static final String PROPERTIES = "properties";

    private static final String REQUIRED = "required";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPManTool manTool;

    private static McpSchema.Tool toolDefinition(String name, String description, Map<String, Object> properties,
        List<String> required)
    {
        return McpSchema.Tool.builder(name, Map.of(TYPE, "object", PROPERTIES, properties, REQUIRED, required))
            .description(description)
            .build();
    }

    private static Map<String, Object> param(String type, String description)
    {
        return Map.of(TYPE, type, DESCRIPTION, description);
    }

    private static MCPTool registerTool(MockitoComponentManager componentManager, String name, String summary,
        String description, String category, boolean enabled, Map<String, Object> properties, List<String> required,
        String manPage) throws Exception
    {
        return registerTool(componentManager, name, summary, category, enabled, manPage,
            toolDefinition(name, description, properties, required));
    }

    private static MCPTool registerTool(MockitoComponentManager componentManager, String hint, String summary,
        String category, boolean enabled, String manPage, McpSchema.Tool definition) throws Exception
    {
        MCPTool tool = componentManager.registerMockComponent(MCPTool.class, hint);
        when(tool.isEnabled()).thenReturn(enabled);
        when(tool.getCategory()).thenReturn(category);
        when(tool.getSummary()).thenReturn(summary);
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.getManPage()).thenReturn(manPage);
        return tool;
    }

    private static String callMan(MCPManTool manTool, String toolArg)
    {
        Map<String, Object> args = toolArg == null ? Map.of() : Map.of(TOOL_PARAM, toolArg);
        McpSchema.CallToolResult result = manTool.execute(manRequest(args));
        return textOf(result);
    }

    private static McpSchema.CallToolRequest manRequest(Map<String, Object> args)
    {
        return McpSchema.CallToolRequest.builder("man").arguments(args).build();
    }

    private static String textOf(McpSchema.CallToolResult result)
    {
        McpSchema.Content content = result.content().get(0);
        return ((McpSchema.TextContent) content).text();
    }

    @Test
    void synopsisAndOptionsFollowSchemaDeclarationOrder(MockitoComponentManager componentManager)
        throws Exception
    {
        // Tool authors declare parameters in importance order; man must preserve it, not sort
        // alphabetically (which would bury the primary parameter).
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("zebra", param(STRING, "The primary parameter."));
        properties.put("apple", param(STRING, "A secondary parameter."));
        registerTool(componentManager, "ordered_tool", "An ordered tool.", "An ordered tool, in depth.",
            "Demo", true, properties, List.of(), null);

        String output = callMan(this.manTool, "ordered_tool");

        assertTrue(output.contains("SYNOPSIS\n    ordered_tool [zebra=<string>] [apple=<string>]"), output);
        assertTrue(output.indexOf("zebra (string, optional)") < output.indexOf("apple (string, optional)"),
            output);
    }

    @Test
    void toolDefinitionAdvertisesOptionalToolParam()
    {
        McpSchema.Tool definition = this.manTool.getToolDefinition();
        assertEquals("man", definition.name());
        Map<String, Object> schema = definition.inputSchema();
        assertEquals("object", schema.get(TYPE));
        assertTrue(((Map<?, ?>) schema.get(PROPERTIES)).containsKey(TOOL_PARAM));
        assertTrue(((List<?>) schema.get(REQUIRED)).isEmpty());
        assertEquals("Help", this.manTool.getCategory());
    }

    @Test
    void catalogGroupsByCategorySortsAndExcludesDisabledTool(MockitoComponentManager componentManager)
        throws Exception
    {
        registerTool(componentManager, QUERY_DOCUMENTS, "Search pages.", "Search pages and more.",
            "Search & Navigation", true, Map.of(), List.of(), null);
        registerTool(componentManager, "demo_tool", "A demo tool.", "A demo tool, in depth.",
            "Search & Navigation", true, Map.of(), List.of(), null);
        registerTool(componentManager, "help_me", "Show help.", "Show help and more.", "Help", true,
            Map.of(), List.of(), null);
        registerTool(componentManager, "secret", "Hidden tool.", "Hidden tool and more.", "Help", false,
            Map.of(), List.of(), null);

        String output = callMan(this.manTool, null);

        // Categories sorted alphabetically: "Help" before "Search & Navigation".
        assertTrue(output.indexOf("Help") < output.indexOf("Search & Navigation"));
        // Within a category, tools sorted by name: demo_tool before query_documents.
        assertTrue(output.indexOf("demo_tool") < output.indexOf(QUERY_DOCUMENTS));
        // Line format: two-space indent, hyphen-space separator, the one-line summary.
        assertTrue(output.contains("  query_documents - Search pages."));
        assertTrue(output.contains("  help_me - Show help."));
        // Disabled tool excluded.
        assertFalse(output.contains("secret"));
        assertFalse(output.contains("Hidden tool"));
    }

    @Test
    void taglineFallsBackToFirstSentenceOfDescriptionWhenSummaryNull(MockitoComponentManager componentManager)
        throws Exception
    {
        // A null summary forces the man tool to fall back to the first sentence of the description.
        registerTool(componentManager, "thing", null, "Does a thing. More detail here.", "Help", true,
            Map.of(), List.of(), null);

        String catalog = callMan(this.manTool, null);
        // The catalog line shows only the first sentence, not the whole description.
        assertTrue(catalog.contains("  thing - Does a thing."));
        assertFalse(catalog.contains("More detail here."));

        String page = callMan(this.manTool, "thing");
        // The NAME tagline also shows only the first sentence.
        assertTrue(page.contains("    thing - Does a thing.\n"));
        // The full description still appears in the DESCRIPTION section.
        assertTrue(page.contains("DESCRIPTION\n    Does a thing. More detail here."));
    }

    @Test
    void pageRendersSynopsisOptionsAndManPageProse(MockitoComponentManager componentManager) throws Exception
    {
        Map<String, Object> properties = Map.of(
            "query", param(STRING, "Search terms."),
            "limit", param("integer", "Max results."));
        registerTool(componentManager, QUERY_DOCUMENTS, "Search pages.", "Full description of searching.",
            "Search & Navigation", true, properties, List.of("query"), "EXAMPLES\n    Full prose body.");

        String output = callMan(this.manTool, QUERY_DOCUMENTS);

        assertTrue(output.startsWith("NAME"));
        // NAME line shows the one-line summary tagline.
        assertTrue(output.contains("    query_documents - Search pages."));
        // Required param bare, optional param bracketed; required comes first.
        assertTrue(output.contains("SYNOPSIS\n    query_documents query=<string> [limit=<integer>]"));
        // OPTIONS lists both with type + requiredness + description.
        assertTrue(output.contains("    query (string, required)\n        Search terms."));
        assertTrue(output.contains("    limit (integer, optional)\n        Max results."));
        // DESCRIPTION section carries the full tool definition description.
        assertTrue(output.contains("DESCRIPTION\n    Full description of searching."));
        // The man-page prose is appended after the description.
        assertTrue(output.contains("Full prose body."));
        assertTrue(output.indexOf("Full description of searching.") < output.indexOf("Full prose body."));
    }

    @Test
    void pageWithoutManPageHasNoTrailingProse(MockitoComponentManager componentManager) throws Exception
    {
        Map<String, Object> properties = Map.of("query", param(STRING, "Search terms."));
        registerTool(componentManager, QUERY_DOCUMENTS, "Search pages.", "Full description of searching.",
            "Search & Navigation", true, properties, List.of("query"), null);

        String output = callMan(this.manTool, QUERY_DOCUMENTS);

        // OPTIONS is present and DESCRIPTION is the last section, with no man-page prose after it.
        assertTrue(output.contains("OPTIONS"));
        assertTrue(output.endsWith("DESCRIPTION\n    Full description of searching."));
    }

    @Test
    void pageForToolWithoutParamsOmitsOptionsAndSynopsisIsNameOnly(MockitoComponentManager componentManager)
        throws Exception
    {
        registerTool(componentManager, "ping", "Ping the server.", "Ping the server in detail.", "Help", true,
            Map.of(), List.of(), null);

        String output = callMan(this.manTool, "ping");

        assertTrue(output.contains("SYNOPSIS\n    ping"));
        assertFalse(output.contains("OPTIONS"));
        // The DESCRIPTION section is the last section (no params, no man-page prose).
        assertTrue(output.endsWith("DESCRIPTION\n    Ping the server in detail."));
    }

    @Test
    void unknownToolReturnsErrorListingAvailableTools(MockitoComponentManager componentManager) throws Exception
    {
        registerTool(componentManager, QUERY_DOCUMENTS, "Search pages.", "Search pages and more.",
            "Search & Navigation", true, Map.of(), List.of(), null);
        registerTool(componentManager, "demo_tool", "A demo tool.", "A demo tool, in depth.",
            "Search & Navigation", true, Map.of(), List.of(), null);

        McpSchema.CallToolResult result = this.manTool.execute(manRequest(Map.of(TOOL_PARAM, "does_not_exist")));

        assertTrue(result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No manual entry for \"does_not_exist\""));
        // The injected man tool is itself an enabled MCPTool, so the available list also includes it,
        // sorted alphabetically: demo_tool, man, query_documents.
        assertTrue(text.contains("demo_tool, man, query_documents"));
    }

    @Test
    void pageWithParamMissingDescriptionKeyOmitsDescriptionLine(MockitoComponentManager componentManager)
        throws Exception
    {
        Map<String, Object> properties = Map.of("query", Map.of(TYPE, STRING));
        registerTool(componentManager, QUERY_DOCUMENTS, "Search pages.", "Searches pages.", "Search & Navigation",
            true, properties, List.of("query"), null);

        String output = callMan(this.manTool, QUERY_DOCUMENTS);

        // The option line is present with its type and requiredness.
        assertTrue(output.contains("    query (string, required)"));
        // No literal "null" description line is rendered for the parameter.
        assertFalse(output.contains("null"));
        // The OPTIONS block has no indented parameter description; DESCRIPTION follows after a blank line.
        assertTrue(output.contains("    query (string, required)\n\nDESCRIPTION"));
    }

    @Test
    void pageWithNonMapPropertyOmitsTypeAndDescription(MockitoComponentManager componentManager) throws Exception
    {
        Map<String, Object> properties = Map.of("query", "not-a-map");
        registerTool(componentManager, QUERY_DOCUMENTS, "Search pages.", "Searches pages.", "Search & Navigation",
            true, properties, List.of("query"), null);

        String output = callMan(this.manTool, QUERY_DOCUMENTS);

        // No type suffix in the synopsis since the property is not a map.
        assertTrue(output.contains("SYNOPSIS\n    query_documents query"));
        assertFalse(output.contains("query=<"));
        // The option is still listed, with requiredness only and no description line.
        assertTrue(output.contains("    query (required)"));
        assertFalse(output.contains("null"));
        // The option line has no indented description; DESCRIPTION follows after a blank line.
        assertTrue(output.contains("    query (required)\n\nDESCRIPTION"));
    }

    @Test
    void pageWithNullInputSchemaRendersNameWithoutSynopsisParamsOrOptions(
        MockitoComponentManager componentManager) throws Exception
    {
        // The SDK makes a null input schema unrepresentable on a real Tool (builder and constructor reject
        // it; deserialization substitutes an empty map), so the man tool's defensive degradation can only
        // be pinned with a mock.
        McpSchema.Tool definition = mock(McpSchema.Tool.class);
        when(definition.name()).thenReturn("ping");
        when(definition.description()).thenReturn("Ping the server.");
        registerTool(componentManager, "ping", "Ping the server.", "Help", true, "EXAMPLES\n    Just pings.",
            definition);

        String output = callMan(this.manTool, "ping");

        assertTrue(output.startsWith("NAME"));
        assertTrue(output.contains("    ping - Ping the server."));
        // Synopsis is the bare name; no parameters and no OPTIONS section.
        assertTrue(output.contains("SYNOPSIS\n    ping"));
        assertFalse(output.contains("OPTIONS"));
        // The man-page prose is still appended.
        assertTrue(output.contains("Just pings."));
    }

    @Test
    void pageWithRequiredNameAbsentFromPropertiesSkipsIt(MockitoComponentManager componentManager) throws Exception
    {
        Map<String, Object> properties = Map.of("query", param(STRING, "Search terms."));
        registerTool(componentManager, QUERY_DOCUMENTS, "Search pages.", "Searches pages.", "Search & Navigation",
            true, properties, List.of("query", "ghost"), null);

        String output = callMan(this.manTool, QUERY_DOCUMENTS);

        // The declared property is rendered; the phantom required name is silently skipped.
        assertTrue(output.contains("    query (string, required)"));
        assertFalse(output.contains("ghost"));
    }

    @Test
    void referencePageRendersXWikiSyntaxGuide()
    {
        String output = callMan(this.manTool, "xwiki-syntax");

        assertTrue(output.startsWith("NAME"), output);
        assertTrue(output.contains("xwiki-syntax - "), output);
        // Body markers from the grounded XWiki 2.1 reference.
        assertTrue(output.contains("XWiki Syntax 2.1"), output);
        assertTrue(output.contains("HEADINGS"), output);
        assertTrue(output.contains("**bold**"), output);
    }

    @Test
    void catalogListsXWikiSyntaxUnderReference()
    {
        String output = callMan(this.manTool, null);

        assertTrue(output.contains("Reference"), output);
        assertTrue(output.contains("  xwiki-syntax - "), output);
    }

    @Test
    void unknownEntryListsReferencePages(MockitoComponentManager componentManager) throws Exception
    {
        registerTool(componentManager, QUERY_DOCUMENTS, "Search pages.", "Search pages and more.",
            "Search & Navigation", true, Map.of(), List.of(), null);

        McpSchema.CallToolResult result = this.manTool.execute(manRequest(Map.of(TOOL_PARAM, "nope")));

        assertTrue(result.isError());
        assertTrue(textOf(result).contains("Reference pages: xwiki-syntax."), textOf(result));
    }

    @Test
    void nonStringToolArgReturnsError()
    {
        Map<String, Object> args = Map.of(TOOL_PARAM, 42);
        McpSchema.CallToolResult result = this.manTool.execute(manRequest(args));

        assertTrue(result.isError());
        assertEquals("Error: 'tool' parameter must be a string.", textOf(result));
    }

    @Test
    void componentLookupFailureYieldsEmptyCatalogAndLogsWarning() throws Exception
    {
        ComponentManager failing = mock(ComponentManager.class);
        when(failing.getInstanceList(MCPTool.class))
            .thenThrow(new ComponentLookupException("lookup failed"));
        setComponentManager(failing);

        String output = callMan(this.manTool, null);

        assertTrue(output.startsWith("Available tools"));
        assertEquals("Failed to look up MCPTool components for the man tool: [ComponentLookupException: lookup "
            + "failed]", this.logCapture.getMessage(0));
    }

    private void setComponentManager(ComponentManager target) throws Exception
    {
        Field field = MCPManTool.class.getDeclaredField("componentManager");
        field.setAccessible(true);
        field.set(this.manTool, target);
    }
}
