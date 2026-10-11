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

import java.util.List;

import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.AuthorizationException;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link InternalDocumentStore}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class InternalDocumentStoreTest
{
    private static final String WIKI_NAME = "wiki";

    private static final String COLLECTION_ID = "testCollection";

    private static final String DOCUMENT_ID = "testDocument";

    private static final DocumentReference COLLECTION_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_ID), "WebHome");

    private static final DocumentReference DOCUMENT_REFERENCE =
        InternalDocumentStore.getDocumentReference(WIKI_NAME, COLLECTION_ID, DOCUMENT_ID);

    private static final DocumentReference USER_REFERENCE = new DocumentReference(WIKI_NAME, "XWiki", "User");

    private static final DocumentReference SCRIPT_AUTHOR_REFERENCE =
        new DocumentReference(WIKI_NAME, "XWiki", "ScriptAuthor");

    private static final DocumentReference SYSTEM_REFERENCE = new DocumentReference(WIKI_NAME, "XWiki", "System");

    @InjectMockComponents
    private InternalDocumentStore documentStore;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    private WriteAttribution writeAttribution;

    @MockComponent
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @MockComponent
    private XWikiContext context;

    @MockComponent
    private Provider<DefaultDocument> documentProvider;

    @Mock
    private XWiki xWiki;

    @Mock
    private Collection collection;

    @Mock
    private Query query;

    @Mock
    private DefaultDocument document;

    private XWikiDocument xWikiDocument;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.collection.getDocumentReference()).thenReturn(COLLECTION_REFERENCE);
        when(this.collection.getID()).thenReturn(COLLECTION_ID);
        when(this.queryManager.createQuery(anyString(), eq(Query.HQL))).thenReturn(this.query);
        when(this.query.execute()).thenReturn(List.of(DOCUMENT_ID));

        when(this.context.getWiki()).thenReturn(this.xWiki);
        when(this.context.getUserReference()).thenReturn(USER_REFERENCE);

        this.xWikiDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        when(this.document.getClonedXWikiDocument()).thenReturn(this.xWikiDocument);
        when(this.document.getID()).thenReturn(DOCUMENT_ID);
    }

    private void initialize(UserReference userReference) throws IndexException
    {
        this.documentStore.initialize(this.collection, userReference);
    }

    /**
     * Initialize the store for a plain user, for which the rights check returns the user itself.
     *
     * @return the user the store acts for
     */
    private UserReference initializeForUser() throws Exception
    {
        UserReference user = mock();
        when(this.userReferenceSerializer.serialize(user)).thenReturn(USER_REFERENCE);
        when(this.writeAttribution.checkAccess(any(), eq(DOCUMENT_REFERENCE), eq(user), eq(USER_REFERENCE)))
            .thenReturn(USER_REFERENCE);
        initialize(user);
        return user;
    }

    /**
     * Initialize the store for the current user, for which the rights check returns a script author.
     */
    private void initializeForCurrentUser() throws Exception
    {
        when(this.userReferenceSerializer.serialize(CurrentUserReference.INSTANCE)).thenReturn(USER_REFERENCE);
        when(this.writeAttribution.checkAccess(any(), eq(DOCUMENT_REFERENCE), eq(CurrentUserReference.INSTANCE),
            eq(USER_REFERENCE))).thenReturn(SCRIPT_AUTHOR_REFERENCE);
        initialize(CurrentUserReference.INSTANCE);
    }

    private static String notTheDocumentMessage(DocumentReference page)
    {
        return "The page [%s] is not the document [%s] of collection [%s]".formatted(page, DOCUMENT_ID,
            COLLECTION_ID);
    }

    @Test
    void getDocumentReference()
    {
        DocumentReference reference =
            InternalDocumentStore.getDocumentReference(WIKI_NAME, COLLECTION_ID, DOCUMENT_ID);

        assertEquals(new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_ID, "Documents"),
            DigestUtils.sha256Hex(DOCUMENT_ID)), reference);
    }

    @Test
    void getDocumentReferencesMatchesStaticReference() throws Exception
    {
        initialize(null);

        assertEquals(List.of(DOCUMENT_REFERENCE), this.documentStore.getDocumentReferences(0, 10));
    }

    @Test
    void getDocumentNamesIsBoundToTheDocumentsSpace() throws Exception
    {
        initialize(null);

        assertEquals(List.of(DOCUMENT_ID), this.documentStore.getDocumentNames(0, 10));

        verify(this.queryManager).createQuery(argThat(hql -> hql.contains("doc.space = :space")), eq(Query.HQL));
        verify(this.query).setWiki(WIKI_NAME);
        verify(this.query).bindValue("space", "AI.Collections." + COLLECTION_ID + ".Documents");
        verify(this.query).bindValue("collection", COLLECTION_ID);
        verify(this.query).setLimit(10);
        verify(this.query).setOffset(0);
    }

    @Test
    void getDocumentNamesEscapesTheCollectionId() throws Exception
    {
        String dottedCollectionId = "test.collection";
        DocumentReference dottedCollectionReference =
            new DocumentReference(WIKI_NAME, List.of("AI", "Collections", dottedCollectionId), "WebHome");
        Collection dottedCollection = mock(Collection.class);
        when(dottedCollection.getDocumentReference()).thenReturn(dottedCollectionReference);
        when(dottedCollection.getID()).thenReturn(dottedCollectionId);
        this.documentStore.initialize(dottedCollection, null);

        this.documentStore.getDocumentNames(0, -1);

        verify(this.query).bindValue("space", "AI.Collections.test\\.collection.Documents");
        assertEquals(dottedCollectionId, InternalDocumentStore.getDocumentReference(WIKI_NAME, dottedCollectionId,
            DOCUMENT_ID).getLastSpaceReference().getParent().getName());
    }

    @Test
    void initializeTwiceFails() throws Exception
    {
        initialize(null);

        assertThrows(IllegalStateException.class, () -> initialize(null));
    }

    @Test
    void saveDocumentAttributesTheSaveToTheActingUser() throws Exception
    {
        UserReference user = initializeForUser();

        this.documentStore.saveDocument(this.document);

        verify(this.writeAttribution).checkAccess(Right.EDIT, DOCUMENT_REFERENCE, user, USER_REFERENCE);
        verify(this.writeAttribution).prepareSave(this.xWikiDocument, USER_REFERENCE, USER_REFERENCE);
        verify(this.xWiki).checkSavingDocument(USER_REFERENCE, this.xWikiDocument, this.context);
        verify(this.xWiki).saveDocument(this.xWikiDocument, this.context);
    }

    @Test
    void saveDocumentFromAScriptAttributesToTheScriptAuthor() throws Exception
    {
        initializeForCurrentUser();

        this.documentStore.saveDocument(this.document);

        verify(this.writeAttribution)
            .checkAccess(Right.EDIT, DOCUMENT_REFERENCE, CurrentUserReference.INSTANCE, USER_REFERENCE);
        verify(this.writeAttribution).prepareSave(this.xWikiDocument, USER_REFERENCE, SCRIPT_AUTHOR_REFERENCE);
        verify(this.xWiki).checkSavingDocument(SCRIPT_AUTHOR_REFERENCE, this.xWikiDocument, this.context);
        verify(this.xWiki).saveDocument(this.xWikiDocument, this.context);
    }

    @Test
    void saveDocumentDeniedFails() throws Exception
    {
        initializeForCurrentUser();
        AccessDeniedException exception = new AccessDeniedException(Right.EDIT, USER_REFERENCE, DOCUMENT_REFERENCE);
        doThrow(exception).when(this.writeAttribution)
            .checkAccess(Right.EDIT, DOCUMENT_REFERENCE, CurrentUserReference.INSTANCE, USER_REFERENCE);

        assertSame(exception,
            assertThrows(AccessDeniedException.class, () -> this.documentStore.saveDocument(this.document)));

        verify(this.writeAttribution, never()).prepareSave(any(), any(), any());
        verifyNoInteractions(this.xWiki);
    }

    @Test
    void saveDocumentDeniedARequiredRightFails() throws Exception
    {
        initializeForCurrentUser();
        AccessDeniedException exception =
            new AccessDeniedException(Right.SCRIPT, SCRIPT_AUTHOR_REFERENCE, DOCUMENT_REFERENCE);
        doThrow(exception).when(this.writeAttribution)
            .prepareSave(this.xWikiDocument, USER_REFERENCE, SCRIPT_AUTHOR_REFERENCE);

        assertSame(exception,
            assertThrows(AccessDeniedException.class, () -> this.documentStore.saveDocument(this.document)));

        verifyNoInteractions(this.xWiki);
    }

    @Test
    void saveDocumentFailingToLoadRequiredRightsFails() throws Exception
    {
        initializeForCurrentUser();
        AuthorizationException exception = mock();
        doThrow(exception).when(this.writeAttribution)
            .prepareSave(this.xWikiDocument, USER_REFERENCE, SCRIPT_AUTHOR_REFERENCE);

        IndexException thrown =
            assertThrows(IndexException.class, () -> this.documentStore.saveDocument(this.document));

        assertSame(exception, thrown.getCause());
        verifyNoInteractions(this.xWiki);
    }

    @Test
    void saveDocumentOutsideTheCollectionFails() throws Exception
    {
        initializeForCurrentUser();
        DocumentReference otherReference = new DocumentReference(WIKI_NAME, "Other", "Page");
        when(this.document.getClonedXWikiDocument()).thenReturn(new XWikiDocument(otherReference));

        IndexException exception =
            assertThrows(IndexException.class, () -> this.documentStore.saveDocument(this.document));

        assertEquals(notTheDocumentMessage(otherReference), exception.getMessage());
        verifyNoInteractions(this.writeAttribution, this.xWiki);
    }

    @Test
    void saveDocumentRefusesThePreferencesPageOfTheDocumentsSpace() throws Exception
    {
        initializeForCurrentUser();
        DocumentReference preferencesReference =
            new DocumentReference("WebPreferences", DOCUMENT_REFERENCE.getLastSpaceReference());
        when(this.document.getClonedXWikiDocument()).thenReturn(new XWikiDocument(preferencesReference));

        IndexException exception =
            assertThrows(IndexException.class, () -> this.documentStore.saveDocument(this.document));

        assertEquals(notTheDocumentMessage(preferencesReference), exception.getMessage());
        verifyNoInteractions(this.writeAttribution, this.xWiki);
    }

    @Test
    void saveDocumentRefusesAnIdThatDoesNotMatchThePage() throws Exception
    {
        initializeForCurrentUser();
        when(this.document.getID()).thenReturn("otherDocument");

        IndexException exception =
            assertThrows(IndexException.class, () -> this.documentStore.saveDocument(this.document));

        assertEquals("The page [%s] is not the document [otherDocument] of collection [%s]"
            .formatted(DOCUMENT_REFERENCE, COLLECTION_ID), exception.getMessage());
        verifyNoInteractions(this.writeAttribution, this.xWiki);
    }

    @Test
    void saveDocumentRefusesAForeignDocumentImplementation() throws Exception
    {
        initializeForCurrentUser();
        Document foreignDocument = mock();
        when(foreignDocument.getID()).thenReturn(DOCUMENT_ID);

        IndexException exception =
            assertThrows(IndexException.class, () -> this.documentStore.saveDocument(foreignDocument));

        assertEquals("The document [testDocument] is not managed by this document store", exception.getMessage());
        verify(foreignDocument, never()).getXWikiDocument();
        verifyNoInteractions(this.writeAttribution, this.xWiki);
    }

    @Test
    void saveDocumentWithoutUserStillAttributesToTheContextUser() throws Exception
    {
        initialize(null);
        when(this.context.getUserReference()).thenReturn(SYSTEM_REFERENCE);

        this.documentStore.saveDocument(this.document);

        verify(this.writeAttribution).prepareSave(this.xWikiDocument, SYSTEM_REFERENCE, SYSTEM_REFERENCE);
        verify(this.writeAttribution, never()).checkAccess(any(), any(), any(), any());
        verify(this.xWiki).saveDocument(this.xWikiDocument, this.context);
        verify(this.xWiki, never()).checkSavingDocument(any(), any(), any());
    }

    @Test
    void saveDocumentWithoutAnyUserKeepsTheAuthors() throws Exception
    {
        initialize(null);
        when(this.context.getUserReference()).thenReturn(null);

        this.documentStore.saveDocument(this.document);

        verifyNoInteractions(this.writeAttribution);
        verify(this.xWiki).saveDocument(this.xWikiDocument, this.context);
    }

    @Test
    void deleteDocumentChecksTheScriptAuthor() throws Exception
    {
        initializeForCurrentUser();

        this.documentStore.deleteDocument(this.document);

        verify(this.writeAttribution)
            .checkAccess(Right.DELETE, DOCUMENT_REFERENCE, CurrentUserReference.INSTANCE, USER_REFERENCE);
        verify(this.writeAttribution, never()).prepareSave(any(), any(), any());
        verify(this.xWiki).checkDeletingDocument(SCRIPT_AUTHOR_REFERENCE, this.xWikiDocument, this.context);
        verify(this.xWiki).deleteDocument(this.xWikiDocument, this.context);
    }

    @Test
    void deleteDocumentDeniedFails() throws Exception
    {
        initializeForCurrentUser();
        AccessDeniedException exception =
            new AccessDeniedException(Right.DELETE, SCRIPT_AUTHOR_REFERENCE, DOCUMENT_REFERENCE);
        doThrow(exception).when(this.writeAttribution)
            .checkAccess(Right.DELETE, DOCUMENT_REFERENCE, CurrentUserReference.INSTANCE, USER_REFERENCE);

        assertSame(exception,
            assertThrows(AccessDeniedException.class, () -> this.documentStore.deleteDocument(this.document)));

        verifyNoInteractions(this.xWiki);
    }

    @Test
    void deleteDocumentOutsideTheCollectionFails() throws Exception
    {
        initializeForCurrentUser();
        DocumentReference otherReference = new DocumentReference(WIKI_NAME, "Other", "Page");
        when(this.document.getClonedXWikiDocument()).thenReturn(new XWikiDocument(otherReference));

        IndexException exception =
            assertThrows(IndexException.class, () -> this.documentStore.deleteDocument(this.document));

        assertEquals(notTheDocumentMessage(otherReference), exception.getMessage());
        verifyNoInteractions(this.writeAttribution, this.xWiki);
    }

    @Test
    void createDocumentChecksAccess() throws Exception
    {
        initializeForCurrentUser();
        when(this.xWiki.getDocument(DOCUMENT_REFERENCE, this.context)).thenReturn(this.xWikiDocument);
        DefaultDocument defaultDocument = mock();
        when(this.documentProvider.get()).thenReturn(defaultDocument);

        assertSame(defaultDocument, this.documentStore.createDocument(DOCUMENT_ID));

        verify(this.writeAttribution)
            .checkAccess(Right.EDIT, DOCUMENT_REFERENCE, CurrentUserReference.INSTANCE, USER_REFERENCE);
        verify(defaultDocument).initialize(this.xWikiDocument);
        verify(defaultDocument).setID(DOCUMENT_ID);
        verify(defaultDocument).setCollection(COLLECTION_ID);
    }

    @Test
    void createDocumentDeniedFails() throws Exception
    {
        initializeForCurrentUser();
        doThrow(new AccessDeniedException(Right.EDIT, SCRIPT_AUTHOR_REFERENCE, DOCUMENT_REFERENCE))
            .when(this.writeAttribution)
            .checkAccess(Right.EDIT, DOCUMENT_REFERENCE, CurrentUserReference.INSTANCE, USER_REFERENCE);

        assertThrows(AccessDeniedException.class, () -> this.documentStore.createDocument(DOCUMENT_ID));

        verifyNoInteractions(this.xWiki, this.documentProvider);
    }
}
