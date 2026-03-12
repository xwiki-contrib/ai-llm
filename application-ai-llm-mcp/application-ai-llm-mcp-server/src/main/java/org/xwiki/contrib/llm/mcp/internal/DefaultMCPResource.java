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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.xwiki.component.annotation.Component;
import org.xwiki.container.Container;
import org.xwiki.container.Request;
import org.xwiki.container.Response;
import org.xwiki.container.servlet.ServletRequest;
import org.xwiki.container.servlet.ServletResponse;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.contrib.llm.mcp.MCPResource;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;

import com.xpn.xwiki.XWikiContext;

/**
 * Default implementation of {@link MCPResource}. Bridges XWiki's JAX-RS infrastructure to the MCP
 * {@link io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport} by:
 * <ol>
 *   <li>Setting the current wiki in the {@link XWikiContext} thread-local so that the
 *       {@link org.xwiki.contrib.llm.CollectionManager} resolves collections from the right wiki.</li>
 *   <li>Delegating to {@link io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport}
 *       which reads the MCP JSON-RPC request, invokes the registered tool handler, and writes the response.</li>
 * </ol>
 *
 * @version $Id$
 * @since 0.8
 */
@Component
@Named("org.xwiki.contrib.llm.mcp.internal.DefaultMCPResource")
public class DefaultMCPResource extends XWikiResource implements MCPResource
{
    @Inject
    private XWikiMCPServerManager mcpServerManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Container container;

    @Inject
    private Execution execution;

    @Override
    public void handlePost(String wikiName) throws XWikiRestException
    {
        delegateToMcp(wikiName);
    }

    @Override
    public void handleGet(String wikiName) throws XWikiRestException
    {
        delegateToMcp(wikiName);
    }

    @Override
    public void handleDelete(String wikiName) throws XWikiRestException
    {
        delegateToMcp(wikiName);
    }

    /**
     * Sets the wiki in the {@link XWikiContext}, delegates to the MCP transport, then restores
     * the original wiki. Using a try-finally guarantees restoration even if the transport throws.
     *
     * @param wikiName the wiki to activate for the duration of this request
     * @throws XWikiRestException wrapping any {@link jakarta.servlet.ServletException} or
     *     {@link java.io.IOException} raised by the transport
     */
    void delegateToMcp(String wikiName) throws XWikiRestException
    {
        XWikiContext xcontext = this.contextProvider.get();
        String previousWiki = xcontext.getWikiId();
        Request request = this.container.getRequest();
        Response response = this.container.getResponse();
        if (request instanceof ServletRequest servletRequest && response instanceof ServletResponse servletResponse) {
            HttpServletRequest jakartaRequest = servletRequest.getRequest();
            HttpServletResponse jakartaResponse = servletResponse.getResponse();
            ExecutionContext executionContext = this.execution.getContext();
            if (executionContext != null) {
                executionContext.setProperty(XWikiMCPServerManager.MCP_CONTEXT_PROPAGATION_PROPERTY, Boolean.TRUE);
            }
            try {
                xcontext.setWikiId(wikiName);
                this.mcpServerManager.handleRequest(jakartaRequest, jakartaResponse);
            } catch (Exception e) {
                throw new XWikiRestException("Failed to handle MCP request for wiki [" + wikiName + "]", e);
            } finally {
                xcontext.setWikiId(previousWiki);
                if (executionContext != null) {
                    executionContext.removeProperty(XWikiMCPServerManager.MCP_CONTEXT_PROPAGATION_PROPERTY);
                }
            }
        } else {
            throw new XWikiRestException("Unsupported request type: " + request.getClass().getName());
        }
    }
}
