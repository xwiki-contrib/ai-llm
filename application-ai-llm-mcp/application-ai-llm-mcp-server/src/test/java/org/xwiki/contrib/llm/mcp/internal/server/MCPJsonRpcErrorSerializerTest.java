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
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link MCPJsonRpcErrorSerializer}, exercised through the mapper {@link MCPJsonMapperFactory} builds:
 * an internal error must reach the caller with a fixed message and no data, every other code unchanged.
 *
 * @version $Id$
 */
class MCPJsonRpcErrorSerializerTest
{
    private static final String CODE = "code";

    private static final String MESSAGE = "message";

    private static final String DATA = "data";

    private static final String INTERNAL_ERROR = "Internal error";

    private static final String UNKNOWN_TOOL = "Unknown tool: invalid_tool_name";

    @Test
    void internalErrorCarriesFixedMessageAndNoData() throws IOException
    {
        String json = serialize(new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
            "Failed to handle request: Cannot construct instance of `java.util.LinkedHashMap` (through reference "
                + "chain: io.modelcontextprotocol.spec.McpSchema$CallToolRequest[\"arguments\"])",
            "java.lang.IllegalStateException at io.modelcontextprotocol.spec.McpSchema"));
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals(Set.of(CODE, MESSAGE), keys(root));
        assertEquals(McpSchema.ErrorCodes.INTERNAL_ERROR, root.get(CODE).asInt());
        assertEquals(INTERNAL_ERROR, root.get(MESSAGE).asText());
        assertFalse(json.contains("java."), json);
        assertFalse(json.contains("McpSchema"), json);
    }

    @Test
    void otherCodesPassMessageAndDataThrough() throws IOException
    {
        JsonNode root = new ObjectMapper().readTree(serialize(new McpSchema.JSONRPCResponse.JSONRPCError(
            McpSchema.ErrorCodes.INVALID_PARAMS, UNKNOWN_TOOL, "Tool not found: x")));

        assertEquals(Set.of(CODE, MESSAGE, DATA), keys(root));
        assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, root.get(CODE).asInt());
        assertEquals(UNKNOWN_TOOL, root.get(MESSAGE).asText());
        assertEquals("Tool not found: x", root.get(DATA).asText());
    }

    @Test
    void blankMessageOfAnotherCodeFallsBackToFixedMessage() throws IOException
    {
        JsonNode root = new ObjectMapper().readTree(serialize(
            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND, " ", null)));

        assertEquals(McpSchema.ErrorCodes.METHOD_NOT_FOUND, root.get(CODE).asInt());
        assertEquals(INTERNAL_ERROR, root.get(MESSAGE).asText());
    }

    @Test
    void nullDataOfAnotherCodeIsOmitted() throws IOException
    {
        JsonNode root = new ObjectMapper().readTree(serialize(
            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS, UNKNOWN_TOOL, null)));

        assertEquals(Set.of(CODE, MESSAGE), keys(root));
        assertEquals(UNKNOWN_TOOL, root.get(MESSAGE).asText());
    }

    private static String serialize(McpSchema.JSONRPCResponse.JSONRPCError error) throws IOException
    {
        return MCPJsonMapperFactory.create().writeValueAsString(error);
    }

    private static Set<String> keys(JsonNode node)
    {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
