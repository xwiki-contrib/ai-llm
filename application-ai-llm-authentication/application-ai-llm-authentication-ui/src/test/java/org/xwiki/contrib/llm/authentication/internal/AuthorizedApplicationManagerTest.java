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
package org.xwiki.contrib.llm.authentication.internal;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.page.PageTest;

import com.nimbusds.jose.jwk.OctetKeyPair;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link AuthorizedApplicationManager}.
 *
 * @version $Id$
 */
@ComponentList({ Ed25519PEMReader.class })
class AuthorizedApplicationManagerTest extends PageTest
{
    /**
     * A public key to use for testing.
     * Generated using the following command:
     * {@code openssl genpkey -algorithm ed25519 -outform PEM -out private.pem
     * && openssl pkey -in private.pem -pubout -outform PEM -out public.pem}
     */
    private static final String PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MCowBQYDK2VwAyEAshG1TnCjbamp/kw+hXb4il7JbPJFgfusCuyM4VW9VVk=
        -----END PUBLIC KEY-----
        """;

    private static final String PRIVATE_KEY = """
        -----BEGIN PRIVATE KEY-----
        MC4CAQAwBQYDK2VwBCIEIPArIU+xaeHcuGNQsWT5U4ixoKv3JXYhayvLlbFOLvGx
        -----END PRIVATE KEY-----
        """;

    private static final DocumentReference APPLICATION_REFERENCE =
        new DocumentReference("xwiki", "Applications", "TestApplication");

    private static final String APPLICATION_URL = "http://example.com";

    private static final DocumentReference USER_REFERENCE = new DocumentReference("xwiki", "XWiki", "TestUser");

    private static final String GROUP_FORMAT = "Application${group}Group";

    @InjectMockComponents
    private AuthorizedApplicationManager authorizedApplicationManager;

    @MockComponent
    private QueryManager queryManager;

    @Mock
    private Query query;

    @BeforeEach
    void setUp() throws Exception
    {
        DocumentReference documentReference =
            new DocumentReference(AuthorizedApplicationManager.AUTHORIZED_APPLICATION_CLASS_REFERENCE,
                new WikiReference("xwiki"));
        loadPage(documentReference);

        // Create an application document with test data.
        XWikiDocument applicationDocument = new XWikiDocument(APPLICATION_REFERENCE);
        applicationDocument.setAuthorReference(USER_REFERENCE);
        BaseObject applicationObject = applicationDocument.newXObject(
            AuthorizedApplicationManager.AUTHORIZED_APPLICATION_CLASS_REFERENCE, this.context);
        applicationObject.set("groupFormat", GROUP_FORMAT, this.context);
        applicationObject.set("publicKey", PUBLIC_KEY, this.context);
        applicationObject.set("url", APPLICATION_URL, this.context);
        this.context.getWiki().saveDocument(applicationDocument, "Test data", this.context);

        when(this.queryManager.createQuery(any(), any())).thenReturn(this.query);
        when(this.query.bindValue(any(), any())).thenReturn(this.query);
    }

    @Test
    void getApplication() throws QueryException, IOException, ComponentLookupException
    {
        when(this.oldcore.getMockAuthorizationManager().hasAccess(Right.ADMIN, USER_REFERENCE,
            APPLICATION_REFERENCE.getWikiReference()))
            .thenReturn(true);
        when(this.query.execute()).thenReturn(List.of(APPLICATION_REFERENCE.toString()));

        var optionalAuthorizedApplication = this.authorizedApplicationManager.getApplication(APPLICATION_URL);
        assertTrue(optionalAuthorizedApplication.isPresent());
        AuthorizedApplication authorizedApplication = optionalAuthorizedApplication.get();
        assertEquals(APPLICATION_URL, authorizedApplication.issuer());
        assertEquals(GROUP_FORMAT, authorizedApplication.groupFormat());
        assertNotNull(authorizedApplication.keyPair());
        assertEquals(APPLICATION_REFERENCE.getName(), authorizedApplication.name());

        // Assert that the public key is correctly read and matches the private key derived from the public key.
        Ed25519PEMReader pemReader = this.componentManager.getInstance(Ed25519PEMReader.class);
        OctetKeyPair privateKey = pemReader.readPrivateEd25519KeyFromPEM(PRIVATE_KEY);
        assertArrayEquals(privateKey.getX().decode(), authorizedApplication.keyPair().getX().decode());
    }
}
