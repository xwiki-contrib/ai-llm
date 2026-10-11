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

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.model.document.DocumentAuthors;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.GuestUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPConfigPageAuthorship}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPConfigPageAuthorshipTest
{
    private static final String WIKI = "subwiki";

    private static final DocumentReference ADMIN_REFERENCE = new DocumentReference("xwiki", "XWiki", "Admin");

    private static final DocumentReference USER_REFERENCE = new DocumentReference("xwiki", "XWiki", "User");

    @InjectMockComponents
    private MCPConfigPageAuthorship authorship;

    @MockComponent
    private AuthorizationManager authorizationManager;

    @MockComponent
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @MockComponent
    @Named("document")
    private UserReferenceResolver<DocumentReference> userReferenceResolver;

    @Mock
    private XWikiDocument document;

    @Mock
    private DocumentAuthors authors;

    @Mock
    private UserReference pageAuthor;

    @Mock
    private UserReference actingUser;

    @Mock
    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        lenient().when(this.document.getAuthors()).thenReturn(this.authors);
        lenient().when(this.context.getUserReference()).thenReturn(USER_REFERENCE);
        lenient().when(this.userReferenceResolver.resolve(USER_REFERENCE)).thenReturn(this.actingUser);
    }

    @Test
    void authorOfSerializesTheEffectiveMetadataAuthor()
    {
        when(this.authors.getEffectiveMetadataAuthor()).thenReturn(this.pageAuthor);
        when(this.userReferenceSerializer.serialize(this.pageAuthor)).thenReturn(ADMIN_REFERENCE);

        assertEquals(ADMIN_REFERENCE, this.authorship.authorOf(this.document));
    }

    @Test
    void authorOfIsNullWhenThePageRecordsNoAuthor()
    {
        when(this.authors.getEffectiveMetadataAuthor()).thenReturn(null);

        assertNull(this.authorship.authorOf(this.document));
        // A missing author is never serialized: the serializer would substitute the current user for it.
        verify(this.userReferenceSerializer, never()).serialize(any());
    }

    @Test
    void authorOfIsNullForTheGuestAuthor()
    {
        when(this.authors.getEffectiveMetadataAuthor()).thenReturn(GuestUserReference.INSTANCE);

        assertNull(this.authorship.authorOf(this.document));
        verify(this.userReferenceSerializer, never()).serialize(any());
    }

    @Test
    void isTrustedIsTrueForAnAdminOfTheWiki()
    {
        when(this.authorizationManager.hasAccess(Right.ADMIN, ADMIN_REFERENCE, new WikiReference(WIKI)))
            .thenReturn(true);

        assertTrue(this.authorship.isTrusted(ADMIN_REFERENCE, WIKI));
    }

    @Test
    void isTrustedIsFalseForANonAdminOfTheWiki()
    {
        when(this.authorizationManager.hasAccess(Right.ADMIN, ADMIN_REFERENCE, new WikiReference(WIKI)))
            .thenReturn(false);

        assertFalse(this.authorship.isTrusted(ADMIN_REFERENCE, WIKI));
    }

    @Test
    void isTrustedIsFalseWithoutAnAuthorAndWithoutARightsLookup()
    {
        assertFalse(this.authorship.isTrusted(null, WIKI));
        verify(this.authorizationManager, never()).hasAccess(any(), any(), any());
    }

    @Test
    void stampRecordsTheActingUserAsAuthorAndCreator()
    {
        this.authorship.stamp(this.document, this.context);

        verify(this.authors).setOriginalMetadataAuthor(this.actingUser);
        verify(this.authors).setEffectiveMetadataAuthor(this.actingUser);
        verify(this.authors).setCreator(this.actingUser);
        // A regular write keeps the content author: the page content is not what the write changes.
        verify(this.authors, never()).setContentAuthor(any());
    }

    @Test
    void stampRecordsTheGuestWhenTheContextHasNoUser()
    {
        when(this.context.getUserReference()).thenReturn(null);
        when(this.userReferenceResolver.resolve((DocumentReference) null)).thenReturn(GuestUserReference.INSTANCE);

        this.authorship.stamp(this.document, this.context);

        // A save without a context user records the guest as author: the page then fails closed (untrusted)
        // rather than borrowing the trust of whoever saved it before.
        verify(this.authors).setOriginalMetadataAuthor(GuestUserReference.INSTANCE);
        verify(this.authors).setEffectiveMetadataAuthor(GuestUserReference.INSTANCE);
        verify(this.authors).setCreator(GuestUserReference.INSTANCE);
    }

    @Test
    void takeOwnershipAlsoRecordsTheActingUserAsContentAuthor()
    {
        this.authorship.takeOwnership(this.document, this.context);

        verify(this.authors).setOriginalMetadataAuthor(this.actingUser);
        verify(this.authors).setEffectiveMetadataAuthor(this.actingUser);
        verify(this.authors).setCreator(this.actingUser);
        verify(this.authors).setContentAuthor(this.actingUser);
    }
}
