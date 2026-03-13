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

    private static final DocumentReference TEST_USER =
        new DocumentReference(INITIAL_WIKI, "XWiki", "TestUser");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultMCPResource mcpResource;

    @MockComponent
    private XWikiMCPServerManager mcpServerManager;

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
        }).when(this.mcpServerManager).handleRequest(any(), any());

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(this.mockRequest, this.mockResponse);
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
            .when(this.mcpServerManager).handleRequest(any(), any());

        assertThrows(XWikiRestException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void delegateToMcpRestoresWikiOnIOException() throws Exception
    {
        this.oldcore.getXWikiContext().setUserReference(TEST_USER);

        doThrow(new IOException("network error"))
            .when(this.mcpServerManager).handleRequest(any(), any());

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
            response.getHeaderString("WWW-Authenticate")
        );
        // The MCP transport must NOT be invoked for an unauthenticated request.
        verify(this.mcpServerManager, never()).handleRequest(any(), any());
    }

    @Test
    void delegateToMcpSetsWikiAndCallsServiceWhenGuestUserWithoutOIDC(MockitoComponentManager mockitoComponentManager)
        throws Exception
    {
        unregisterOidcResourceHandler(mockitoComponentManager);

        this.oldcore.getXWikiContext().setUserReference(null);

        doAnswer(invocation -> {
            // Assert the wiki was set on the XWiki context while service() is running
            assertEquals(WIKI_NAME, this.oldcore.getXWikiContext().getWikiId());
            return null;
        }).when(this.mcpServerManager).handleRequest(any(), any());

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(this.mockRequest, this.mockResponse);
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
