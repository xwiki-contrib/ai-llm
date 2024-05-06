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
package org.xwiki.contrib.llm.authentication;

import java.security.Principal;
import java.text.ParseException;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.classloader.ClassLoaderManager;
import org.xwiki.contrib.llm.authentication.internal.AuthorizedApplication;
import org.xwiki.contrib.llm.authentication.internal.AuthorizedApplicationManager;
import org.xwiki.contrib.llm.authentication.internal.ClaimValidator;
import org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorConfiguration;
import org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorUserStore;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.stability.Unstable;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.user.api.XWikiAuthService;
import com.xpn.xwiki.user.api.XWikiUser;
import com.xpn.xwiki.user.impl.xwiki.XWikiAuthServiceImpl;
import com.xpn.xwiki.web.Utils;
import com.xpn.xwiki.web.XWikiRequest;

/**
 * Authenticator based on JWT tokens that are issued by external, trusted applications.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
public class JWTTokenAuth implements XWikiAuthService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(JWTTokenAuth.class);

    private static final String PREFIX = "Bearer ";

    private final XWikiAuthService authService;

    private final JWTTokenAuthenticatorConfiguration configuration =
        Utils.getComponent(JWTTokenAuthenticatorConfiguration.class);

    private final AuthorizedApplicationManager authorizedApplicationManager =
        Utils.getComponent(AuthorizedApplicationManager.class);

    private final JWTTokenAuthenticatorUserStore userStore = Utils.getComponent(JWTTokenAuthenticatorUserStore.class);

    private final ClaimValidator claimValidator = Utils.getComponent(ClaimValidator.class);

    /**
     * Default constructor.
     */
    public JWTTokenAuth()
    {
        String authenticator = this.configuration.getAuthenticator();

        this.authService = createAuthService(authenticator);
    }

    private XWikiAuthService createAuthService(String authClass)
    {
        XWikiAuthService result;

        if (StringUtils.isNotEmpty(authClass)) {
            LOGGER.debug("Using custom AuthClass [{}].", authClass);

            try {
                // Get the current ClassLoader
                @SuppressWarnings("deprecation")
                ClassLoaderManager clManager = Utils.getComponent(ClassLoaderManager.class);
                ClassLoader classloader = null;
                if (clManager != null) {
                    classloader = clManager.getURLClassLoader("wiki:xwiki", false);
                }

                // Get the class
                if (classloader != null) {
                    result = (XWikiAuthService) Class.forName(authClass, true, classloader).getDeclaredConstructor()
                            .newInstance();
                } else {
                    result = (XWikiAuthService) Class.forName(authClass).getDeclaredConstructor().newInstance();
                }

                LOGGER.debug("Initialized AuthService using Reflection.");

                return result;
            } catch (Exception e) {
                LOGGER.warn("Failed to initialize AuthService " + authClass
                    + " using Reflection, trying default implementations using 'new'.", e);
            }
        }

        result = new XWikiAuthServiceImpl();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Initialized AuthService [{}] using 'new'.", result.getClass().getName());
        }

        return result;
    }

    @Override
    public XWikiUser checkAuth(XWikiContext context) throws XWikiException
    {
        XWikiRequest request = context.getRequest();
        String authorization = request.getHeader("Authorization");

        if (!StringUtils.isBlank(authorization) && authorization.startsWith(PREFIX)) {
            try {
                SignedJWT signedJWT = SignedJWT.parse(StringUtils.removeStart(authorization, PREFIX));
                JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
                String issuer = claims.getIssuer();
                AuthorizedApplication application = this.authorizedApplicationManager.getApplication(issuer)
                    .orElseThrow(() -> new XWikiException(XWikiException.MODULE_XWIKI_APP,
                        XWikiException.ERROR_XWIKI_ACCESS_DENIED, "Unauthorized application."));

                Ed25519Verifier verifier = new Ed25519Verifier(application.keyPair());
                if (!signedJWT.verify(verifier)) {
                    throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                        "Invalid token signature.");
                }

                this.claimValidator.validateClaims(claims, context);

                DocumentReference userReference = this.userStore.updateUser(application, claims);

                return new XWikiUser(userReference);
            } catch (ParseException | JOSEException e) {
                throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                    "Invalid token.", e);
            }
        }

        return this.authService.checkAuth(context);
    }

    @Override
    public XWikiUser checkAuth(String username, String password, String rememberme, XWikiContext context)
        throws XWikiException
    {
        return this.authService.checkAuth(username, password, rememberme, context);
    }

    @Override
    public void showLogin(XWikiContext context) throws XWikiException
    {
        this.authService.showLogin(context);
    }

    @Override
    public Principal authenticate(String username, String password, XWikiContext context) throws XWikiException
    {
        return this.authService.authenticate(username, password, context);
    }
}
