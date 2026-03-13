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
package org.xwiki.contrib.llm.mcp;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.xwiki.rest.XWikiRestException;
import org.xwiki.stability.Unstable;

/**
 * REST resource that bridges the MCP (Model Context Protocol) Streamable HTTP transport into XWiki's JAX-RS
 * infrastructure. Clients interact with this resource using the standard MCP Streamable HTTP protocol.
 *
 * <p>Authentication is advertised via the OAuth 2.0 Protected Resource Metadata endpoint
 * ({@code /.well-known/oauth-protected-resource}, RFC 9728). Unauthenticated requests to the MCP
 * endpoints receive a {@code 401 Unauthorized} response whose {@code WWW-Authenticate} header
 * points to that metadata document so that MCP clients can discover the XWiki OIDC Provider as the
 * required authorization server.</p>
 *
 * @version $Id$
 * @since 0.8
 */
@Unstable
@Path("/wikis/{wikiName}/aiLLM/mcp")
public interface MCPResource
{
    /**
     * RFC 9728 OAuth 2.0 Protected Resource Metadata endpoint. Returns a JSON document that
     * identifies this MCP endpoint as a protected resource and lists the XWiki OIDC Provider as
     * the authorization server. MCP clients use this document to discover where to obtain an
     * access token.
     *
     * @param wikiName the wiki for which metadata is returned
     * @return a {@code 200 OK} response with {@code application/json} body
     * @throws XWikiRestException if the response cannot be written
     */
    @GET
    @Path(".well-known/oauth-protected-resource")
    @Produces(MediaType.APPLICATION_JSON)
    Response handleGetOAuthMetadata(@PathParam("wikiName") String wikiName) throws XWikiRestException;

    /**
     * Handles MCP POST requests: tool calls, initialization handshakes, and all client-to-server messages.
     *
     * @param wikiName the wiki whose knowledge base to search
     * @throws XWikiRestException if the request cannot be processed
     */
    @POST
    void handlePost(@PathParam("wikiName") String wikiName) throws XWikiRestException;

    /**
     * Handles MCP GET requests: SSE streams for server-initiated messages and endpoint discovery.
     *
     * @param wikiName the wiki whose knowledge base to search
     * @throws XWikiRestException if the request cannot be processed
     */
    @GET
    void handleGet(@PathParam("wikiName") String wikiName) throws XWikiRestException;

    /**
     * Handles MCP DELETE requests: session termination.
     *
     * @param wikiName the wiki whose knowledge base to search
     * @throws XWikiRestException if the request cannot be processed
     */
    @DELETE
    void handleDelete(@PathParam("wikiName") String wikiName) throws XWikiRestException;
}
