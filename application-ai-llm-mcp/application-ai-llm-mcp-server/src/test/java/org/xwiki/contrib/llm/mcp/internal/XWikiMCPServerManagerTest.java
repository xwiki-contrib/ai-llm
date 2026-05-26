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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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

    /**
     * Captures the Reactor scheduler hook registered by {@link XWikiMCPServerManager#initialize()} so the
     * context-propagation logic inside it can be exercised directly. {@link Schedulers} is mocked only to
     * intercept the registration; {@link McpServer} is mocked so the rebuild triggered by initialise uses a
     * mock server rather than building a real one.
     */
    private Function<Runnable, Runnable> captureSchedulerHook()
    {
        ArgumentCaptor<Function<Runnable, Runnable>> hookCaptor = ArgumentCaptor.captor();
        try (MockedStatic<Schedulers> schedulersStatic = mockStatic(Schedulers.class);
            MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(server);
            when(server.closeGracefully()).thenReturn(Mono.empty());

            this.mcpServerManager.initialize();

            schedulersStatic.verify(() -> Schedulers.onScheduleHook(anyString(), hookCaptor.capture()));
        }
        return hookCaptor.getValue();
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

    @Test
    void disposeClosesServerGracefully()
    {
        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(server);
            when(server.closeGracefully()).thenReturn(Mono.empty());

            this.mcpServerManager.rebuildServer();
            this.mcpServerManager.dispose();

            verify(server).closeGracefully();
        }
    }

    @Test
    void disposeLogsWarningWhenCloseGracefullyFails()
    {
        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(server);
            when(server.closeGracefully()).thenReturn(Mono.error(new RuntimeException("close failed")));

            this.mcpServerManager.rebuildServer();
            assertDoesNotThrow(() -> this.mcpServerManager.dispose());
        }

        assertEquals("Failed to close MCP server gracefully: RuntimeException: close failed",
            this.logCapture.getMessage(0));
    }

    @Test
    void rebuildLogsWarningWhenOldServerCloseGracefullyFails()
    {
        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer firstServer = mock(McpStatelessSyncServer.class);
            McpStatelessSyncServer secondServer = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(firstServer).thenReturn(secondServer);
            when(firstServer.closeGracefully()).thenReturn(Mono.error(new RuntimeException("close failed")));
            when(secondServer.closeGracefully()).thenReturn(Mono.empty());

            this.mcpServerManager.rebuildServer();
            assertDoesNotThrow(() -> this.mcpServerManager.rebuildServer());
        }

        assertEquals("Failed to close MCP server gracefully during rebuild: RuntimeException: close failed",
            this.logCapture.getMessage(0));
    }

    @Test
    void handleRequestDelegatesToCurrentTransport() throws Exception
    {
        HttpServletStatelessServerTransport transport = mock(HttpServletStatelessServerTransport.class);
        Field transportField = XWikiMCPServerManager.class.getDeclaredField("transport");
        transportField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<HttpServletStatelessServerTransport> transportRef =
            (AtomicReference<HttpServletStatelessServerTransport>) transportField.get(this.mcpServerManager);
        transportRef.set(transport);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        this.mcpServerManager.handleRequest(request, response);

        verify(transport).service(request, response);
    }

    @Test
    void schedulerHookPropagatesContextWhenPropagationPropertyPresent()
    {
        Function<Runnable, Runnable> hook = captureSchedulerHook();

        ExecutionContext context = mock(ExecutionContext.class);
        when(context.hasProperty(XWikiMCPServerManager.MCP_CONTEXT_PROPAGATION_PROPERTY)).thenReturn(true);
        when(this.execution.getContext()).thenReturn(context);

        Runnable original = mock(Runnable.class);
        hook.apply(original).run();

        // The wrapped runnable must push the captured context, run the original task, then pop the context.
        InOrder inOrder = inOrder(this.execution, original);
        inOrder.verify(this.execution).pushContext(context, false);
        inOrder.verify(original).run();
        inOrder.verify(this.execution).popContext();
    }

    @Test
    void schedulerHookLeavesRunnableUnchangedWhenNoPropagationProperty()
    {
        Function<Runnable, Runnable> hook = captureSchedulerHook();

        // No execution context at all: the hook must return the runnable untouched.
        when(this.execution.getContext()).thenReturn(null);

        Runnable original = mock(Runnable.class);

        assertSame(original, hook.apply(original));
    }

    @Test
    void rebuildLogsWarningWhenToolLookupFails() throws Exception
    {
        ComponentManager failingComponentManager = mock(ComponentManager.class);
        when(failingComponentManager.getInstanceList(MCPTool.class))
            .thenThrow(new ComponentLookupException("lookup failed"));
        Field componentManagerField = XWikiMCPServerManager.class.getDeclaredField("componentManager");
        componentManagerField.setAccessible(true);
        componentManagerField.set(this.mcpServerManager, failingComponentManager);

        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(server);
            when(server.closeGracefully()).thenReturn(Mono.empty());

            // The server must still be built (with no tools) even though tool lookup failed.
            assertDoesNotThrow(() -> this.mcpServerManager.rebuildServer());
            verify(spec, never()).toolCall(any(), any());
        }

        assertEquals("Failed to look up MCPTool components; no tools will be registered",
            this.logCapture.getMessage(0));
    }
}
