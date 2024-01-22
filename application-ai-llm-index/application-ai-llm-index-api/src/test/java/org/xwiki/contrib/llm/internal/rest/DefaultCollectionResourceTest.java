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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.internal.CurrentUserCollection;
import org.xwiki.contrib.llm.internal.CurrentUserCollectionManager;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.DefaultCollectionManager;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.contrib.llm.rest.JSONCollection;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Integration test for {@link DefaultCollectionResource}.
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
    CurrentUserCollection.class
})
@ReferenceComponentList
class DefaultCollectionResourceTest
{

    private static final String COLLECTION_1 = "collection1";

    private static final String WIKI_NAME = "testwiki";

    private static final DocumentReference COLLECTION_1_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_1), "WebHome");

    private static final String MAIN_WIKI = "xwiki";

    private static final DocumentReference USER_REFERENCE =
        new DocumentReference(MAIN_WIKI, "XWiki", "User");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultCollectionResource collectionResource;

    @Inject
    private CollectionManager collectionManager;

    @BeforeEach
    void setUp() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        this.collectionManager.createCollection(COLLECTION_1).save();
        this.oldcore.getXWikiContext().setWikiId(MAIN_WIKI);
    }

    @Test
    void getCollection() throws XWikiRestException
    {
        JSONCollection collection = this.collectionResource.getCollection(WIKI_NAME, COLLECTION_1);

        assertEquals(COLLECTION_1, collection.getName());
    }

    @Test
    void getCollectionDenied() throws AccessDeniedException
    {
        doThrow(new AccessDeniedException(Right.VIEW, USER_REFERENCE, COLLECTION_1_REFERENCE))
            .when(this.oldcore.getMockContextualAuthorizationManager())
            .checkAccess(Right.VIEW, COLLECTION_1_REFERENCE);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.collectionResource.getCollection(WIKI_NAME, COLLECTION_1));

        assertEquals(403, exception.getResponse().getStatus());
    }

    @Test
    void getNonExistingCollection()
    {
        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.collectionResource.getCollection(WIKI_NAME, "nonexisting"));

        assertEquals(404, exception.getResponse().getStatus());
    }

    @Test
    void putCollection() throws XWikiRestException, IndexException, XWikiException
    {
        String name = "newcollection";
        String chunkingMethod = "none";
        int chunkingMaxSize = 200;

        // Mock the checkSavingDocument call to avoid issues with loading the ObservationManager.
        doNothing().when(this.oldcore.getSpyXWiki()).checkSavingDocument(any(), any(), any());

        JSONCollection jsonCollection = new JSONCollection();
        jsonCollection.setName(name);
        jsonCollection.setChunkingMethod(chunkingMethod);
        jsonCollection.setEmbeddingModel("embedding");
        jsonCollection.setChunkingOverlapOffset(10);
        jsonCollection.setChunkingMaxSize(chunkingMaxSize);

        JSONCollection createdCollection = this.collectionResource.putCollection(WIKI_NAME, name, jsonCollection);
        assertEquals(name, createdCollection.getName());
        assertEquals(chunkingMethod, createdCollection.getChunkingMethod());
        assertEquals(chunkingMaxSize, createdCollection.getChunkingMaxSize());
        // FIXME: full equals fails because the lists all contain a null entry.

        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        Collection savedCollection = this.collectionManager.getCollection(name);
        assertEquals(chunkingMaxSize, savedCollection.getChunkingMaxSize());
        assertEquals(chunkingMethod, savedCollection.getChunkingMethod());
        String rightsCheckMethod = "customRights";
        savedCollection.setRightsCheckMethod(rightsCheckMethod);
        savedCollection.save();
        this.oldcore.getXWikiContext().setWikiId(MAIN_WIKI);

        String updatedEmbeddingModel = "embedding2";
        jsonCollection.setEmbeddingModel(updatedEmbeddingModel);
        JSONCollection updatedCollection = this.collectionResource.putCollection(WIKI_NAME, name, jsonCollection);
        assertEquals(updatedEmbeddingModel, updatedCollection.getEmbeddingModel());
        assertEquals(rightsCheckMethod, updatedCollection.getRightsCheckMethod());
    }
}
