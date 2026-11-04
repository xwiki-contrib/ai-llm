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

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.contrib.oidc.provider.OIDCBridgeAuth;
import org.xwiki.contrib.oidc.token.TokenAuthService;
import org.xwiki.resource.ResourceReferenceHandler;
import org.xwiki.resource.ResourceType;
import org.xwiki.security.authservice.XWikiAuthServiceComponent;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.user.api.XWikiAuthService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OIDCProviderStatus}.
 *
 * @version $Id$
 */
@OldcoreTest
class OIDCProviderStatusTest
{
    private static final String INITIAL_WIKI = "xwiki";

    private static final String WIKI_NAME = "sub";

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private OIDCProviderStatus status;

    // The OIDC Provider's resource handler: its registration is what "installed" means.
    @MockComponent
    @Named("oidc")
    private ResourceReferenceHandler<ResourceType> oidcResourceReferenceHandler;

    @MockComponent
    private MCPServerConfiguration mcpConfig;

    @BeforeEach
    void setUp()
    {
        this.oldcore.getXWikiContext().setWikiId(INITIAL_WIKI);
        when(this.mcpConfig.isCanonicalWikiId(WIKI_NAME)).thenReturn(true);
    }

    @Test
    void isProviderInstalledWhenResourceHandlerIsRegistered()
    {
        assertTrue(this.status.isProviderInstalled());
    }

    @Test
    void isProviderInstalledWhenResourceHandlerIsMissing(MockitoComponentManager mockitoComponentManager)
    {
        unregisterOidcResourceHandler(mockitoComponentManager);

        assertFalse(this.status.isProviderInstalled());
    }

    @Test
    void isBearerAuthenticationActiveWithBridgeComponent()
    {
        this.oldcore.getSpyXWiki().setAuthService(bridgeComponent());

        assertTrue(this.status.isBearerAuthenticationActive(WIKI_NAME));
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isBearerAuthenticationActiveWithOtherComponent()
    {
        XWikiAuthServiceComponent standard = mock();
        when(standard.getId()).thenReturn("standard");
        this.oldcore.getSpyXWiki().setAuthService(standard);

        assertFalse(this.status.isBearerAuthenticationActive(WIKI_NAME));
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isBearerAuthenticationActiveWithLegacyBridgeClass()
    {
        this.oldcore.getSpyXWiki().setAuthService(new TokenAuthService());

        assertTrue(this.status.isBearerAuthenticationActive(WIKI_NAME));
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isBearerAuthenticationActiveWithDeprecatedLegacyBridgeSubclass()
    {
        this.oldcore.getSpyXWiki().setAuthService(new OIDCBridgeAuth());

        assertTrue(this.status.isBearerAuthenticationActive(WIKI_NAME));
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isBearerAuthenticationActiveWithPlainLegacyService()
    {
        // The default MockitoOldcore wiring: a plain XWikiAuthService mock, neither component nor bridge class.
        assertFalse(this.status.isBearerAuthenticationActive(WIKI_NAME));
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isBearerAuthenticationActiveWithoutAuthService()
    {
        this.oldcore.getSpyXWiki().setAuthService(null);
        // The spy would otherwise initialize a real authenticator from the (absent) configuration.
        doReturn(null).when(this.oldcore.getSpyXWiki()).getAuthService();

        assertFalse(this.status.isBearerAuthenticationActive(WIKI_NAME));
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isBearerAuthenticationActiveWithoutXWiki()
    {
        this.oldcore.getXWikiContext().setWiki(null);

        assertFalse(this.status.isBearerAuthenticationActive(WIKI_NAME));
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isBearerAuthenticationActiveRefusesNonCanonicalWikiId()
    {
        String variant = "SUB";
        when(this.mcpConfig.isCanonicalWikiId(variant)).thenReturn(false);
        this.oldcore.getSpyXWiki().setAuthService(bridgeComponent());

        assertFalse(this.status.isBearerAuthenticationActive(variant));
        // The authentication service is never resolved through a non-existent or variant id, and the context is
        // never switched to it.
        verify(this.oldcore.getSpyXWiki(), never()).getAuthService();
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isBearerAuthenticationActiveResolvesTheServiceInTheRequestedWiki()
    {
        XWikiAuthServiceComponent bridge = bridgeComponent();
        doAnswer(invocation -> {
            // The per-wiki component selection is resolved from the context wiki, so it must be the requested
            // one while the service is looked up.
            assertEquals(WIKI_NAME, this.oldcore.getXWikiContext().getWikiId());
            return bridge;
        }).when(this.oldcore.getSpyXWiki()).getAuthService();

        assertTrue(this.status.isBearerAuthenticationActive(WIKI_NAME));
        verify(this.oldcore.getSpyXWiki()).getAuthService();
        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isAdvertisableWhenInstalledAndBridgeActive()
    {
        this.oldcore.getSpyXWiki().setAuthService(bridgeComponent());

        assertTrue(this.status.isAdvertisable(WIKI_NAME));
    }

    @Test
    void isAdvertisableWhenInstalledAndStandardActive()
    {
        XWikiAuthServiceComponent standard = mock();
        when(standard.getId()).thenReturn("standard");
        this.oldcore.getSpyXWiki().setAuthService(standard);

        assertFalse(this.status.isAdvertisable(WIKI_NAME));
    }

    @Test
    void isAdvertisableWhenNotInstalledAndBridgeActive(MockitoComponentManager mockitoComponentManager)
    {
        unregisterOidcResourceHandler(mockitoComponentManager);
        this.oldcore.getSpyXWiki().setAuthService(bridgeComponent());

        assertFalse(this.status.isAdvertisable(WIKI_NAME));
        // The installed check comes first and short-circuits: no authentication service is resolved.
        verify(this.oldcore.getSpyXWiki(), never()).getAuthService();
    }

    private static XWikiAuthServiceComponent bridgeComponent()
    {
        XWikiAuthServiceComponent bridge = mock();
        when(bridge.getId()).thenReturn(OIDCProviderStatus.BRIDGE_ID);
        return bridge;
    }

    private static void unregisterOidcResourceHandler(MockitoComponentManager mockitoComponentManager)
    {
        Type handlerType = new DefaultParameterizedType(null, ResourceReferenceHandler.class, ResourceType.class);
        mockitoComponentManager.unregisterComponent(handlerType, "oidc");
    }
}
