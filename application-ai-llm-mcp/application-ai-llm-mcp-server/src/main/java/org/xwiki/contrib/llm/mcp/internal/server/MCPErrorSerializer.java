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

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Serializes an {@link McpError} as a JSON-RPC 2.0 error response:
 * {@code {"jsonrpc":"2.0","id":null,"error":{"code":...,"message":"...","data":...}}}.
 *
 * <p>The MCP SDK's servlet transport ({@code HttpServletStatelessServerTransport#responseError}) reports some
 * failures as a JSON body: an unsupported Content-Type, a missing Accept type, a malformed or unexpected JSON-RPC
 * message, and a handler failure. For those it hands the {@link McpError} <em>exception object</em> itself to
 * the JSON mapper. {@link McpError} is a {@link RuntimeException}, so Jackson's default bean serialization emits
 * its {@code stackTrace}, {@code cause}, {@code suppressed} and {@code localizedMessage} properties: a full
 * server stack trace in the HTTP response body. Registering this serializer on the mapper the transport uses
 * restricts the body to the JSON-RPC error fields; it reads nothing but {@link McpError#getJsonRpcError()}.</p>
 *
 * <p>The {@code id} is {@code null}: the {@link McpError} carries no request id on any of those paths, including
 * the handler-failure path where the request was already parsed, so the serializer cannot echo one, and JSON-RPC
 * 2.0 prescribes {@code null} when the id cannot be determined.</p>
 *
 * <p>A blank message is replaced by a generic one: the SDK's {@code JSONRPCError} record only asserts that its
 * message is non-null.</p>
 *
 * @version $Id$
 * @since 1.0.1
 */
public final class MCPErrorSerializer extends StdSerializer<McpError>
{
    private static final long serialVersionUID = 1L;

    private static final String FALLBACK_MESSAGE = "Internal error";

    /**
     * Creates the serializer for {@link McpError}.
     */
    public MCPErrorSerializer()
    {
        super(McpError.class);
    }

    @Override
    public void serialize(McpError value, JsonGenerator gen, SerializerProvider provider) throws IOException
    {
        McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = value.getJsonRpcError();

        gen.writeStartObject();
        gen.writeStringField("jsonrpc", "2.0");
        gen.writeNullField("id");
        gen.writeObjectFieldStart("error");
        gen.writeNumberField("code", jsonRpcError.code());
        gen.writeStringField("message", StringUtils.defaultIfBlank(jsonRpcError.message(), FALLBACK_MESSAGE));
        if (jsonRpcError.data() != null) {
            provider.defaultSerializeField("data", jsonRpcError.data(), gen);
        }
        gen.writeEndObject();
        gen.writeEndObject();
    }
}
