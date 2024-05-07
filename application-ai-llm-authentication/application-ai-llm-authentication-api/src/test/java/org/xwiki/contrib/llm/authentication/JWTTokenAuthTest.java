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

import java.lang.reflect.Field;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.classloader.ClassLoaderManager;
import org.xwiki.classloader.NamespaceURLClassLoader;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.contrib.llm.authentication.internal.AuthorizedApplication;
import org.xwiki.contrib.llm.authentication.internal.AuthorizedApplicationManager;
import org.xwiki.contrib.llm.authentication.internal.ClaimValidator;
import org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorConfiguration;
import org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorUserStore;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.user.api.XWikiAuthService;
import com.xpn.xwiki.user.api.XWikiUser;
import com.xpn.xwiki.web.Utils;
import com.xpn.xwiki.web.XWikiRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JWTTokenAuth}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList(ClaimValidator.class)
class JWTTokenAuthTest
{
    private static final String USERNAME = "username";

    private static final String PASSWORD = "password";

    private static final String NAMESPACE = "wiki:xwiki";

    private static final String ISSUER = "issuer";

    private static final String AUTHORIZATION = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String APPLICATION_NAME = "Test Application";

    private static final String AUDIENCE = "https://localhost/";

    private record TestCase(JWTClaimsSet claimsSet, String expectedErrorMessage)
    {
    }

    @Mock
    private XWikiRequest request;

    @Mock
    private XWikiContext context;

    @MockComponent
    private JWTTokenAuthenticatorConfiguration configuration;

    @MockComponent
    private AuthorizedApplicationManager authorizedApplicationManager;

    @MockComponent
    private JWTTokenAuthenticatorUserStore userStore;

    @MockComponent
    private ClassLoaderManager classLoaderManager;

    public static final class TestAuthService implements XWikiAuthService
    {
        private final XWikiAuthService authService;

        /**
         * Default constructor.
         */
        TestAuthService()
        {
            this.authService = mock();
        }

        public XWikiAuthService getMock()
        {
            return this.authService;
        }

        @Override
        public XWikiUser checkAuth(XWikiContext context) throws XWikiException
        {
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

    @BeforeEach
    void setUp(MockitoComponentManager componentManager)
    {
        Utils.setComponentManager(componentManager);
        when(this.configuration.getAuthenticator()).thenReturn(TestAuthService.class.getName());
        when(this.context.getRequest()).thenReturn(this.request);

        // Mock the request URL
        when(this.request.getScheme()).thenReturn("https");
        when(this.request.getServerName()).thenReturn("localhost");
        when(this.request.getServerPort()).thenReturn(-1);
    }

    @ParameterizedTest
    @ValueSource(strings = { " ", "Bearer", "Basic " })
    @NullAndEmptySource
    void checkAuthWithoutAuthorizationHeader(String authorizationHeader) throws XWikiException, NoSuchFieldException,
        IllegalAccessException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        when(this.request.getHeader(AUTHORIZATION)).thenReturn(authorizationHeader);
        XWikiUser user = mock();
        XWikiAuthService authService = getAuthService(jwtTokenAuth);
        when(authService.checkAuth(this.context)).thenReturn(user);
        assertSame(user, jwtTokenAuth.checkAuth(this.context));
        verify(authService).checkAuth(this.context);
    }

    @Test
    void checkAuthWithUnauthorizedApplication()
        throws XWikiException, NoSuchFieldException, IllegalAccessException, JOSEException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        OctetKeyPair jwk = generateKeyPair();
        when(this.request.getHeader(AUTHORIZATION)).thenReturn(BEARER_PREFIX + getToken(jwk));
        when(this.authorizedApplicationManager.getApplication(ISSUER)).thenReturn(Optional.empty());
        XWikiAuthService authService = getAuthService(jwtTokenAuth);
        XWikiException exception = assertThrows(XWikiException.class, () -> jwtTokenAuth.checkAuth(this.context));
        assertEquals("Error number 9001 in 11: Unauthorized application.", exception.getMessage());
        verify(authService, never()).checkAuth(this.context);
    }

    @Test
    void checkAuthWithDifferentSignature()
        throws XWikiException, NoSuchFieldException, IllegalAccessException, JOSEException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        OctetKeyPair headerKey = generateKeyPair();
        when(this.request.getHeader(AUTHORIZATION)).thenReturn(BEARER_PREFIX + getToken(headerKey));
        OctetKeyPair applicationKey = generateKeyPair().toPublicJWK();
        AuthorizedApplication application = new AuthorizedApplication(mock(), ISSUER, APPLICATION_NAME, "",
            applicationKey);
        when(this.authorizedApplicationManager.getApplication(ISSUER)).thenReturn(Optional.of(application));
        XWikiAuthService authService = getAuthService(jwtTokenAuth);
        XWikiException exception = assertThrows(XWikiException.class, () -> jwtTokenAuth.checkAuth(this.context));
        assertEquals("Error number 9001 in 11: Invalid token signature.", exception.getMessage());
        verify(authService, never()).checkAuth(this.context);
    }

    private static Stream<TestCase> provideTestCases()
    {
        return Stream.of(
            new TestCase(
                getJwtClaimsSet(System.currentTimeMillis() - 1000), "Error number 9001 in 11: Token expired."
            ),
            new TestCase(getJwtClaimsSet(System.currentTimeMillis() + 1000 * 60 * 60 * 26),
                "Error number 9001 in 11: The token must not be valid for more than 24 hours."
            ),
            new TestCase(new JWTClaimsSet.Builder(getJwtClaimsSet())
                .issueTime(Date.from(Instant.now().plusSeconds(3600)))
                .build(), "Error number 9001 in 11: Token issued in the future."
            ),
            new TestCase(
                new JWTClaimsSet.Builder(getJwtClaimsSet())
                    .audience("https://www.example.com")
                    .build(),
                "Error number 9001 in 11: The wiki's URL [https://localhost/] is not in the provided audience"
            ),
            new TestCase(
                new JWTClaimsSet.Builder(getJwtClaimsSet())
                    .notBeforeTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build(),
                "Error number 9001 in 11: Token isn't valid yet."
            ),
            new TestCase(
                new JWTClaimsSet.Builder(getJwtClaimsSet())
                    .expirationTime(null)
                    .build(),
                "Error number 9001 in 11: No expiration time specified."
            ),
            new TestCase(
                new JWTClaimsSet.Builder(getJwtClaimsSet())
                    .issueTime(Date.from(Instant.now().minus(Duration.ofHours(26))))
                    .build(),
                "Error number 9001 in 11: The token is more than 24 hours old."
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideTestCases")
    void checkAuthWithInvalidClaims(TestCase testCase)
        throws XWikiException, NoSuchFieldException, IllegalAccessException, JOSEException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        OctetKeyPair keyPair = generateKeyPair();
        // Generate a token that expires in the past
        when(this.request.getHeader(AUTHORIZATION)).thenReturn(BEARER_PREFIX + getToken(testCase.claimsSet, keyPair));
        AuthorizedApplication application = new AuthorizedApplication(mock(), ISSUER, APPLICATION_NAME, "",
            keyPair.toPublicJWK());
        when(this.authorizedApplicationManager.getApplication(ISSUER)).thenReturn(Optional.of(application));
        XWikiAuthService authService = getAuthService(jwtTokenAuth);
        XWikiException exception = assertThrows(XWikiException.class, () -> jwtTokenAuth.checkAuth(this.context));
        assertEquals(testCase.expectedErrorMessage, exception.getMessage());
        verify(authService, never()).checkAuth(this.context);
    }

    @Test
    void checkAuthWithValidToken() throws XWikiException, JOSEException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        OctetKeyPair keyPair = generateKeyPair();
        JWTClaimsSet claims = getJwtClaimsSet();
        when(this.request.getHeader(AUTHORIZATION)).thenReturn(BEARER_PREFIX + getToken(claims, keyPair));
        AuthorizedApplication application = new AuthorizedApplication(mock(), ISSUER, APPLICATION_NAME, "",
            keyPair.toPublicJWK());
        when(this.authorizedApplicationManager.getApplication(ISSUER)).thenReturn(Optional.of(application));
        DocumentReference userReference = new DocumentReference("xwiki", "XWiki", "User");
        when(this.userStore.updateUser(same(application), any())).thenReturn(userReference);
        assertEquals(new XWikiUser(userReference), jwtTokenAuth.checkAuth(this.context));
        // Use an argument captor to verify the claims passed to updateUser
        ArgumentCaptor<JWTClaimsSet> claimsCaptor = ArgumentCaptor.forClass(JWTClaimsSet.class);
        verify(this.userStore).updateUser(same(application), claimsCaptor.capture());
        // For some reason, the JWTClaimsSet.equals method does not work as expected, so we compare the JSON objects.
        // This is most likely because we lose precision on the date field.
        assertEquals(claims.toJSONObject(), claimsCaptor.getValue().toJSONObject());
    }

    private static OctetKeyPair generateKeyPair() throws JOSEException
    {
        return new OctetKeyPairGenerator(Curve.Ed25519)
            .keyID("123")
            .generate();
    }

    private static String getToken(OctetKeyPair keyPair)
        throws JOSEException
    {
        return getToken(getJwtClaimsSet(), keyPair);
    }

    private static String getToken(JWTClaimsSet claims, OctetKeyPair issuerPrivateKey) throws JOSEException
    {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA).build();
        SignedJWT signedJWT = new SignedJWT(header, claims);
        Ed25519Signer signer = new Ed25519Signer(issuerPrivateKey);
        signedJWT.sign(signer);
        return signedJWT.serialize();
    }

    private static JWTClaimsSet getJwtClaimsSet()
    {
        return getJwtClaimsSet(System.currentTimeMillis() + 3600 * 1000);
    }

    private static JWTClaimsSet getJwtClaimsSet(long expirationTime)
    {
        return new JWTClaimsSet.Builder()
            .issuer(JWTTokenAuthTest.ISSUER)
            .subject("subject")
            .expirationTime(new Date(expirationTime))
            .audience(AUDIENCE)
            .build();
    }

    @Test
    void checkAuthWithInvalidToken() throws XWikiException, NoSuchFieldException, IllegalAccessException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        when(this.request.getHeader(AUTHORIZATION)).thenReturn("Bearer token");
        XWikiAuthService authService = getAuthService(jwtTokenAuth);
        XWikiException exception = assertThrows(XWikiException.class, () -> jwtTokenAuth.checkAuth(this.context));
        assertEquals("Error number 9001 in 11: Invalid token.", exception.getMessage());
        verify(authService, never()).checkAuth(this.context);
    }

    @Test
    void checkPasswordAuth() throws XWikiException, NoSuchFieldException, IllegalAccessException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        String username = USERNAME;
        String password = PASSWORD;
        String rememberme = "rememberme";
        XWikiUser user = mock();
        XWikiAuthService authService = getAuthService(jwtTokenAuth);
        when(authService.checkAuth(username, password, rememberme, this.context)).thenReturn(user);
        assertSame(user, jwtTokenAuth.checkAuth(username, password, rememberme, this.context));
        verify(authService).checkAuth(username, password, rememberme, this.context);
    }

    @Test
    void showLogin() throws XWikiException, NoSuchFieldException, IllegalAccessException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        jwtTokenAuth.showLogin(this.context);
        verify(getAuthService(jwtTokenAuth)).showLogin(this.context);
    }

    @Test
    void authenticate() throws XWikiException, NoSuchFieldException, IllegalAccessException
    {
        JWTTokenAuth jwtTokenAuth = new JWTTokenAuth();
        String username = USERNAME;
        String password = PASSWORD;
        Principal principal = mock();
        XWikiAuthService authService = getAuthService(jwtTokenAuth);
        when(authService.authenticate(username, password, this.context)).thenReturn(principal);
        assertSame(principal, jwtTokenAuth.authenticate(username, password, this.context));
        verify(authService).authenticate(username, password, this.context);
    }

    @Test
    void createAuthServiceWithURLClassLoader()
    {
        NamespaceURLClassLoader classLoader = new NamespaceURLClassLoader(getClass().getClassLoader(), NAMESPACE);
        when(this.classLoaderManager.getURLClassLoader(NAMESPACE, false)).thenReturn(classLoader);

        new JWTTokenAuth();
        verify(this.classLoaderManager).getURLClassLoader(NAMESPACE, false);
    }

    private static XWikiAuthService getAuthService(JWTTokenAuth jwtTokenAuth)
        throws NoSuchFieldException, IllegalAccessException
    {
        // Use reflection to access the private authService field
        Field authServiceField = ReflectionUtils.getField(JWTTokenAuth.class, "authService");
        authServiceField.setAccessible(true);
        return ((TestAuthService) authServiceField.get(jwtTokenAuth)).getMock();
    }

}
