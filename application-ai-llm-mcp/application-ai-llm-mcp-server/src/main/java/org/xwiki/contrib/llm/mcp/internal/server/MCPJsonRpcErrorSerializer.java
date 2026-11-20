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

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Serializes the SDK's JSON-RPC error object as {@code {"code":...,"message":"...","data":...}}, with the message
 * of an internal error replaced by a fixed text and its data left out.
 *
 * <p>The MCP SDK fills the message of an internal error ({@link McpSchema.ErrorCodes#INTERNAL_ERROR}) with
 * exception text: Java type names and Jackson reference chains of the server's classes. For that code the
 * serializer writes a fixed message and no {@code data}. The other codes carry a fixed text or an echo of the
 * caller's own input, so their message and data pass through; a blank message is replaced by the same fixed
 * text.</p>
 *
 * <p>The serializer applies wherever the mapper meets the error object: as the {@code error} property of a
 * {@code JSONRPCResponse} and as the error {@link MCPErrorSerializer} delegates to it.</p>
 *
 * <p>A custom serializer replaces the record's own omission of absent properties, hence the explicit check that
 * leaves a {@code null} data out.</p>
 *
 * @version $Id$
 * @since 1.0.1
 */
public final class MCPJsonRpcErrorSerializer extends StdSerializer<McpSchema.JSONRPCResponse.JSONRPCError>
{
    private static final long serialVersionUID = 1L;

    /**
     * The message written for an internal error and in place of a blank message.
     */
    private static final String INTERNAL_ERROR_MESSAGE = "Internal error";

    /**
     * Creates the serializer for the SDK's JSON-RPC error object.
     */
    public MCPJsonRpcErrorSerializer()
    {
        super(McpSchema.JSONRPCResponse.JSONRPCError.class);
    }

    @Override
    public void serialize(McpSchema.JSONRPCResponse.JSONRPCError value, JsonGenerator gen,
        SerializerProvider provider) throws IOException
    {
        boolean internal = value.code() == McpSchema.ErrorCodes.INTERNAL_ERROR;

        gen.writeStartObject();
        gen.writeNumberField("code", value.code());
        gen.writeStringField("message",
            internal ? INTERNAL_ERROR_MESSAGE : StringUtils.defaultIfBlank(value.message(), INTERNAL_ERROR_MESSAGE));
        if (!internal && value.data() != null) {
            provider.defaultSerializeField("data", value.data(), gen);
        }
        gen.writeEndObject();
    }
}
