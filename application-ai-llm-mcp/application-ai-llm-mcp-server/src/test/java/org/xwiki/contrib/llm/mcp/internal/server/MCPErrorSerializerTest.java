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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MCPErrorSerializer}, exercised through the mapper {@link MCPJsonMapperFactory} builds: an
 * {@link McpError} must serialize as a JSON-RPC error response, never as an exception bean.
 *
 * @version $Id$
 */
class MCPErrorSerializerTest
{
    private static final String JSONRPC = "jsonrpc";

    private static final String ID = "id";

    private static final String ERROR = "error";

    private static final String CODE = "code";

    private static final String MESSAGE = "message";

    private static final String DATA = "data";

    private static final String UNSUPPORTED = "Unsupported Media Type";

    @Test
    void errorSerializesAsJsonRpcErrorWithoutStackTrace() throws IOException
    {
        McpError error = McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message(UNSUPPORTED).build();

        String json = MCPJsonMapperFactory.create().writeValueAsString(error);
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals(Set.of(JSONRPC, ID, ERROR), keys(root));
        assertEquals("2.0", root.get(JSONRPC).asText());
        assertTrue(root.get(ID).isNull());
        assertEquals(Set.of(CODE, MESSAGE), keys(root.get(ERROR)));
        assertEquals(McpSchema.ErrorCodes.INVALID_REQUEST, root.get(ERROR).get(CODE).asInt());
        assertEquals(UNSUPPORTED, root.get(ERROR).get(MESSAGE).asText());
        assertFalse(json.contains("stackTrace"));
    }

    @Test
    void errorDataIsForwarded() throws IOException
    {
        McpError error = McpError.builder(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND)
            .message("Resource not found")
            .data(Map.of("uri", "x"))
            .build();

        JsonNode root = new ObjectMapper().readTree(MCPJsonMapperFactory.create().writeValueAsString(error));

        assertEquals(Set.of(CODE, MESSAGE, DATA), keys(root.get(ERROR)));
        assertEquals("x", root.get(ERROR).get(DATA).get("uri").asText());
    }

    @Test
    void blankMessageFallsBackToGenericMessage() throws IOException
    {
        McpError error = new McpError(
            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR, " ", null));

        JsonNode root = new ObjectMapper().readTree(MCPJsonMapperFactory.create().writeValueAsString(error));

        assertEquals(McpSchema.ErrorCodes.INTERNAL_ERROR, root.get(ERROR).get(CODE).asInt());
        assertEquals("Internal error", root.get(ERROR).get(MESSAGE).asText());
    }

    private static Set<String> keys(JsonNode node)
    {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
