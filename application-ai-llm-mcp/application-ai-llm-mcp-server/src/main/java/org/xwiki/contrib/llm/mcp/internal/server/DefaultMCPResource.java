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

import java.lang.reflect.Type;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.container.Container;
import org.xwiki.container.Request;
import org.xwiki.container.servlet.ServletRequest;
import org.xwiki.container.servlet.ServletResponse;
import org.xwiki.contrib.llm.mcp.MCPResource;
import org.xwiki.resource.ResourceReferenceHandler;
import org.xwiki.resource.ResourceType;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xpn.xwiki.XWikiContext;

/**
 * Default implementation of {@link MCPResource}. Bridges XWiki's JAX-RS infrastructure to the MCP
 * {@link io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport} by:
 * <ol>
 *   <li>Advertising the XWiki OIDC Provider as the required authorization server via the RFC&nbsp;9728
 *       {@code /.well-known/oauth-protected-resource} endpoint and via a {@code WWW-Authenticate}
 *       header on {@code 401} responses if the OIDC Provider is available.</li>
 *   <li>Refusing an unauthenticated (guest) caller with {@code 401} unless the wiki has enabled guest access,
 *       and refusing in every case a guest request that carries an {@code Authorization} header, since the
 *       credentials it presented did not authenticate. The rule is the same with or without the OIDC Provider;
 *       only the shape of the {@code 401} differs. Credential validation itself is performed upstream by
 *       XWiki's authentication filter; this resource only needs to inspect the result.</li>
 *   <li>Setting the current wiki in the {@link XWikiContext} so that the
 *       {@link org.xwiki.contrib.llm.CollectionManager} resolves collections from the right wiki.</li>
 *   <li>Delegating to {@link io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport}
 *       which reads the MCP JSON-RPC request, invokes the registered tool handler, and writes the
 *       response.</li>
 * </ol>
 *
 * <h2>Authentication flow with OIDC Provider</h2>
 * <p>The flow below applies when the wiki's {@code allowGuest} configuration flag is off, which is the
 * default. With it on, step 3 is skipped for a request that carries no {@code Authorization} header: it is
 * forwarded to the transport as the guest user, and the space filter and XWiki's view rights decide what the
 * caller may read. A request whose bearer token is expired or invalid still gets the {@code 401} of step 3
 * even then, so the client refreshes its token or restarts the flow instead of being silently served as
 * guest. Without an OIDC Provider the same rule applies, but the {@code 401} carries no
 * {@code WWW-Authenticate} header because there is no protected-resource metadata to point at.</p>
 * <ol>
 *   <li>An unauthenticated client POSTs to {@code /rest/wikis/{wiki}/aiLLM/mcp}.</li>
 *   <li>XWiki's authentication filter runs {@code OIDCBridgeAuth.checkAuth()}, which reads the
 *       {@code Authorization: Bearer} header. With no valid token the user remains guest
 *       ({@code getUserReference() == null}).</li>
 *   <li>This resource detects the guest user and returns {@code 401 Unauthorized} with:<br>
 *       {@code WWW-Authenticate: Bearer realm="XWiki MCP",
 *       resource_metadata="{mcpBase}/.well-known/oauth-protected-resource"}</li>
 *   <li>The client GETs the metadata URL to discover the XWiki OIDC Provider endpoints.</li>
 *   <li>The client performs the OAuth 2.0 Authorization Code flow and obtains a bearer token.</li>
 *   <li>The client re-sends the MCP request with {@code Authorization: Bearer {token}}.
 *       The OIDC provider validates the token and sets the authenticated user; this resource
 *       then forwards to the MCP transport.</li>
 * </ol>
 *
 * @version $Id$
 * @since 0.8
 */
@Component
@Named("org.xwiki.contrib.llm.mcp.internal.server.DefaultMCPResource")
public class DefaultMCPResource extends XWikiResource implements MCPResource
{
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    private static final String AUTHORIZATION = "Authorization";

    private static final String WELL_KNOWN_PATH = "/.well-known/oauth-protected-resource";

    private static final String UNSUPPORTED_REQUEST_TYPE = "Unsupported request type: ";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Type OIDC_PROVIDER_TYPE =
        new DefaultParameterizedType(null, ResourceReferenceHandler.class, ResourceType.class);

    @Inject
    private XWikiMCPServerManager mcpServerManager;

    @Inject
    private MCPServerConfiguration mcpConfig;

    @Inject
    private Container container;

    @Override
    public Response handleGetOAuthMetadata(String wikiName) throws XWikiRestException
    {
        if (!hasOIDCProvider()) {
            // If there's no OIDC Provider, there's no point in advertising the protected resource metadata.
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // The metadata describes the MCP endpoint of the wiki, so it is absent exactly when that endpoint is:
        // for an id that is not the canonical id of an existing wiki and for a wiki whose endpoint is disabled.
        if (!this.mcpConfig.isCanonicalWikiId(wikiName) || !this.mcpConfig.isEnabled(wikiName)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        // Extract the base URL of the MCP endpoint from the metadata URL.
        String metadataUrl = this.uriInfo.getAbsolutePath().toString();
        String mcpBaseUrl = StringUtils.removeEnd(metadataUrl, WELL_KNOWN_PATH);

        // Produce RFC 9728 Protected Resource Metadata JSON.
        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        metadata.put("resource", mcpBaseUrl);
        metadata.putArray("authorization_servers").add(buildOidcIssuerUrl());
        metadata.putArray("bearer_methods_supported").add("header");
        try {
            return Response.ok(OBJECT_MAPPER.writeValueAsString(metadata)).type("application/json").build();
        } catch (JsonProcessingException e) {
            throw new XWikiRestException("Failed to serialize OAuth protected resource metadata", e);
        }
    }

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
     * Sets the wiki in the {@link XWikiContext}, applies the guest gate, delegates to the MCP
     * transport, then restores the original wiki. A {@code try-finally} guarantees restoration
     * even if the transport throws.
     *
     * <p>XWiki's authentication filter has already tried to authenticate the caller from the
     * request's credentials before this method is reached. If the user is still the guest user
     * ({@code getUserReference() == null}), the request is refused with {@code 401 Unauthorized}
     * in two cases:</p>
     * <ul>
     *   <li>the wiki does not allow guest access
     *       ({@link MCPServerConfiguration#isGuestAccessAllowed(String)}), which is the default;</li>
     *   <li>the request carries a non-blank {@code Authorization} header: credentials were presented
     *       but did not authenticate (expired or invalid bearer token, wrong Basic credentials).
     *       Serving the call as guest would silently downgrade the caller, and a client only
     *       refreshes its token or starts the OAuth flow on a {@code 401}.</li>
     * </ul>
     *
     * <p>Otherwise the guest request is forwarded to the transport, and the space filter together
     * with XWiki's view rights limit the result to what a guest may see anyway. The rule does not
     * depend on the OIDC Provider; its presence only decides whether the {@code 401} carries the
     * {@code WWW-Authenticate} challenge (see {@link #unauthorizedException()}).</p>
     *
     * <p>Before any of that, a {@code {wiki}} path value that is not the canonical id of an existing wiki
     * ({@link MCPServerConfiguration#isCanonicalWikiId(String)}) is answered with {@code 404}, like a
     * disabled endpoint. A case variant of the main wiki id would otherwise be served from the main wiki's
     * database under a different document-cache key, where configuration changes are not seen.</p>
     *
     * @param wikiName the wiki to activate for the duration of this request
     * @throws XWikiRestException wrapping any {@link jakarta.servlet.ServletException} or
     *     {@link java.io.IOException} raised by the transport
     */
    void delegateToMcp(String wikiName) throws XWikiRestException
    {
        // The canonical check comes first so no configuration is read through a variant wiki id.
        if (!this.mcpConfig.isCanonicalWikiId(wikiName) || !this.mcpConfig.isEnabled(wikiName)) {
            throw notFoundException();
        }

        XWikiContext xcontext = this.xcontextProvider.get();
        String previousWiki = xcontext.getWikiId();

        Request request = this.container.getRequest();
        org.xwiki.container.Response response = this.container.getResponse();
        if (request instanceof ServletRequest servletRequest && response instanceof ServletResponse servletResponse) {
            HttpServletRequest jakartaRequest = servletRequest.getRequest();
            HttpServletResponse jakartaResponse = servletResponse.getResponse();

            // XWiki's auth filter has already run. A caller that is still guest is refused unless the
            // wiki opted into serving guests, in which case the request goes through and the space
            // filter and view rights decide what the guest actually gets. A guest request that carries
            // an Authorization header is refused even then: the credentials it presented did not
            // authenticate, so serving it as guest would silently downgrade the caller, and a client
            // only refreshes its token or starts the OAuth flow on a 401. The user check comes first
            // so the guest flag (a document read) is never read for an authenticated caller.
            if (xcontext.getUserReference() == null
                && (StringUtils.isNotBlank(jakartaRequest.getHeader(AUTHORIZATION))
                    || !this.mcpConfig.isGuestAccessAllowed(wikiName)))
            {
                throw unauthorizedException();
            }

            // --- Delegate to MCP transport ---
            try {
                xcontext.setWikiId(wikiName);
                this.mcpServerManager.handleRequest(wikiName, jakartaRequest, jakartaResponse);
            } catch (Exception e) {
                throw new XWikiRestException("Failed to handle MCP request for wiki [" + wikiName + "]", e);
            } finally {
                xcontext.setWikiId(previousWiki);
            }
        } else {
            throw new XWikiRestException(UNSUPPORTED_REQUEST_TYPE + request.getClass().getName());
        }
    }

    /**
     * Builds a {@code 401 Unauthorized} {@link WebApplicationException}. With an OIDC Provider the
     * response carries a {@code WWW-Authenticate} header pointing to the RFC&nbsp;9728
     * protected-resource metadata document. Without one there is no metadata document to point at,
     * so the response is a bare {@code 401} with no challenge, like the one the XWiki REST
     * resources answer with. Throwing this exception (rather than writing to the raw servlet
     * response) lets JAX-RS set the HTTP status correctly; writing directly to
     * {@link HttpServletResponse} would be overridden by JAX-RS with {@code 204 No Content} for
     * {@code void}-returning methods.
     *
     * @return the exception to throw
     */
    private WebApplicationException unauthorizedException()
    {
        if (!hasOIDCProvider()) {
            return new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED).build());
        }
        String metadataUrl = this.uriInfo.getAbsolutePath() + WELL_KNOWN_PATH;
        return new WebApplicationException(
            Response.status(Response.Status.UNAUTHORIZED)
                .header(WWW_AUTHENTICATE,
                    "Bearer realm=\"XWiki MCP\", resource_metadata=\"" + metadataUrl + "\"")
                .build()
        );
    }

    /**
     * Builds a {@code 404 Not Found} {@link WebApplicationException}. Returned when the MCP endpoint is
     * disabled for the target wiki, or when the requested wiki id is not the canonical id of an existing wiki,
     * so such an endpoint looks absent regardless of authentication.
     *
     * @return the exception to throw
     */
    private WebApplicationException notFoundException()
    {
        return new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * Returns the XWiki OIDC Provider issuer URL by navigating one segment up from the JAX-RS
     * REST base URI and appending {@code oidc}.
     *
     * <p>The REST base URI is {@code {webapp}/rest/}. Resolving {@code ../oidc} against it
     * (per RFC&nbsp;3986 relative reference resolution) yields {@code {webapp}/oidc}, which is
     * exactly the issuer used by {@code OIDCManager.createBaseEndPointURI()}.</p>
     *
     * @return the OIDC issuer URL
     */
    private String buildOidcIssuerUrl()
    {
        return this.uriInfo.getBaseUri().resolve("../oidc").toString();
    }

    private boolean hasOIDCProvider()
    {
        return this.componentManager.hasComponent(OIDC_PROVIDER_TYPE, "oidc");
    }
}
