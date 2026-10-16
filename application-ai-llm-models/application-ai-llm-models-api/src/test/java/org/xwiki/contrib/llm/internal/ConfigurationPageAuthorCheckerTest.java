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
package org.xwiki.contrib.llm.internal;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.component.wiki.WikiComponentException;
import org.xwiki.model.document.DocumentAuthors;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.GuestUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.doc.XWikiDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ConfigurationPageAuthorChecker}.
 *
 * @version $Id$
 */
@ComponentTest
class ConfigurationPageAuthorCheckerTest
{
    private static final WikiReference WIKI_REFERENCE = new WikiReference("xwiki");

    private static final DocumentReference CONFIG_REFERENCE =
        new DocumentReference("xwiki", "AI", "Config");

    private static final ObjectReference OBJECT_REFERENCE = new ObjectReference("AI.Class[0]", CONFIG_REFERENCE);

    private static final DocumentReference USER_REFERENCE = new DocumentReference("xwiki", "XWiki", "User");

    @InjectMockComponents
    private ConfigurationPageAuthorChecker configurationPageAuthorChecker;

    @MockComponent
    private AuthorizationManager authorizationManager;

    @MockComponent
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @Mock
    private XWikiDocument document;

    @Mock
    private DocumentAuthors authors;

    @Mock
    private UserReference author;

    @BeforeEach
    void setUp()
    {
        when(this.document.getAuthors()).thenReturn(this.authors);
    }

    @Test
    void pageWithoutAuthorIsRejectedBeforeAnyRightCheck()
    {
        when(this.authors.getEffectiveMetadataAuthor()).thenReturn(null);

        WikiComponentException exception = assertThrows(WikiComponentException.class,
            () -> this.configurationPageAuthorChecker.requireAdminAuthor(this.document, OBJECT_REFERENCE));

        assertEquals("Failed to build component for object [" + OBJECT_REFERENCE + "], the page has no author.",
            exception.getMessage());
        // A missing author is never serialized: the serializer would substitute the current user for it.
        verify(this.userReferenceSerializer, never()).serialize(any());
        verify(this.authorizationManager, never()).hasAccess(any(), any(), any());
    }

    @Test
    void guestAuthorIsRejectedBeforeAnyRightCheck()
    {
        when(this.authors.getEffectiveMetadataAuthor()).thenReturn(GuestUserReference.INSTANCE);

        assertThrows(WikiComponentException.class,
            () -> this.configurationPageAuthorChecker.requireAdminAuthor(this.document, OBJECT_REFERENCE));

        verify(this.userReferenceSerializer, never()).serialize(any());
        verify(this.authorizationManager, never()).hasAccess(any(), any(), any());
    }

    @Test
    void authorWithoutAdminRightIsRejected()
    {
        when(this.document.getDocumentReference()).thenReturn(CONFIG_REFERENCE);
        when(this.authors.getEffectiveMetadataAuthor()).thenReturn(this.author);
        when(this.userReferenceSerializer.serialize(this.author)).thenReturn(USER_REFERENCE);
        when(this.authorizationManager.hasAccess(Right.ADMIN, USER_REFERENCE, WIKI_REFERENCE)).thenReturn(false);

        WikiComponentException exception = assertThrows(WikiComponentException.class,
            () -> this.configurationPageAuthorChecker.requireAdminAuthor(this.document, OBJECT_REFERENCE));

        assertTrue(exception.getMessage().contains("user [" + USER_REFERENCE + "] does not have admin rights"),
            exception.getMessage());
    }

    @Test
    void adminAuthorIsReturnedAsDocumentReference() throws Exception
    {
        when(this.document.getDocumentReference()).thenReturn(CONFIG_REFERENCE);
        when(this.authors.getEffectiveMetadataAuthor()).thenReturn(this.author);
        when(this.userReferenceSerializer.serialize(this.author)).thenReturn(USER_REFERENCE);
        when(this.authorizationManager.hasAccess(Right.ADMIN, USER_REFERENCE, WIKI_REFERENCE)).thenReturn(true);

        assertEquals(USER_REFERENCE,
            this.configurationPageAuthorChecker.requireAdminAuthor(this.document, OBJECT_REFERENCE));
    }
}
