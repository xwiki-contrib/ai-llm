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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.context.Execution;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link XWikiMCPServerManager}.
 *
 * @version $Id$
 */
@ComponentTest
class XWikiMCPServerManagerTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private XWikiMCPServerManager mcpServerManager;

    @MockComponent
    private CollectionManager collectionManager;

    @MockComponent
    private Execution execution;

    @Test
    void handleSearchToolCallsHybridSearchWithDefaultLimits() throws IndexException
    {
        List<String> allCollections = List.of("col1", "col2");
        List<Context> results = List.of(
            new Context("col1", "doc1", "https://wiki.example.com/doc1", "Some content", 0.95, null));
        when(this.collectionManager.getCollections()).thenReturn(allCollections);
        when(this.collectionManager.hybridSearch("test query", allCollections, 10, 10))
            .thenReturn(results);

        McpSchema.CallToolRequest request =
            new McpSchema.CallToolRequest("search_wiki", Map.of("query", "test query"));

        McpSchema.CallToolResult result =
            this.mcpServerManager.handleSearchTool(McpTransportContext.EMPTY, request);

        assertNotEquals(Boolean.TRUE, result.isError());
        assertNotNull(result.content());
        assertFalse(result.content().isEmpty());
        verify(this.collectionManager).hybridSearch("test query", allCollections, 10, 10);
    }

    @Test
    void handleSearchToolPassesCollectionsAndCustomLimits() throws IndexException
    {
        when(this.collectionManager.hybridSearch(any(), any(), anyInt(), anyInt()))
            .thenReturn(Collections.emptyList());

        Map<String, Object> args = Map.of(
            "query", "my query",
            "collections", List.of("collA", "collB"),
            "limitKeywordResults", 5,
            "limitSemanticResults", 3
        );
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_wiki", args);

        this.mcpServerManager.handleSearchTool(McpTransportContext.EMPTY, request);

        verify(this.collectionManager).hybridSearch("my query", List.of("collA", "collB"), 3, 5);
    }

    @Test
    void handleSearchToolReturnsErrorForMissingQuery()
    {
        McpSchema.CallToolRequest request =
            new McpSchema.CallToolRequest("search_wiki", Collections.emptyMap());

        McpSchema.CallToolResult result =
            this.mcpServerManager.handleSearchTool(McpTransportContext.EMPTY, request);

        assertEquals(Boolean.TRUE, result.isError());
    }

    @Test
    void handleSearchToolReturnsErrorOnIndexException() throws IndexException
    {
        when(this.collectionManager.hybridSearch(any(), any(), anyInt(), anyInt()))
            .thenThrow(new IndexException("Solr unavailable"));

        McpSchema.CallToolRequest request =
            new McpSchema.CallToolRequest("search_wiki", Map.of("query", "failing query"));

        McpSchema.CallToolResult result =
            this.mcpServerManager.handleSearchTool(McpTransportContext.EMPTY, request);

        assertEquals(Boolean.TRUE, result.isError());
        assertFalse(result.content().isEmpty());
        assertTrue(((McpSchema.TextContent) result.content().get(0)).text().contains("Solr unavailable"));
        assertEquals("MCP search_wiki tool failed for query [failing query]", this.logCapture.getMessage(0));
    }

    @Test
    void handleSearchToolFormatsResultsWithUrlAndScore() throws IndexException
    {
        List<Context> results = List.of(
            new Context("col1", "doc1", "https://wiki.example.com/doc1", "First content", 0.95, null),
            new Context("col1", "doc2", null, "Second content", null, null)
        );
        when(this.collectionManager.hybridSearch(any(), any(), anyInt(), anyInt()))
            .thenReturn(results);

        McpSchema.CallToolRequest request =
            new McpSchema.CallToolRequest("search_wiki", Map.of("query", "search"));

        McpSchema.CallToolResult result =
            this.mcpServerManager.handleSearchTool(McpTransportContext.EMPTY, request);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertEquals("""
            <result>
            <url>https://wiki.example.com/doc1</url>
            <documentId>doc1</documentId>
            <content>
            First content
            </content>
            </result>
            <result>
            <documentId>doc2</documentId>
            <content>
            Second content
            </content>
            </result>""", text);
    }

    @Test
    void handleSearchToolReturnsNoResultsMessage() throws IndexException
    {
        when(this.collectionManager.hybridSearch(any(), any(), anyInt(), anyInt()))
            .thenReturn(Collections.emptyList());

        McpSchema.CallToolRequest request =
            new McpSchema.CallToolRequest("search_wiki", Map.of("query", "obscure"));

        McpSchema.CallToolResult result =
            this.mcpServerManager.handleSearchTool(McpTransportContext.EMPTY, request);

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("No relevant content found"));
    }
}
