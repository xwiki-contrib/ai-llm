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
package org.xwiki.contrib.llm.mcp.internal.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPJsonMapperFactory}: the mapper it builds must still serialize regular MCP messages, and
 * the SDK transport configured with it must answer a rejected or failed request with a JSON-RPC error body that
 * carries no exception text.
 *
 * @version $Id$
 */
class MCPJsonMapperFactoryTest
{
    private static final String ERROR = "error";

    private static final String CODE = "code";

    private static final String STACK_TRACE = "stackTrace";

    private static final String ENDPOINT = "/mcp";

    private static final String ID = "id";

    private static final String MESSAGE = "message";

    private static final String INTERNAL_ERROR = "Internal error";

    private static final String JAVA_PACKAGE = "java.";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.ERROR);

    @Test
    void regularMessagesStillSerializeNormally() throws IOException
    {
        McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse("2.0", "1", null,
            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_REQUEST, "bad", null));

        String json = MCPJsonMapperFactory.create().writeValueAsString(response);
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals("1", root.get(ID).asText());
        assertEquals(McpSchema.ErrorCodes.INVALID_REQUEST, root.get(ERROR).get(CODE).asInt());
        assertEquals("bad", root.get(ERROR).get(MESSAGE).asText());
        assertFalse(json.contains(STACK_TRACE));
    }

    @Test
    void internalErrorOfARegularResponseCarriesFixedMessage() throws IOException
    {
        McpSchema.JSONRPCResponse response = McpSchema.JSONRPCResponse.error(7,
            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
                "Cannot construct instance of `io.modelcontextprotocol.spec.McpSchema$InitializeRequest`", null));

        String json = MCPJsonMapperFactory.create().writeValueAsString(response);
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals(Set.of("jsonrpc", ID, ERROR), keys(root));
        assertEquals("2.0", root.get("jsonrpc").asText());
        assertEquals(7, root.get(ID).asInt());
        assertEquals(Set.of(CODE, MESSAGE), keys(root.get(ERROR)));
        assertEquals(McpSchema.ErrorCodes.INTERNAL_ERROR, root.get(ERROR).get(CODE).asInt());
        assertEquals(INTERNAL_ERROR, root.get(ERROR).get(MESSAGE).asText());
        assertFalse(json.contains("McpSchema"), json);
    }

    @Test
    void transportErrorBodyIsJsonRpcError() throws ServletException, IOException
    {
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
            .jsonMapper(MCPJsonMapperFactory.create())
            .messageEndpoint(ENDPOINT)
            .build();

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/xwiki/mcp" + ENDPOINT);
        when(request.getContentLengthLong()).thenReturn(2L);
        when(request.getContentType()).thenReturn("text/plain");
        when(request.getHeader("Accept")).thenReturn("application/json, text/event-stream");

        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        transport.service(request, response);

        verify(response).setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        String json = body.toString();
        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals(Set.of("jsonrpc", ID, ERROR), keys(root));
        assertTrue(root.get(ID).isNull());
        assertEquals(McpSchema.ErrorCodes.INVALID_REQUEST, root.get(ERROR).get(CODE).asInt());
        assertFalse(json.contains(STACK_TRACE));
    }

    @Test
    void transportHandlerFailureBodyCarriesNoExceptionText() throws ServletException, IOException
    {
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
            .jsonMapper(MCPJsonMapperFactory.create())
            .messageEndpoint(ENDPOINT)
            .build();
        McpSchema.Tool definition = McpSchema.Tool.builder("probe", Map.of("type", "object")).build();
        McpStatelessSyncServer server = McpServer.sync(transport)
            .serverInfo("test", "1.0")
            .immediateExecution(true)
            .toolCall(definition, (ctx, req) -> McpSchema.CallToolResult.builder().addTextContent("ok").build())
            .build();

        // The arguments of a tool call must be a JSON object: a string makes the SDK fail while it converts the
        // request parameters, and it reports that failure with the exception's message.
        byte[] payload = ("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"probe\",\"arguments\":\"not an object\"}}").getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream source = new ByteArrayInputStream(payload);
        ServletInputStream input = mock(ServletInputStream.class);
        when(input.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(
            invocation -> source.read(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/xwiki/mcp" + ENDPOINT);
        when(request.getContentLengthLong()).thenReturn((long) payload.length);
        when(request.getContentType()).thenReturn("application/json");
        when(request.getHeader("Accept")).thenReturn("application/json, text/event-stream");
        when(request.getInputStream()).thenReturn(input);

        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        try {
            transport.service(request, response);
        } finally {
            server.close();
        }

        verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        String json = body.toString();
        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals(Set.of("jsonrpc", ID, ERROR), keys(root));
        assertEquals(McpSchema.ErrorCodes.INTERNAL_ERROR, root.get(ERROR).get(CODE).asInt());
        assertEquals(INTERNAL_ERROR, root.get(ERROR).get(MESSAGE).asText());
        assertFalse(json.contains(JAVA_PACKAGE), json);
        assertFalse(json.contains(STACK_TRACE), json);
        // The exception text stays on the server side: the SDK transport writes it to the log only.
        assertTrue(this.logCapture.getMessage(0).startsWith("Failed to handle request: Cannot construct instance"),
            this.logCapture.getMessage(0));
    }

    private static Set<String> keys(JsonNode node)
    {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
