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
import org.mockito.MockedStatic;
import org.xwiki.context.Execution;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link XWikiMCPServerManager}.
 *
 * <p>Tool logic is tested in the tool-specific test classes (e.g. {@code MCPSearchCollectionsToolTest}).
 * These tests focus on the manager's lifecycle: initialise, rebuild, and dispose, and verify the correct
 * arguments are forwarded to the MCP SDK by intercepting the {@link McpServer#sync} static factory.</p>
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

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Stubs {@link McpServer#sync} to return a mock specification whose builder methods return themselves.
     * The caller still needs to stub {@code spec.build()} to return the desired server mock(s).
     */
    private static McpServer.StatelessSyncSpecification stubSpec(MockedStatic<McpServer> mcpServerStatic)
    {
        McpServer.StatelessSyncSpecification spec = mock(McpServer.StatelessSyncSpecification.class);
        when(spec.serverInfo(anyString(), anyString())).thenReturn(spec);
        when(spec.instructions(any())).thenReturn(spec);
        when(spec.capabilities(any())).thenReturn(spec);
        when(spec.toolCall(any(), any())).thenReturn(spec);
        mcpServerStatic.when(() -> McpServer.sync(any(McpStatelessServerTransport.class))).thenReturn(spec);
        return spec;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void rebuildServerRegistersEnabledToolAndIgnoresDisabledTool(MockitoComponentManager componentManager)
        throws Exception
    {
        MCPTool enabledTool = componentManager.registerMockComponent(MCPTool.class, "enabled_tool");
        MCPTool disabledTool = componentManager.registerMockComponent(MCPTool.class, "disabled_tool");

        McpSchema.Tool enabledDef = McpSchema.Tool.builder()
            .name("enabled_tool")
            .description("An enabled tool")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
            .build();

        when(enabledTool.isEnabled()).thenReturn(true);
        when(enabledTool.getToolDefinition()).thenReturn(enabledDef);
        when(disabledTool.isEnabled()).thenReturn(false);

        when(this.mcpConfig.getServerName()).thenReturn("MyXWiki");
        when(this.mcpConfig.getServerDescription()).thenReturn("My Wiki Instructions");

        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer builtServer = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(builtServer);
            when(builtServer.closeGracefully()).thenReturn(Mono.empty());

            this.mcpServerManager.rebuildServer();

            // Server metadata forwarded correctly to the SDK.
            verify(spec).serverInfo(eq("MyXWiki"), anyString());
            verify(spec).instructions("My Wiki Instructions");
            // Enabled tool registered with its exact definition.
            verify(spec).toolCall(eq(enabledDef), any());
            // toolCall called exactly once — the disabled tool is not registered.
            verify(spec, times(1)).toolCall(any(), any());
            // The disabled tool's definition is never fetched.
            verify(disabledTool, never()).getToolDefinition();
        }
    }

    @Test
    void rebuildServerDefaultsBlankServerName()
    {
        when(this.mcpConfig.getServerName()).thenReturn("   ");

        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer builtServer = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(builtServer);
            when(builtServer.closeGracefully()).thenReturn(Mono.empty());

            this.mcpServerManager.rebuildServer();

            verify(spec).serverInfo(eq(MCPServerConfiguration.DEFAULT_SERVER_NAME), anyString());
        }
    }

    @Test
    void rebuildCanBeCalledMultipleTimesWithoutResourceLeak()
    {
        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer firstServer = mock(McpStatelessSyncServer.class);
            McpStatelessSyncServer secondServer = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(firstServer).thenReturn(secondServer);
            when(firstServer.closeGracefully()).thenReturn(Mono.empty());
            when(secondServer.closeGracefully()).thenReturn(Mono.empty());

            this.mcpServerManager.rebuildServer();
            this.mcpServerManager.rebuildServer();

            // The first server must be closed when the second rebuild replaces it.
            verify(firstServer).closeGracefully();
        }
    }

    @Test
    void disposeDoesNotThrow()
    {
        assertDoesNotThrow(() -> this.mcpServerManager.dispose());
    }
}
