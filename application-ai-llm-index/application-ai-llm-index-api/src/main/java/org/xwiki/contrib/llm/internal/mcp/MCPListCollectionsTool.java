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
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.mcp.MCPTool;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that lists all indexed collections available for searching.
 * Contributed by the index module when installed alongside the MCP server;
 * the MCP server has no compile-time knowledge of this class.
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPListCollectionsTool.TOOL_ID)
@Singleton
public class MCPListCollectionsTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint and as the config object key.
     */
    public static final String TOOL_ID = "list_collections";

    private static final String OBJECT = "object";

    @Inject
    private Logger logger;

    @Inject
    private CollectionManager collectionManager;

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID,
                Map.of("type", OBJECT, "properties", Map.of(), "required", List.of()))
            .description("List the indexed collections you can search with search_collections.")
            .build();
    }

    @Override
    public String getCategory()
    {
        return "Semantic Search";
    }

    @Override
    public String getSummary()
    {
        return "List the indexed collections available for semantic search.";
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        try {
            List<String> allCollections = this.collectionManager.getCollections();
            List<String> accessibleCollections =
                this.collectionManager.filterCollectionbasedOnUserAccess(allCollections);
            String content = accessibleCollections.isEmpty()
                ? "No collections found."
                : String.join("\n", accessibleCollections);
            return McpSchema.CallToolResult.builder()
                .addTextContent(content)
                .build();
        } catch (IndexException e) {
            // Keep the root cause in the logs, off the wire.
            this.logger.warn("MCP list_collections tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP list_collections tool failure details", e);
            return McpSchema.CallToolResult.builder()
                .addTextContent("Failed to list collections. Try again; if it persists, report it to a wiki "
                    + "administrator (details are in the server logs).")
                .isError(true)
                .build();
        }
    }
}
