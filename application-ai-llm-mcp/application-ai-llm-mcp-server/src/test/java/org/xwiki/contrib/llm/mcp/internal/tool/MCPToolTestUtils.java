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
package org.xwiki.contrib.llm.mcp.internal.tool;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Static plumbing shared by the MCP tool tests: building a call request and reading a result's text.
 *
 * @version $Id$
 */
public final class MCPToolTestUtils
{
    private MCPToolTestUtils()
    {
    }

    /**
     * Reads the text of the first content block of a tool result.
     *
     * @param result the tool result
     * @return the text of the first content block
     */
    public static String textOf(McpSchema.CallToolResult result)
    {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    /**
     * Builds a call request for the named tool with the given arguments.
     *
     * @param name the tool name
     * @param args the call arguments
     * @return the call request
     */
    public static McpSchema.CallToolRequest request(String name, Map<String, Object> args)
    {
        return McpSchema.CallToolRequest.builder(name).arguments(args).build();
    }
}
