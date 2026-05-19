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

import io.modelcontextprotocol.common.McpTransportContext;
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
    public String getId()
    {
        return TOOL_ID;
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder()
            .name(TOOL_ID)
            .description("List all collections available for searching.")
            .inputSchema(new McpSchema.JsonSchema(OBJECT, Map.of(), List.of(), null, null, null))
            .build();
    }

    @Override
    public boolean isEnabled()
    {
        // TODO (Phase 5): consult MCPToolConfig XObject for this tool ID.
        return true;
    }

    @Override
    public McpSchema.CallToolResult execute(McpTransportContext context, McpSchema.CallToolRequest request)
    {
        try {
            List<String> allCollections = this.collectionManager.getCollections();
            List<String> accessibleCollections =
                this.collectionManager.filterCollectionbasedOnUserAccess(allCollections);
            return McpSchema.CallToolResult.builder()
                .addTextContent(String.join("\n", accessibleCollections))
                .build();
        } catch (IndexException e) {
            this.logger.error("MCP list_collections tool failed", e);
            return McpSchema.CallToolResult.builder()
                .addTextContent("Error listing collections: " + ExceptionUtils.getRootCauseMessage(e))
                .isError(true)
                .build();
        }
    }
}
