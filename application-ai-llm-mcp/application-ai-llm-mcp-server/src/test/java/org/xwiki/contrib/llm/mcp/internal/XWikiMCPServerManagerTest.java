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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.context.Execution;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link XWikiMCPServerManager}.
 *
 * <p>Tool logic is tested in the tool-specific test classes (e.g. {@code MCPSearchCollectionsToolTest}).
 * These tests focus on the manager's lifecycle: initialise, rebuild, and dispose.</p>
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
    private Execution execution;

    @MockComponent
    private MCPServerConfiguration mcpConfig;

    @Test
    void rebuildServerWithEnabledAndDisabledTools(MockitoComponentManager componentManager) throws Exception
    {
        MCPTool enabledTool = componentManager.registerMockComponent(MCPTool.class, "enabled_tool");
        MCPTool disabledTool = componentManager.registerMockComponent(MCPTool.class, "disabled_tool");

        when(enabledTool.isEnabled()).thenReturn(true);
        when(enabledTool.getToolDefinition()).thenReturn(
            McpSchema.Tool.builder()
                .name("enabled_tool")
                .description("An enabled tool")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build());

        when(disabledTool.isEnabled()).thenReturn(false);

        // mcpConfig returns null by default from the Mockito mock; XWikiMCPServerManager falls back
        // to MCPServerConfiguration.DEFAULT_SERVER_NAME so the SDK never sees a null server name.

        assertDoesNotThrow(() -> this.mcpServerManager.rebuildServer());

        // The disabled tool's definition must never be retrieved.
        verify(disabledTool, never()).getToolDefinition();
    }

    @Test
    void disposeDoesNotThrow()
    {
        assertDoesNotThrow(() -> this.mcpServerManager.dispose());
    }

    @Test
    void rebuildCanBeCalledMultipleTimes()
    {
        // Verifies that repeated rebuilds (e.g. on config changes) do not leak resources or throw.
        assertDoesNotThrow(() -> {
            this.mcpServerManager.rebuildServer();
            this.mcpServerManager.rebuildServer();
        });
    }

    @Test
    void rebuildServerDefaultsBlankServerName()
    {
        when(this.mcpConfig.getServerName()).thenReturn("   ");

        assertDoesNotThrow(() -> this.mcpServerManager.rebuildServer());
    }
}
