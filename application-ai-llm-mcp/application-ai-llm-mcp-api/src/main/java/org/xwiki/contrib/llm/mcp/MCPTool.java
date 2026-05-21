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
package org.xwiki.contrib.llm.mcp;

import org.xwiki.component.annotation.Role;
import org.xwiki.stability.Unstable;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Extension point for contributing tools to the XWiki MCP server.
 *
 * <p>Each implementation is a XWiki component with a hint equal to the tool's stable {@link #getId() ID}.
 * The MCP server collects all registered implementations, filters by {@link #isEnabled()}, and registers
 * the enabled ones with the MCP SDK at startup and after each configuration change.</p>
 *
 * <p>Implementations live in the module that owns the tool's logic. For example, hybrid-search tools
 * live in the index module and are contributed when that module is installed. The MCP server itself
 * has no compile-time knowledge of them.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Role
@Unstable
public interface MCPTool
{
    /**
     * Returns the MCP tool definition (name, description, input schema) to advertise to agents.
     *
     * @return the MCP tool definition
     */
    McpSchema.Tool getToolDefinition();

    /**
     * Returns {@code true} if this tool should currently be registered with the MCP server.
     * Disabled tools are not registered and are invisible to agents.
     * Override to consult tool-specific configuration; the default always returns {@code true}.
     *
     * @return {@code true} if the tool is enabled
     */
    default boolean isEnabled()
    {
        return true;
    }

    /**
     * Executes the tool call and returns the result.
     *
     * @param request the tool call request
     * @return the tool call result
     */
    McpSchema.CallToolResult execute(McpSchema.CallToolRequest request);
}
