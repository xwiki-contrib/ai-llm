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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;

/**
 * Builds the JSON mapper handed to the MCP servlet transport.
 *
 * <p>The mapper is a plain Jackson {@link ObjectMapper} with {@link MCPErrorSerializer} registered for
 * {@link McpError}, so the transport's error responses carry only the JSON-RPC error fields instead of the
 * exception's default bean serialization (stack trace included). The construction lives in its own class rather
 * than inline in {@code XWikiMCPServerManager#buildServer} because that method is private and the transport does
 * not expose the mapper it was given: a standalone factory is what lets a test exercise the mapper's behaviour
 * without standing up a server, and it keeps the Jackson configuration out of the composition root.</p>
 *
 * @version $Id$
 * @since 1.0.1
 */
public final class MCPJsonMapperFactory
{
    private MCPJsonMapperFactory()
    {
    }

    /**
     * Creates the JSON mapper for the MCP transport, with the {@link McpError} serializer registered.
     *
     * @return a new mapper instance
     */
    public static JacksonMcpJsonMapper create()
    {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new SimpleModule().addSerializer(McpError.class, new MCPErrorSerializer()));
        return new JacksonMcpJsonMapper(objectMapper);
    }
}
