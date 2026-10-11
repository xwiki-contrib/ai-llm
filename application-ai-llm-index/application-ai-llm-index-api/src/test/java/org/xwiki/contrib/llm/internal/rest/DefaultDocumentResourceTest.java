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
package org.xwiki.contrib.llm.internal.rest;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.ws.rs.WebApplicationException;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.ChunkingUtils;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.contrib.llm.internal.CurrentUserCollection;
import org.xwiki.contrib.llm.internal.CurrentUserCollectionManager;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.DefaultCollectionManager;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.contrib.llm.internal.InternalDocumentStore;
import org.xwiki.contrib.llm.internal.WriteAttribution;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentDocument;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStore;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStoreHelper;
import org.xwiki.contrib.llm.rest.JSONDocument;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.query.QueryFilter;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.Right;
import org.xwiki.security.authorization.requiredrights.DocumentRequiredRightsManager;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.UserReferenceResolver;
import org.xwiki.user.UserReferenceSerializer;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link DefaultDocumentResource}.
 *
 * @version $Id$
 */
@OldcoreTest
@ComponentList({
    DefaultCollectionManager.class,
    CurrentUserCollectionManager.class,
    DefaultCollection.class,
    CurrentUserCollection.class,
    DefaultDocument.class,
    InternalDocumentStore.class,
    WriteAttribution.class,
    XWikiDocumentStore.class,
    XWikiDocumentStoreHelper.class
})
@ReferenceComponentList
class DefaultDocumentResourceTest
{
    public static final String DOCUMENT_ID = "testdoc";

    public static final String CONTENT = "test content";

    public static final String LANGUAGE = "en";

    public static final String MIME_TYPE = "text/plain";

    private static final String WIKI_NAME = "testwiki";

    private static final String COLLECTION_NAME = "testcollection";

    private static final String MAIN_WIKI = "xwiki";

    private static final DocumentReference DOCUMENT_REFERENCE =
        new DocumentReference(WIKI_NAME,
            List.of("AI", "Collections", COLLECTION_NAME, "Documents"), DigestUtils.sha256Hex(DOCUMENT_ID));

    private static final DocumentReference USER_REFERENCE = new DocumentReference(MAIN_WIKI, "XWiki", "User");

    private static final String READ_ONLY_COLLECTION_NAME = "readonlycollection";

    private static final String READ_ONLY_STORE = "readonly";

    private static final DocumentReference READ_ONLY_COLLECTION_REFERENCE = new DocumentReference(WIKI_NAME,
        List.of("AI", "Collections", READ_ONLY_COLLECTION_NAME), "WebHome");

    private static final String XWIKI_STORE_COLLECTION_NAME = "xwikistorecollection";

    private static final DocumentReference XWIKI_STORE_COLLECTION_REFERENCE =
        DefaultCollection.getDocumentReference(WIKI_NAME, XWIKI_STORE_COLLECTION_NAME);

    private static final String XWIKI_STORE_SPACE = "Docs";

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultDocumentResource documentResource;

    @Inject
    private CollectionManager collectionManager;

    @MockComponent
    private ChunkingUtils chunkingUtils;

    @MockComponent
    private SolrConnector solrConnector;

    @MockComponent
    private AiLLMSolrCoreInitializer aillmSolrCoreInitializer;

    @MockComponent
    private GroupManager groupManager;

    @MockComponent
    private UserReferenceResolver<CurrentUserReference> currentUserReferenceUserReferenceResolver;

    @MockComponent
    private DocumentRequiredRightsManager documentRequiredRightsManager;

    @MockComponent
    @Named(READ_ONLY_STORE)
    private DocumentStore readOnlyDocumentStore;

    @MockComponent
    @Named("language")
    private QueryFilter languageFilter;

    @MockComponent
    @Named("count")
    private QueryFilter countFilter;

    @MockComponent
    private SpaceReferenceResolver<String> spaceReferenceResolver;

    @MockComponent
    private Provider<XWikiDocumentDocument> xWikiDocumentDocumentProvider;

    @BeforeEach
    void setUp() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        this.collectionManager.createCollection(COLLECTION_NAME).save();
        Collection collection = this.collectionManager.getCollection(COLLECTION_NAME);
        Document internalDocument = collection.getDocumentStore().createDocument(DOCUMENT_ID);
        internalDocument.setContent(CONTENT);
        internalDocument.setLanguage(LANGUAGE);
        internalDocument.setMimetype(MIME_TYPE);
        collection.getDocumentStore().saveDocument(internalDocument);
        this.oldcore.getXWikiContext().setWikiId(MAIN_WIKI);

        // Do nothing when checking for saving or deletion.
        doNothing().when(this.oldcore.getSpyXWiki()).checkSavingDocument(any(), any(), any(), anyBoolean(), any());
        doNothing().when(this.oldcore.getSpyXWiki()).checkDeletingDocument(any(), any(), any());


        // Set the current user reference and serialize the current user reference to it.
        this.oldcore.getXWikiContext().setUserReference(USER_REFERENCE);

        UserReferenceSerializer<DocumentReference> userReferenceSerializer =
            this.oldcore.getMocker().getInstance(UserReferenceSerializer.TYPE_DOCUMENT_REFERENCE, "document");
        when(userReferenceSerializer.serialize(CurrentUserReference.INSTANCE)).thenReturn(USER_REFERENCE);

    }

    @Test
    void getDocument() throws XWikiRestException
    {
        JSONDocument document = this.documentResource.getDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID);
        assertEquals(DOCUMENT_ID, document.getId());
        assertEquals(CONTENT, document.getContent());
        assertEquals(LANGUAGE, document.getLanguage());
        assertEquals(MIME_TYPE, document.getMimetype());
    }

    @Test
    void getDocumentNotFound()
    {
        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.getDocument(WIKI_NAME, COLLECTION_NAME, "notfound"));
        assertEquals(404, exception.getResponse().getStatus());
    }

    @Test
    void getDocumentNotAccessible() throws AccessDeniedException
    {
        doThrow(new AccessDeniedException(Right.VIEW, USER_REFERENCE, DOCUMENT_REFERENCE))
            .when(this.oldcore.getMockAuthorizationManager())
            .checkAccess(Right.VIEW, USER_REFERENCE, DOCUMENT_REFERENCE);

        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.getDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID));
        assertEquals(401, exception.getResponse().getStatus());
    }

    @Test
    void deleteDocument() throws XWikiRestException
    {
        // Delete it.
        this.documentResource.deleteDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID);

        // Verify that it doesn't exist.
        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.getDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID));
        assertEquals(404, exception.getResponse().getStatus());
    }

    @Test
    void deleteDocumentDenied() throws AccessDeniedException, XWikiRestException
    {
        doThrow(new AccessDeniedException(Right.DELETE, USER_REFERENCE, DOCUMENT_REFERENCE))
            .when(this.oldcore.getMockAuthorizationManager())
            .checkAccess(Right.DELETE, USER_REFERENCE, DOCUMENT_REFERENCE);

        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.deleteDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID));
        assertEquals(401, exception.getResponse().getStatus());

        // Verify that the document still exists.
        assertEquals(DOCUMENT_ID,
            this.documentResource.getDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID).getTitle());
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void putDocumentOnAReadOnlyStoreIsNotAllowed() throws Exception
    {
        createReadOnlyCollection();
        JSONDocument document = new JSONDocument();
        document.setContent(CONTENT);

        WebApplicationException exception = assertThrows(WebApplicationException.class, () ->
            this.documentResource.putDocument(WIKI_NAME, READ_ONLY_COLLECTION_NAME, DOCUMENT_ID, document));
        assertEquals(405, exception.getResponse().getStatus());
    }

    @Test
    void deleteDocumentOnAReadOnlyStoreIsNotAllowed() throws Exception
    {
        createReadOnlyCollection();

        WebApplicationException exception = assertThrows(WebApplicationException.class, () ->
            this.documentResource.deleteDocument(WIKI_NAME, READ_ONLY_COLLECTION_NAME, DOCUMENT_ID));
        assertEquals(405, exception.getResponse().getStatus());
    }

    /**
     * Create a collection whose document store, like the XWiki document store, refuses every modification.
     */
    private void createReadOnlyCollection() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        this.collectionManager.createCollection(READ_ONLY_COLLECTION_NAME).save();
        XWikiDocument collectionDocument = this.oldcore.getSpyXWiki()
            .getDocument(READ_ONLY_COLLECTION_REFERENCE, this.oldcore.getXWikiContext()).clone();
        collectionDocument.getXObject(Collection.XCLASS_REFERENCE)
            .setStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME, READ_ONLY_STORE);
        this.oldcore.getSpyXWiki().saveDocument(collectionDocument, this.oldcore.getXWikiContext());
        this.oldcore.getXWikiContext().setWikiId(MAIN_WIKI);

        Document existingDocument = mock();
        when(this.readOnlyDocumentStore.getDocument(DOCUMENT_ID)).thenReturn(existingDocument);
        doThrow(new UnsupportedOperationException()).when(this.readOnlyDocumentStore).saveDocument(any());
        doThrow(new UnsupportedOperationException()).when(this.readOnlyDocumentStore).deleteDocument(any());
    }

    @Test
    void getDocumentThatIsNotADocumentOfAnXWikiStoreCollectionIsNotFound() throws Exception
    {
        createXWikiStoreCollection();

        // An id that is not the id of a page, the id of a page outside the spaces of the collection and the id of a
        // page of those spaces that does not exist are not documents of the collection.
        for (String documentId : List.of("nonsense", "testwiki:Other.Page;", "testwiki:Docs.Missing;")) {
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> this.documentResource.getDocument(WIKI_NAME, XWIKI_STORE_COLLECTION_NAME, documentId));
            assertEquals(404, exception.getResponse().getStatus(), documentId);
        }
    }

    /**
     * Create a collection whose documents are the pages of the {@code Docs} space of the wiki of the collection.
     */
    private void createXWikiStoreCollection() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        DefaultCollection collection =
            (DefaultCollection) this.collectionManager.createCollection(XWIKI_STORE_COLLECTION_NAME);
        collection.setDocumentSpaces(List.of(XWIKI_STORE_SPACE));
        collection.save();
        XWikiDocument collectionDocument = this.oldcore.getSpyXWiki()
            .getDocument(XWIKI_STORE_COLLECTION_REFERENCE, this.oldcore.getXWikiContext()).clone();
        collectionDocument.getXObject(Collection.XCLASS_REFERENCE)
            .setStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME, XWikiDocumentStore.NAME);
        this.oldcore.getSpyXWiki().saveDocument(collectionDocument, this.oldcore.getXWikiContext());
        this.oldcore.getXWikiContext().setWikiId(MAIN_WIKI);

        when(this.spaceReferenceResolver.resolve(XWIKI_STORE_SPACE, XWIKI_STORE_COLLECTION_REFERENCE))
            .thenReturn(new SpaceReference(WIKI_NAME, XWIKI_STORE_SPACE));
    }

    @Test
    void putNewDocument() throws Exception
    {
        JSONDocument document = new JSONDocument();
        document.setId("wrongId");
        document.setContent(CONTENT);
        document.setLanguage(LANGUAGE);
        document.setMimetype(MIME_TYPE);

        String documentID = "newDocument";
        JSONDocument result =
            this.documentResource.putDocument(WIKI_NAME, COLLECTION_NAME, documentID, document);
        assertEquals(documentID, result.getId());
        assertEquals(CONTENT, result.getContent());
        assertEquals(LANGUAGE, result.getLanguage());
        assertEquals(MIME_TYPE, result.getMimetype());

        // Verify that the document has actually been saved.
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        Collection collection = this.collectionManager.getCollection(COLLECTION_NAME);
        Document savedDocument = collection.getDocumentStore().getDocument(documentID);
        assertNotNull(savedDocument);
        assertEquals(CONTENT, savedDocument.getContent());
        assertEquals(LANGUAGE, savedDocument.getLanguage());
        assertEquals(MIME_TYPE, result.getMimetype());
    }

    @Test
    void updateExistingDocument() throws XWikiRestException
    {
        String newContent = "new content";
        JSONDocument document = new JSONDocument();
        document.setContent(newContent);

        JSONDocument result =
            this.documentResource.putDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID, document);
        assertEquals(DOCUMENT_ID, result.getId());
        assertEquals(newContent, result.getContent());
        assertEquals(LANGUAGE, result.getLanguage());
        assertEquals(MIME_TYPE, result.getMimetype());
    }

    @Test
    void updateDeniedExisting() throws AccessDeniedException
    {
        JSONDocument document = new JSONDocument();
        document.setContent(CONTENT);

        doThrow(new AccessDeniedException(Right.EDIT, USER_REFERENCE, DOCUMENT_REFERENCE))
            .when(this.oldcore.getMockAuthorizationManager())
            .checkAccess(Right.EDIT, USER_REFERENCE, DOCUMENT_REFERENCE);

        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.putDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID, document));
        assertEquals(401, exception.getResponse().getStatus());
    }

    @Test
    void putNewDenied() throws AccessDeniedException
    {
        String documentId = "newDocumentDenied";
        DocumentReference documentReference =
            new DocumentReference(DigestUtils.sha256Hex(documentId), DOCUMENT_REFERENCE.getLastSpaceReference());
        JSONDocument document = new JSONDocument();
        document.setContent(CONTENT);

        doThrow(new AccessDeniedException(Right.EDIT, USER_REFERENCE, documentReference))
            .when(this.oldcore.getMockAuthorizationManager())
            .checkAccess(Right.EDIT, USER_REFERENCE, documentReference);

        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.putDocument(WIKI_NAME, COLLECTION_NAME, documentId, document));
        assertEquals(401, exception.getResponse().getStatus());
    }
}
