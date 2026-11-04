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
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.resource.ResourceReferenceHandler;
import org.xwiki.resource.ResourceType;
import org.xwiki.security.authservice.XWikiAuthServiceComponent;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.user.api.XWikiAuthService;

/**
 * Tells whether the MCP endpoint of a wiki may advertise the XWiki OIDC Provider as its authorization server,
 * through the RFC&nbsp;9728 protected-resource metadata and the {@code WWW-Authenticate} challenge on {@code 401}.
 * Advertising is right only when a bearer token the client obtains from the provider is actually honored, which
 * takes two things: the provider must be installed (it serves the issuer the metadata points at), and the active
 * authentication service of the wiki must be the provider's token bridge, the authenticator that reads
 * {@code Authorization: Bearer}. With the provider installed but another authenticator selected, an advertised
 * client would obtain a token and be refused with {@code 401} forever.
 *
 * @version $Id$
 * @since 1.0.1
 */
@Component(roles = OIDCProviderStatus.class)
@Singleton
public class OIDCProviderStatus
{
    /**
     * Component hint of the OIDC Provider's token bridge authentication service
     * ({@code TokenAuthenticatorComponent} in its {@code token-authenticator} jar).
     */
    static final String BRIDGE_ID = "oidc-provider-bridge";

    /**
     * Class name of the token bridge when it is configured as a legacy authenticator class through
     * {@code xwiki.authentication.authclass}. Its deprecated name {@code OIDCBridgeAuth} extends this class, so a
     * superclass walk covers both.
     */
    static final String BRIDGE_LEGACY_CLASS = "org.xwiki.contrib.oidc.token.TokenAuthService";

    private static final Type OIDC_PROVIDER_TYPE =
        new DefaultParameterizedType(null, ResourceReferenceHandler.class, ResourceType.class);

    private static final String OIDC_PROVIDER_HINT = "oidc";

    @Inject
    @Named("context")
    private ComponentManager componentManager;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    private MCPServerConfiguration mcpConfig;

    /**
     * @return whether the XWiki OIDC Provider is installed, i.e. whether its resource handler, which serves the
     *     issuer URL {@code {webapp}/oidc} named in the protected-resource metadata, is registered
     */
    public boolean isProviderInstalled()
    {
        return this.componentManager.hasComponent(OIDC_PROVIDER_TYPE, OIDC_PROVIDER_HINT);
    }

    /**
     * Tells whether bearer tokens are honored on the given wiki, i.e. whether its active authentication service
     * is the OIDC Provider's token bridge. The bridge is recognized in both forms it can take: as the
     * authentication service component with hint {@link #BRIDGE_ID}, and as a legacy class configured through
     * {@code xwiki.authentication.authclass} in {@code xwiki.cfg}, matched by class name
     * ({@link #BRIDGE_LEGACY_CLASS} or a subclass of it) so that this module needs no dependency on the OIDC jars.
     * <p>
     * The context wiki is switched to the given wiki for the duration of the lookup because the component-based
     * selection is a per-wiki setting that {@link XWiki#getAuthService()} resolves from the current context wiki
     * on every call; the {@code xwiki.cfg} override, when set, is farm-wide and wins regardless of the wiki.
     * <p>
     * An id that is not the canonical id of an existing wiki (see
     * {@link MCPServerConfiguration#isCanonicalWikiId(String)}) is refused before the context is touched: the
     * script service exposes this method to any script author with a caller-chosen id, and resolving the
     * authentication service through a non-existent or variant id makes the platform's authentication service
     * configuration log an error with a stack trace on every call (inside the farm-wide authentication service
     * lock) or pollute its per-wiki cache with a case variant that invalidation never clears. The MCP resource
     * checks canonicality first; this guard makes the component honor that invariant for every caller.
     *
     * @param wikiId the wiki whose authentication service to inspect
     * @return {@code true} when the id is canonical and the active authentication service of the wiki is the
     *     token bridge
     */
    public boolean isBearerAuthenticationActive(String wikiId)
    {
        if (!this.mcpConfig.isCanonicalWikiId(wikiId)) {
            return false;
        }
        XWikiContext xcontext = this.xcontextProvider.get();
        XWiki xwiki = xcontext.getWiki();
        if (xwiki == null) {
            return false;
        }
        String previousWiki = xcontext.getWikiId();
        try {
            xcontext.setWikiId(wikiId);
            return isTokenBridge(xwiki.getAuthService());
        } finally {
            xcontext.setWikiId(previousWiki);
        }
    }

    /**
     * @param wikiId the wiki whose MCP endpoint is considered
     * @return whether the endpoint of the wiki may advertise the OIDC Provider: the provider is installed (checked
     *     first, as it costs no context switch) and bearer authentication is active on the wiki
     */
    public boolean isAdvertisable(String wikiId)
    {
        return isProviderInstalled() && isBearerAuthenticationActive(wikiId);
    }

    private static boolean isTokenBridge(XWikiAuthService service)
    {
        if (service == null) {
            return false;
        }
        if (service instanceof XWikiAuthServiceComponent component) {
            return BRIDGE_ID.equals(component.getId());
        }
        return isLegacyBridgeClass(service.getClass());
    }

    private static boolean isLegacyBridgeClass(Class<?> serviceClass)
    {
        for (Class<?> current = serviceClass; current != null; current = current.getSuperclass()) {
            if (BRIDGE_LEGACY_CLASS.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }
}
