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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPJsonMapperFactory}: the mapper it builds must still serialize regular MCP messages, and
 * the SDK transport configured with it must answer a rejected request with a JSON-RPC error body.
 *
 * @version $Id$
 */
class MCPJsonMapperFactoryTest
{
    private static final String ERROR = "error";

    private static final String CODE = "code";

    private static final String STACK_TRACE = "stackTrace";

    private static final String ENDPOINT = "/mcp";

    @Test
    void regularMessagesStillSerializeNormally() throws IOException
    {
        McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse("2.0", "1", null,
            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_REQUEST, "bad", null));

        String json = MCPJsonMapperFactory.create().writeValueAsString(response);
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals("1", root.get("id").asText());
        assertEquals(McpSchema.ErrorCodes.INVALID_REQUEST, root.get(ERROR).get(CODE).asInt());
        assertEquals("bad", root.get(ERROR).get("message").asText());
        assertFalse(json.contains(STACK_TRACE));
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
        Set<String> keys = new HashSet<>();
        root.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("jsonrpc", "id", ERROR), keys);
        assertTrue(root.get("id").isNull());
        assertEquals(McpSchema.ErrorCodes.INVALID_REQUEST, root.get(ERROR).get(CODE).asInt());
        assertFalse(json.contains(STACK_TRACE));
    }
}
