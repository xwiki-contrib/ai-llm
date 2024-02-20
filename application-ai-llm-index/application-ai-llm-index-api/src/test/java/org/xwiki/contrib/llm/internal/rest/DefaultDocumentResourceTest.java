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
import javax.ws.rs.WebApplicationException;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.ChunkingUtils;
import org.xwiki.contrib.llm.internal.CurrentUserCollection;
import org.xwiki.contrib.llm.internal.CurrentUserCollectionManager;
import org.xwiki.contrib.llm.internal.CurrentUserDocument;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.DefaultCollectionManager;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.contrib.llm.rest.JSONDocument;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.UserReferenceSerializer;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    CurrentUserDocument.class
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
    private GroupManager groupManager;

    @BeforeEach
    void setUp() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        this.collectionManager.createCollection(COLLECTION_NAME).save();
        Document internalDocument = this.collectionManager.getCollection(COLLECTION_NAME).newDocument(DOCUMENT_ID);
        internalDocument.setContent(CONTENT);
        internalDocument.setLanguage(LANGUAGE);
        internalDocument.setMimetype(MIME_TYPE);
        internalDocument.save();
        this.oldcore.getXWikiContext().setWikiId(MAIN_WIKI);

        // Do nothing when checking for saving or deletion.
        doNothing().when(this.oldcore.getSpyXWiki()).checkSavingDocument(any(), any(), any(), anyBoolean(), any());
        doNothing().when(this.oldcore.getSpyXWiki()).checkDeletingDocument(any(), any(), any());
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
    void getDocumentNotFound() throws XWikiRestException
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
            .when(this.oldcore.getMockContextualAuthorizationManager())
            .checkAccess(Right.VIEW, DOCUMENT_REFERENCE);

        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.getDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID));
        assertEquals(403, exception.getResponse().getStatus());
    }

    @Test
    void deleteDocument() throws XWikiRestException
    {
        // Delete it.
        this.documentResource.deleteDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID);

        verify(this.solrConnector).deleteChunksByDocId(DOCUMENT_ID);

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
            .when(this.oldcore.getMockContextualAuthorizationManager())
            .checkAccess(Right.DELETE, DOCUMENT_REFERENCE);

        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.deleteDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID));
        assertEquals(403, exception.getResponse().getStatus());

        // Verify that the document still exists.
        assertEquals(DOCUMENT_ID,
            this.documentResource.getDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID).getTitle());
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void putNewDocument() throws XWikiRestException
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
    void updateDeniedExisting() throws XWikiRestException, AccessDeniedException
    {
        JSONDocument document = new JSONDocument();
        document.setContent(CONTENT);

        doThrow(new AccessDeniedException(Right.EDIT, USER_REFERENCE, DOCUMENT_REFERENCE))
            .when(this.oldcore.getMockContextualAuthorizationManager())
            .checkAccess(Right.EDIT, DOCUMENT_REFERENCE);

        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.putDocument(WIKI_NAME, COLLECTION_NAME, DOCUMENT_ID, document));
        assertEquals(403, exception.getResponse().getStatus());
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
            .when(this.oldcore.getMockContextualAuthorizationManager())
            .checkAccess(Right.EDIT, documentReference);

        WebApplicationException exception =
            assertThrows(WebApplicationException.class,
                () -> this.documentResource.putDocument(WIKI_NAME, COLLECTION_NAME, documentId, document));
        assertEquals(403, exception.getResponse().getStatus());
    }
}
