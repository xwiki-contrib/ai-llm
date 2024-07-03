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
package org.xwiki.contrib.llm.internal.xwikistore;

import java.util.List;
import java.util.Locale;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentStore}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class XWikiDocumentStoreTest
{
    private static final String COLLECTION_NAME = "mycollection";

    private static final String COLLECTION_WIKI = "subwiki";

    private static final DocumentReference COLLECTION_REFERENCE =
        new DocumentReference(COLLECTION_WIKI, COLLECTION_NAME, "WebHome");

    private static final String SUBWIKI_SPACE = "subwikispace";

    private static final List<String> SPACE_NAMES = List.of(SUBWIKI_SPACE, "xwiki:mainwikispace");

    private static final List<SpaceReference> SPACES =
        List.of(new SpaceReference(COLLECTION_WIKI, SUBWIKI_SPACE), new SpaceReference("xwiki", "mainwikispace"));

    @InjectMockComponents
    private XWikiDocumentStore xWikiDocumentStore;

    @MockComponent
    private XWikiDocumentStoreHelper xWikiDocumentStoreHelper;

    @MockComponent
    private Collection collection;

    @MockComponent
    private XWikiContext context;

    @Mock
    private XWiki xWiki;

    @MockComponent
    private Provider<XWikiDocumentDocument> xWikiDocumentDocumentProvider;

    @MockComponent
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @MockComponent
    private AuthorizationManager authorizationManager;

    @BeforeEach
    void setUp()
    {
        when(this.collection.getDocumentSpaces()).thenReturn(SPACE_NAMES);
        when(this.collection.getDocumentReference()).thenReturn(COLLECTION_REFERENCE);
        when(this.collection.getID()).thenReturn(COLLECTION_NAME);
        when(this.xWikiDocumentStoreHelper.resolveSpaceReferences(SPACE_NAMES, COLLECTION_REFERENCE))
            .thenReturn(SPACES);
        when(this.context.getWiki()).thenReturn(this.xWiki);
    }

    @Test
    void getDocumentNames() throws IndexException
    {
        this.xWikiDocumentStore.initialize(this.collection, null);
        List<DocumentReference> documents = List.of(
            new DocumentReference(COLLECTION_WIKI, SUBWIKI_SPACE, "WebHome", Locale.ROOT),
            new DocumentReference("xwiki", "mainwikispace", "WebHome", Locale.FRENCH)
        );
        when(this.xWikiDocumentStoreHelper.getDocuments(SPACES, 1, 12)).thenReturn(documents);
        assertEquals(List.of("subwiki:subwikispace.WebHome;", "xwiki:mainwikispace.WebHome;fr"),
            this.xWikiDocumentStore.getDocumentNames(1, 12));
    }

    @Test
    void getDocument() throws XWikiException, AccessDeniedException, IndexException
    {
        this.xWikiDocumentStore.initialize(this.collection, null);

        DocumentReference documentReference =
            new DocumentReference(COLLECTION_WIKI, SUBWIKI_SPACE, "WebHome", Locale.FRENCH);

        XWikiDocument document = mock();

        when(this.xWiki.getDocument(documentReference, this.context)).thenReturn(document);

        XWikiDocumentDocument xWikiDocumentDocument = mock();
        when(this.xWikiDocumentDocumentProvider.get()).thenReturn(xWikiDocumentDocument);

        assertEquals(xWikiDocumentDocument,
            this.xWikiDocumentStore.getDocument(COLLECTION_WIKI + ":" + SUBWIKI_SPACE + ".WebHome;fr"));

        verify(xWikiDocumentDocument).initialize(COLLECTION_NAME, document);
    }

    @Test
    void getDocumentOutsideSpace() throws IndexException
    {
        this.xWikiDocumentStore.initialize(this.collection, null);

        String documentName = "xwiki:subwikispace.WebHome;fr";

        IndexException indexException =
            assertThrows(IndexException.class, () -> this.xWikiDocumentStore.getDocument(documentName));
        assertEquals("The document [%s] isn't part of the collection [%s]".formatted(documentName, COLLECTION_NAME),
            indexException.getMessage());

        verifyNoInteractions(this.xWiki);
        verifyNoInteractions(this.xWikiDocumentDocumentProvider);
    }

    @Test
    void getDocumentAccessDenied() throws IndexException, AccessDeniedException
    {
        UserReference userReference = mock();
        DocumentReference userDocumentReference = mock();

        when(this.userReferenceSerializer.serialize(userReference)).thenReturn(userDocumentReference);
        this.xWikiDocumentStore.initialize(this.collection, userReference);

        DocumentReference documentReference =
            new DocumentReference(COLLECTION_WIKI, SUBWIKI_SPACE, "WebHome", Locale.ROOT);

        AccessDeniedException exception = mock();
        doThrow(exception).when(this.authorizationManager)
            .checkAccess(Right.VIEW, userDocumentReference, documentReference);

        AccessDeniedException thrownException = assertThrows(AccessDeniedException.class, () ->
            this.xWikiDocumentStore.getDocument(COLLECTION_WIKI + ":" + SUBWIKI_SPACE + ".WebHome;"));

        assertSame(exception, thrownException);

        verifyNoInteractions(this.xWiki);
        verifyNoInteractions(this.xWikiDocumentDocumentProvider);
    }

    @Test
    void createDocument()
    {
        assertThrows(UnsupportedOperationException.class, () -> this.xWikiDocumentStore.createDocument(""));
    }

    @Test
    void saveDocument()
    {
        Document document = mock();
        assertThrows(UnsupportedOperationException.class, () -> this.xWikiDocumentStore.saveDocument(document));
    }

    @Test
    void deleteDocument()
    {
        Document document = mock();
        assertThrows(UnsupportedOperationException.class, () -> this.xWikiDocumentStore.deleteDocument(document));
    }
}
