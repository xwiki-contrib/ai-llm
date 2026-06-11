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
import java.util.function.BiFunction;
import java.util.function.Consumer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * <p>Tool logic is tested in the tool-specific test classes (e.g. {@code MCPQueryDocumentsToolTest}).
 * These tests focus on the manager's lifecycle: initialise, rebuild, and dispose, and verify the correct
 * arguments are forwarded to the MCP SDK by intercepting the {@link McpServer#sync} static factory.</p>
 *
 * @version $Id$
 */
@ComponentTest
class XWikiMCPServerManagerTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @InjectMockComponents
    private XWikiMCPServerManager mcpServerManager;

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
        when(spec.immediateExecution(anyBoolean())).thenReturn(spec);
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
            // Tool handlers must run inline on the request thread (which the blocking servlet transport
            // holds anyway), so XWiki's thread-locals are available to tools without context propagation.
            verify(spec).immediateExecution(true);
            // Enabled tool registered with its exact definition.
            verify(spec).toolCall(eq(enabledDef), any());
            // toolCall called exactly once — the disabled tool is not registered.
            verify(spec, times(1)).toolCall(any(), any());
            // The disabled tool's definition is never fetched.
            verify(disabledTool, never()).getToolDefinition();
        }
    }

    @Test
    void duplicateToolNameIsSkippedAndServerStillBuilds(MockitoComponentManager componentManager)
        throws Exception
    {
        McpSchema.Tool definition = McpSchema.Tool.builder()
            .name("clashing_tool")
            .description("Two components advertise this same MCP name")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
            .build();
        for (String hint : List.of("first_contribution", "second_contribution")) {
            MCPTool tool = componentManager.registerMockComponent(MCPTool.class, hint);
            when(tool.isEnabled()).thenReturn(true);
            when(tool.getToolDefinition()).thenReturn(definition);
        }

        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            // Mirror the SDK: the second registration of the same tool name is rejected.
            when(spec.toolCall(any(), any())).thenReturn(spec)
                .thenThrow(new IllegalArgumentException("Tool with name 'clashing_tool' already exists"));
            McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(server);
            when(server.closeGracefully()).thenReturn(Mono.empty());

            assertDoesNotThrow(() -> this.mcpServerManager.rebuildServer());

            verify(spec, times(2)).toolCall(any(), any());
            verify(spec).build();
        }

        assertTrue(this.logCapture.getMessage(0).startsWith("Skipping MCP tool ["),
            this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("already exists"), this.logCapture.getMessage(0));
    }

    @Test
    void toolThrowingFromDefinitionIsSkippedAndOthersRegister(MockitoComponentManager componentManager)
        throws Exception
    {
        MCPTool broken = componentManager.registerMockComponent(MCPTool.class, "broken_tool");
        when(broken.isEnabled()).thenReturn(true);
        when(broken.getToolDefinition()).thenThrow(new IllegalStateException("definition exploded"));

        MCPTool healthy = componentManager.registerMockComponent(MCPTool.class, "healthy_tool");
        McpSchema.Tool healthyDef = McpSchema.Tool.builder()
            .name("healthy_tool")
            .description("A healthy tool")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
            .build();
        when(healthy.isEnabled()).thenReturn(true);
        when(healthy.getToolDefinition()).thenReturn(healthyDef);

        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(server);
            when(server.closeGracefully()).thenReturn(Mono.empty());

            assertDoesNotThrow(() -> this.mcpServerManager.rebuildServer());

            verify(spec).toolCall(eq(healthyDef), any());
            verify(spec).build();
        }

        assertTrue(this.logCapture.getMessage(0).contains("definition exploded"), this.logCapture.getMessage(0));
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
    void wrappedHandlerReturnsToolResultAndEmitsAuditLine(MockitoComponentManager componentManager)
        throws Exception
    {
        BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
            registerToolAndCaptureHandler(componentManager, tool -> when(tool.execute(any()))
                .thenReturn(McpSchema.CallToolResult.builder().addTextContent("fine").build()));

        McpSchema.CallToolResult result =
            handler.apply(null, new McpSchema.CallToolRequest("wrapped_tool", Map.of()));

        assertEquals("fine", ((McpSchema.TextContent) result.content().get(0)).text());
        String audit = this.logCapture.getMessage(0);
        assertTrue(audit.contains("tool=[wrapped_tool]"), audit);
        assertTrue(audit.contains("outcome=[ok]"), audit);
        // The mocked context carries no user reference, which must be audited as the guest user.
        assertTrue(audit.contains("user=[guest]"), audit);
    }

    @Test
    void wrappedHandlerAuditsErrorOutcomeForErrorResult(MockitoComponentManager componentManager)
        throws Exception
    {
        BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
            registerToolAndCaptureHandler(componentManager, tool -> when(tool.execute(any()))
                .thenReturn(McpSchema.CallToolResult.builder().addTextContent("bad input").isError(true).build()));

        handler.apply(null, new McpSchema.CallToolRequest("wrapped_tool", Map.of()));

        assertTrue(this.logCapture.getMessage(0).contains("outcome=[error]"), this.logCapture.getMessage(0));
    }

    @Test
    void wrappedHandlerNormalizesNullResult(MockitoComponentManager componentManager) throws Exception
    {
        BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
            registerToolAndCaptureHandler(componentManager, tool -> when(tool.execute(any())).thenReturn(null));

        McpSchema.CallToolResult result =
            handler.apply(null, new McpSchema.CallToolRequest("wrapped_tool", Map.of()));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(((McpSchema.TextContent) result.content().get(0)).text()
            .contains("Internal error in tool 'wrapped_tool'"));
        assertEquals("MCP tool [wrapped_tool] returned a null result", this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(1).contains("outcome=[failed]"), this.logCapture.getMessage(1));
    }

    @Test
    void wrappedHandlerNormalizesUnexpectedException(MockitoComponentManager componentManager)
        throws Exception
    {
        BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
            registerToolAndCaptureHandler(componentManager, tool -> when(tool.execute(any()))
                .thenThrow(new IllegalStateException("secret internal detail")));

        McpSchema.CallToolResult result =
            handler.apply(null, new McpSchema.CallToolRequest("wrapped_tool", Map.of()));

        assertEquals(Boolean.TRUE, result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Internal error in tool 'wrapped_tool'"), text);
        assertFalse(text.contains("secret internal detail"), "The raw exception message must not leak: " + text);

        assertEquals("MCP tool [wrapped_tool] failed with an unexpected error", this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(1).contains("outcome=[failed]"), this.logCapture.getMessage(1));
    }

    @Test
    void wrappedHandlerNormalizesLinkageError(MockitoComponentManager componentManager) throws Exception
    {
        // A tool whose extension was reloaded can fail class resolution at call time; the middleware
        // must turn that into a normal error result instead of letting it escape to the transport.
        BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
            registerToolAndCaptureHandler(componentManager, tool -> when(tool.execute(any()))
                .thenThrow(new NoClassDefFoundError("org/example/Gone")));

        McpSchema.CallToolResult result =
            handler.apply(null, new McpSchema.CallToolRequest("wrapped_tool", Map.of()));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("MCP tool [wrapped_tool] failed with an unexpected error", this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(1).contains("outcome=[failed]"), this.logCapture.getMessage(1));
    }

    /**
     * Registers a mock tool named {@code wrapped_tool}, stubs its behavior, rebuilds the server against a
     * mocked SDK builder, and returns the handler the manager registered — i.e. the middleware-wrapped
     * function, ready to be invoked directly.
     */
    private BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult>
        registerToolAndCaptureHandler(MockitoComponentManager componentManager, Consumer<MCPTool> stubbing)
        throws Exception
    {
        MCPTool tool = componentManager.registerMockComponent(MCPTool.class, "wrapped_tool");
        McpSchema.Tool definition = McpSchema.Tool.builder()
            .name("wrapped_tool")
            .description("A tool wrapped by the middleware")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
            .build();
        when(tool.isEnabled()).thenReturn(true);
        when(tool.getToolDefinition()).thenReturn(definition);
        stubbing.accept(tool);

        ArgumentCaptor<BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult>>
            handlerCaptor = ArgumentCaptor.captor();
        try (MockedStatic<McpServer> mcpServerStatic = mockStatic(McpServer.class)) {
            McpServer.StatelessSyncSpecification spec = stubSpec(mcpServerStatic);
            McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
            when(spec.build()).thenReturn(server);
            when(server.closeGracefully()).thenReturn(Mono.empty());

            this.mcpServerManager.rebuildServer();

            verify(spec).toolCall(eq(definition), handlerCaptor.capture());
        }
        return handlerCaptor.getValue();
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

        assertEquals("Failed to look up MCPTool components; no tools will be registered: "
            + "[ComponentLookupException: lookup failed]", this.logCapture.getMessage(0));
    }
}
