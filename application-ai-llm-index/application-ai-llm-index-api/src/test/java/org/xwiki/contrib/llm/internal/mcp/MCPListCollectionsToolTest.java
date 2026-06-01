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
package org.xwiki.contrib.llm.internal.mcp;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPListCollectionsTool}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPListCollectionsToolTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPListCollectionsTool tool;

    @MockComponent
    private CollectionManager collectionManager;

    @Test
    void executeReturnsAccessibleCollections() throws IndexException
    {
        List<String> allCollections = List.of("col1", "col2", "col3");
        List<String> accessible = List.of("col1", "col3");
        when(this.collectionManager.getCollections()).thenReturn(allCollections);
        when(this.collectionManager.filterCollectionbasedOnUserAccess(allCollections)).thenReturn(accessible);

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("list_collections", null);

        McpSchema.CallToolResult result = this.tool.execute(request);

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertEquals("col1\ncol3", text);
    }

    @Test
    void executeReturnsErrorOnIndexException() throws IndexException
    {
        when(this.collectionManager.getCollections()).thenThrow(new IndexException("Solr down"));

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("list_collections", null);

        McpSchema.CallToolResult result = this.tool.execute(request);

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(((McpSchema.TextContent) result.content().get(0)).text().contains("Solr down"));
        assertEquals("MCP list_collections tool failed", this.logCapture.getMessage(0));
    }

    @Test
    void executeReturnsEmptyStringForNoCollections() throws IndexException
    {
        when(this.collectionManager.getCollections()).thenReturn(List.of());
        when(this.collectionManager.filterCollectionbasedOnUserAccess(List.of())).thenReturn(List.of());

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("list_collections", null);

        McpSchema.CallToolResult result = this.tool.execute(request);

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("No collections found.", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void isEnabledReturnsTrueByDefault()
    {
        assertTrue(this.tool.isEnabled());
    }

    @Test
    void exposesCategoryAndSummary()
    {
        assertEquals("Semantic Search", this.tool.getCategory());
        assertEquals("List the indexed collections available for semantic search.", this.tool.getSummary());
    }
}
