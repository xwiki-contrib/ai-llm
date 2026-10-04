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
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.net.URI;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.container.Container;
import org.xwiki.container.servlet.ServletRequest;
import org.xwiki.container.servlet.ServletResponse;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.resource.ResourceReferenceHandler;
import org.xwiki.resource.ResourceType;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultMCPResource}.
 *
 * @version $Id$
 */
@OldcoreTest
@SuppressWarnings("checkstyle:ClassFanOutComplexity")
@ReferenceComponentList
class DefaultMCPResourceTest
{
    private static final String WIKI_NAME = "testwiki";

    private static final String INITIAL_WIKI = "xwiki";

    private static final String AUTHORIZATION = "Authorization";

    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    private static final String BEARER_TOKEN = "Bearer abc";

    private static final DocumentReference TEST_USER =
        new DocumentReference(INITIAL_WIKI, "XWiki", "TestUser");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultMCPResource mcpResource;

    @MockComponent
    private XWikiMCPServerManager mcpServerManager;

    @MockComponent
    private MCPServerConfiguration mcpConfig;

    @MockComponent
    private Container container;

    // Mock the presence of the OIDC resource reference handler to ensure the authentication logic is executed in the
    // tests.
    @MockComponent
    @Named("oidc")
    private ResourceReferenceHandler<ResourceType> oidcResourceReferenceHandler;

    @Mock
    private HttpServletRequest mockRequest;

    @Mock
    private HttpServletResponse mockResponse;

    @Mock
    private UriInfo mockUriInfo;

    @BeforeEach
    void setUp() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(INITIAL_WIKI);
        when(this.mcpConfig.isEnabled(WIKI_NAME)).thenReturn(true);
        when(this.mcpConfig.isCanonicalWikiId(WIKI_NAME)).thenReturn(true);
        ServletRequest servletRequest = mock();
        when(servletRequest.getRequest()).thenReturn(this.mockRequest);
        ServletResponse servletResponse = mock();
        when(servletResponse.getResponse()).thenReturn(this.mockResponse);

        when(this.container.getRequest()).thenReturn(servletRequest);
        when(this.container.getResponse()).thenReturn(servletResponse);

        // UriInfo is a JAX-RS @Context field on the parent XWikiResource; inject the mock
        // via reflection since the XWiki test framework does not process @Context annotations.
        Field uriInfoField = XWikiResource.class.getDeclaredField("uriInfo");
        uriInfoField.setAccessible(true);
        uriInfoField.set(this.mcpResource, this.mockUriInfo);
    }

    @Test
    void delegateToMcpSetsWikiAndCallsService() throws Exception
    {
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        doAnswer(invocation -> {
            // Assert the wiki was set on the XWiki context while service() is running
            assertEquals(WIKI_NAME, this.oldcore.getXWikiContext().getWikiId());
            return null;
        }).when(this.mcpServerManager).handleRequest(any(), any(), any());

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(WIKI_NAME, this.mockRequest, this.mockResponse);
    }

    @Test
    void delegateToMcpRestoresWikiAfterSuccess() throws Exception
    {
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        this.mcpResource.delegateToMcp(WIKI_NAME);

        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void delegateToMcpRestoresWikiOnServletException() throws Exception
    {
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        doThrow(new ServletException("transport error"))
            .when(this.mcpServerManager).handleRequest(any(), any(), any());

        assertThrows(XWikiRestException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void delegateToMcpRestoresWikiOnIOException() throws Exception
    {
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        doThrow(new IOException("network error"))
            .when(this.mcpServerManager).handleRequest(any(), any(), any());

        assertThrows(XWikiRestException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void delegateToMcpReturns401WhenGuestUser() throws Exception
    {
        // User reference is null (guest) - simulates no valid Bearer token
        this.oldcore.getXWikiContext().setUserReference(null);
        URI mcpUri = new URI("https://server/rest/wikis/" + WIKI_NAME + "/aiLLM/mcp");
        when(this.mockUriInfo.getAbsolutePath()).thenReturn(mcpUri);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        Response response = ex.getResponse();
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertEquals(
            "Bearer realm=\"XWiki MCP\", resource_metadata=\""
                + mcpUri + "/.well-known/oauth-protected-resource\"",
            response.getHeaderString(WWW_AUTHENTICATE)
        );
        // The MCP transport must NOT be invoked for an unauthenticated request.
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
    }

    @Test
    void delegateToMcpReturnsBare401WhenGuestUserWithoutOIDC(MockitoComponentManager mockitoComponentManager)
        throws Exception
    {
        unregisterOidcResourceHandler(mockitoComponentManager);

        // Guest access is off by default, and it is the single gate: no OIDC Provider does not open the endpoint.
        this.oldcore.getXWikiContext().setUserReference(null);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        Response response = ex.getResponse();
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        // Without a provider there is no protected-resource metadata to point at: no challenge header, and the
        // request URI is never read to build one.
        assertNull(response.getHeaderString(WWW_AUTHENTICATE));
        verify(this.mockUriInfo, never()).getAbsolutePath();
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
    }

    @Test
    void delegateToMcpServesGuestWithoutOIDCWhenGuestAccessAllowed(MockitoComponentManager mockitoComponentManager)
        throws Exception
    {
        unregisterOidcResourceHandler(mockitoComponentManager);

        when(this.mcpConfig.isGuestAccessAllowed(WIKI_NAME)).thenReturn(true);
        this.oldcore.getXWikiContext().setUserReference(null);

        doAnswer(invocation -> {
            // Assert the wiki was set on the XWiki context while service() is running
            assertEquals(WIKI_NAME, this.oldcore.getXWikiContext().getWikiId());
            return null;
        }).when(this.mcpServerManager).handleRequest(any(), any(), any());

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(WIKI_NAME, this.mockRequest, this.mockResponse);
    }

    @Test
    void delegateToMcpReturns401WithChallengeWhenGuestPresentsFailedCredentials() throws Exception
    {
        // Guest access is allowed, but the request carried a bearer token that did not authenticate: serving
        // it as guest would silently downgrade the caller, so it gets the challenge instead.
        when(this.mcpConfig.isGuestAccessAllowed(WIKI_NAME)).thenReturn(true);
        when(this.mockRequest.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        this.oldcore.getXWikiContext().setUserReference(null);
        URI mcpUri = new URI("https://server/rest/wikis/" + WIKI_NAME + "/aiLLM/mcp");
        when(this.mockUriInfo.getAbsolutePath()).thenReturn(mcpUri);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        Response response = ex.getResponse();
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertEquals(
            "Bearer realm=\"XWiki MCP\", resource_metadata=\""
                + mcpUri + "/.well-known/oauth-protected-resource\"",
            response.getHeaderString(WWW_AUTHENTICATE)
        );
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
    }

    @Test
    void delegateToMcpReturnsBare401WhenGuestPresentsFailedCredentialsWithoutOIDC(
        MockitoComponentManager mockitoComponentManager) throws Exception
    {
        unregisterOidcResourceHandler(mockitoComponentManager);

        when(this.mcpConfig.isGuestAccessAllowed(WIKI_NAME)).thenReturn(true);
        when(this.mockRequest.getHeader(AUTHORIZATION)).thenReturn("Basic d3Jvbmc6Y3JlZHM=");
        this.oldcore.getXWikiContext().setUserReference(null);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        Response response = ex.getResponse();
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertNull(response.getHeaderString(WWW_AUTHENTICATE));
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
    }

    @Test
    void delegateToMcpServesAuthenticatedUserWithoutReadingTheGuestFlag() throws Exception
    {
        when(this.mockRequest.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(WIKI_NAME, this.mockRequest, this.mockResponse);
        // The guest flag is a document read: an authenticated caller must never pay for it.
        verify(this.mcpConfig, never()).isGuestAccessAllowed(any());
    }

    @Test
    void delegateToMcpServesGuestWhenGuestAccessAllowed() throws Exception
    {
        // The OIDC Provider stays registered: with guest access enabled the endpoint must forward the
        // request anyway instead of answering with the WWW-Authenticate challenge.
        when(this.mcpConfig.isGuestAccessAllowed(WIKI_NAME)).thenReturn(true);
        this.oldcore.getXWikiContext().setUserReference(null);

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(WIKI_NAME, this.mockRequest, this.mockResponse);
    }

    @Test
    void delegateToMcpReturns404WhenGuestAccessAllowedButWikiDisabled() throws Exception
    {
        // Guest access must not reopen a wiki whose endpoint is switched off: the disabled check runs first
        // and a disabled wiki keeps looking absent.
        when(this.mcpConfig.isEnabled(WIKI_NAME)).thenReturn(false);
        when(this.mcpConfig.isGuestAccessAllowed(WIKI_NAME)).thenReturn(true);
        this.oldcore.getXWikiContext().setUserReference(null);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        assertEquals(HttpServletResponse.SC_NOT_FOUND, ex.getResponse().getStatus());
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
    }

    @Test
    void delegateToMcpReturns404WhenWikiDisabled() throws Exception
    {
        when(this.mcpConfig.isEnabled(WIKI_NAME)).thenReturn(false);
        // An authenticated user must still be refused with 404 when the wiki is disabled.
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        assertEquals(HttpServletResponse.SC_NOT_FOUND, ex.getResponse().getStatus());
        // The MCP transport must NOT be invoked and the target wiki must never be activated.
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void handlePostReturns404WhenWikiDisabled() throws Exception
    {
        when(this.mcpConfig.isEnabled(WIKI_NAME)).thenReturn(false);
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.handlePost(WIKI_NAME));

        assertEquals(HttpServletResponse.SC_NOT_FOUND, ex.getResponse().getStatus());
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void delegateToMcpReturns404ForNonCanonicalWikiId() throws Exception
    {
        // A case variant of a wiki id is not canonical: it must look absent even to an authenticated user and
        // even though the configuration read through it would report the endpoint as enabled.
        String variant = "TESTWIKI";
        when(this.mcpConfig.isCanonicalWikiId(variant)).thenReturn(false);
        when(this.mcpConfig.isEnabled(variant)).thenReturn(true);
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.delegateToMcp(variant));

        assertEquals(HttpServletResponse.SC_NOT_FOUND, ex.getResponse().getStatus());
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
        // No configuration is read through the variant id.
        verify(this.mcpConfig, never()).isEnabled(variant);
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void delegateToMcpServesGuestWithBlankAuthorizationHeaderWhenGuestAccessAllowed() throws Exception
    {
        // A whitespace-only Authorization header presents no credentials: the caller is a plain guest.
        when(this.mcpConfig.isGuestAccessAllowed(WIKI_NAME)).thenReturn(true);
        when(this.mockRequest.getHeader(AUTHORIZATION)).thenReturn("   ");
        this.oldcore.getXWikiContext().setUserReference(null);

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(WIKI_NAME, this.mockRequest, this.mockResponse);
    }

    @Test
    void delegateToMcpServesAuthenticatedUserWithoutOIDCWhenGuestAccessOff(
        MockitoComponentManager mockitoComponentManager) throws Exception
    {
        unregisterOidcResourceHandler(mockitoComponentManager);

        when(this.mcpConfig.isGuestAccessAllowed(WIKI_NAME)).thenReturn(false);
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(WIKI_NAME, this.mockRequest, this.mockResponse);
    }

    @Test
    void delegateToMcpReturns401WhenGuestPresentsCredentialsAndGuestAccessOff() throws Exception
    {
        when(this.mcpConfig.isGuestAccessAllowed(WIKI_NAME)).thenReturn(false);
        when(this.mockRequest.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        this.oldcore.getXWikiContext().setUserReference(null);
        when(this.mockUriInfo.getAbsolutePath())
            .thenReturn(new URI("https://server/rest/wikis/" + WIKI_NAME + "/aiLLM/mcp"));

        WebApplicationException ex = assertThrows(WebApplicationException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, ex.getResponse().getStatus());
        verify(this.mcpServerManager, never()).handleRequest(any(), any(), any());
    }

    @Test
    void handleOAuthMetadataReturns404ForNonCanonicalWikiId() throws Exception
    {
        String variant = "TESTWIKI";
        when(this.mcpConfig.isCanonicalWikiId(variant)).thenReturn(false);

        try (Response response = this.mcpResource.handleGetOAuthMetadata(variant)) {
            assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());
        }
        verify(this.mcpConfig, never()).isEnabled(variant);
        verify(this.mockUriInfo, never()).getAbsolutePath();
    }

    @Test
    void handleOAuthMetadataReturns404WhenWikiDisabled() throws Exception
    {
        when(this.mcpConfig.isEnabled(WIKI_NAME)).thenReturn(false);

        try (Response response = this.mcpResource.handleGetOAuthMetadata(WIKI_NAME)) {
            assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());
        }
        verify(this.mockUriInfo, never()).getAbsolutePath();
    }

    @Test
    void handleOAuthMetadata() throws Exception
    {
        URI wellKnownURI = new URI(
            "https://server/rest/wikis/%s/aiLLM/mcp/.well-known/oauth-protected-resource".formatted(WIKI_NAME));
        when(this.mockUriInfo.getAbsolutePath()).thenReturn(wellKnownURI);
        URI baseURI = new URI("https://server/rest/");
        when(this.mockUriInfo.getBaseUri()).thenReturn(baseURI);

        try (Response response = this.mcpResource.handleGetOAuthMetadata(WIKI_NAME)) {
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());

            Object entity = response.getEntity();
            assertInstanceOf(String.class, entity);

            assertEquals("{\"resource\":\"https://server/rest/wikis/testwiki/aiLLM/mcp\","
                + "\"authorization_servers\":[\"https://server/oidc\"],"
                + "\"bearer_methods_supported\":[\"header\"]}", entity);
        }
    }

    @Test
    void handleOAuthMetadataReturns404WhenOIDCIsMissing(MockitoComponentManager mockitoComponentManager)
        throws Exception
    {
        unregisterOidcResourceHandler(mockitoComponentManager);

        try (Response response = this.mcpResource.handleGetOAuthMetadata(WIKI_NAME)) {
            assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());
        }
    }

    private static void unregisterOidcResourceHandler(MockitoComponentManager mockitoComponentManager)
    {
        Type handlerType = new DefaultParameterizedType(null, ResourceReferenceHandler.class, ResourceType.class);
        mockitoComponentManager.unregisterComponent(handlerType, "oidc");
    }
}
