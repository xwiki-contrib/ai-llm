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
import javax.ws.rs.WebApplicationException;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.authorization.AuthorizationManagerBuilder;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.contrib.llm.internal.CurrentUserCollection;
import org.xwiki.contrib.llm.internal.CurrentUserCollectionManager;
import org.xwiki.contrib.llm.internal.CurrentUserDocument;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.DefaultCollectionManager;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.contrib.llm.rest.JSONCollection;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.group.GroupManager;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    CurrentUserDocument.class
})

@ReferenceComponentList
class DefaultCollectionResourceTest
{
    private static final String COLLECTION_1 = "collection1";

    private static final String WIKI_NAME = "testwiki";

    private static final String AI_SPACE = "AI";

    private static final String COLLECTIONS_SPACE = "Collections";

    public static final SpaceReference COLLECTION_1_DOCUMENTS_SPACE = new SpaceReference(WIKI_NAME, AI_SPACE,
        COLLECTIONS_SPACE, COLLECTION_1, "Documents");

    private static final DocumentReference COLLECTION_1_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of(AI_SPACE, COLLECTIONS_SPACE, COLLECTION_1), "WebHome");

    private static final String MAIN_WIKI = "xwiki";

    private static final DocumentReference USER_REFERENCE =
        new DocumentReference(MAIN_WIKI, "XWiki", "User");

    private static final String RIGHTS_CHECK_METHOD = "customRights";

    private static final String AUTHORIZATION_PARAMETER_NAME = "param1";

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultCollectionResource collectionResource;

    @MockComponent
    private SolrConnector solrConnector;

    @MockComponent
    private AiLLMSolrCoreInitializer aillmSolrCoreInitializer;

    @MockComponent
    private GroupManager groupManager;

    @MockComponent
    @Named("customRights")
    private AuthorizationManagerBuilder customRightsAuthorizationManagerBuilder;

    @Mock
    private Query collectionsQuery;

    @Mock
    private Query documentsQuery;

    @Inject
    private CollectionManager collectionManager;

    @JsonAutoDetect(creatorVisibility = JsonAutoDetect.Visibility.ANY)
    private record RightsCheckConfiguration(String param1)
    {
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        this.collectionManager.createCollection(COLLECTION_1).save();
        this.oldcore.getXWikiContext().setWikiId(MAIN_WIKI);

        // Mock the query to return the collection.
        when(this.oldcore.getQueryManager()
            .createQuery(contains("AI.Collections.Code.CollectionsClass"), eq(Query.HQL)))
            .thenReturn(this.collectionsQuery);
        when(this.collectionsQuery.execute()).thenReturn(List.of(COLLECTION_1));

        // Set up the document query mock.
        when(this.oldcore.getQueryManager().createQuery(contains("AI.Documents.Code.DocumentsClass"), eq(Query.HQL)))
            .thenReturn(this.documentsQuery);
        when(this.documentsQuery.execute()).thenReturn(List.of());

        // Mock access rights to allow access to the collection in lists.
        when(this.oldcore.getMockContextualAuthorizationManager().hasAccess(Right.VIEW, COLLECTION_1_REFERENCE))
            .thenReturn(true);

        // Mock the checking calls to avoid issues with loading the ObservationManager.
        doNothing().when(this.oldcore.getSpyXWiki()).checkSavingDocument(any(), any(), any());
        doNothing().when(this.oldcore.getSpyXWiki()).checkDeletingDocument(any(), any(), any());
    }

    @Test
    void getCollectionFromWiki() throws XWikiRestException
    {
        JSONCollection collection = this.collectionResource.getCollection(WIKI_NAME, COLLECTION_1);

        assertEquals(COLLECTION_1, collection.getID());
    }

    @Test
    void getCollectionDenied() throws AccessDeniedException
    {
        doThrow(new AccessDeniedException(Right.VIEW, USER_REFERENCE, COLLECTION_1_REFERENCE))
            .when(this.oldcore.getMockContextualAuthorizationManager())
            .checkAccess(Right.VIEW, COLLECTION_1_REFERENCE);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.collectionResource.getCollection(WIKI_NAME, COLLECTION_1));

        assertEquals(401, exception.getResponse().getStatus());
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
        String id = "newcollection";
        String chunkingMethod = "none";
        int chunkingMaxSize = 200;

        // Prepare a collection with some values.
        JSONCollection jsonCollection = new JSONCollection();
        jsonCollection.setID(id);
        jsonCollection.setChunkingMethod(chunkingMethod);
        jsonCollection.setEmbeddingModel("embedding");
        jsonCollection.setChunkingOverlapOffset(10);
        jsonCollection.setChunkingMaxSize(chunkingMaxSize);

        // Test creating the collection and verify the response.
        JSONCollection createdCollection = this.collectionResource.putCollection(WIKI_NAME, id, jsonCollection);
        assertEquals(id, createdCollection.getID());
        assertEquals(chunkingMethod, createdCollection.getChunkingMethod());
        assertEquals(chunkingMaxSize, createdCollection.getChunkingMaxSize());

        // Verify that the collection got actually created on the "server" side.
        Collection savedCollection = getCollectionFromWiki(id);
        assertEquals(chunkingMaxSize, savedCollection.getChunkingMaxSize());
        assertEquals(chunkingMethod, savedCollection.getChunkingMethod());
        // Update a property directly in the collection.
        savedCollection.setRightsCheckMethod(RIGHTS_CHECK_METHOD);
        savedCollection.save();

        // Try updating just a single property.
        String updatedEmbeddingModel = "embedding2";
        jsonCollection = new JSONCollection();
        jsonCollection.setEmbeddingModel(updatedEmbeddingModel);
        JSONCollection updatedCollection = this.collectionResource.putCollection(WIKI_NAME, id, jsonCollection);
        // Verify that the update succeeded but other properties remain unchanged.
        assertEquals(updatedEmbeddingModel, updatedCollection.getEmbeddingModel());
        assertEquals(RIGHTS_CHECK_METHOD, updatedCollection.getRightsCheckMethod());
        assertEquals(chunkingMethod, updatedCollection.getChunkingMethod());
        assertEquals(chunkingMaxSize, updatedCollection.getChunkingMaxSize());
    }

    @Test
    void putAuthorizationManagerParameters() throws XWikiException, XWikiRestException, IndexException
    {
        String id = "authorizationCollection";

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode rightsCheckMethodConfiguration = objectMapper.createObjectNode();
        String authorizationParameter = "value1";
        rightsCheckMethodConfiguration.put(AUTHORIZATION_PARAMETER_NAME, authorizationParameter);

        JSONCollection jsonCollection = new JSONCollection();
        jsonCollection.setID(id);
        jsonCollection.setRightsCheckMethod(RIGHTS_CHECK_METHOD);
        jsonCollection.setRightsCheckMethodConfiguration(rightsCheckMethodConfiguration);

        doReturn(RightsCheckConfiguration.class)
            .when(this.customRightsAuthorizationManagerBuilder).getConfigurationType();

        // Create a document with the configuration class.
        DocumentReference configurationClassReference =
            new DocumentReference(WIKI_NAME, "AI", "AuthorizationConfiguration");
        XWikiDocument configurationClassDocument = new XWikiDocument(configurationClassReference);
        configurationClassDocument.getXClass().addTextField(AUTHORIZATION_PARAMETER_NAME, "Param1", 30);
        this.oldcore.getSpyXWiki().saveDocument(configurationClassDocument, "Create configuration class",
            this.oldcore.getXWikiContext());

        when(this.customRightsAuthorizationManagerBuilder.getConfigurationClassReference())
            .thenReturn(configurationClassReference);


        when(this.customRightsAuthorizationManagerBuilder.getConfiguration(any()))
            .thenAnswer(invocation -> {
                BaseObject object = invocation.getArgument(0);
                String param1 = object.getStringValue(AUTHORIZATION_PARAMETER_NAME);
                return new RightsCheckConfiguration(param1);
            });
        doAnswer(invocation -> {
            BaseObject object = invocation.getArgument(0);
            RightsCheckConfiguration configuration = invocation.getArgument(1);
            object.setStringValue(AUTHORIZATION_PARAMETER_NAME, configuration.param1());
            return null;
        }).when(this.customRightsAuthorizationManagerBuilder).setConfiguration(any(), any());

        // Test creating the collection and verify the response.
        JSONCollection createdCollection = this.collectionResource.putCollection(WIKI_NAME, id, jsonCollection);
        assertEquals(id, createdCollection.getID());
        assertEquals(RIGHTS_CHECK_METHOD, createdCollection.getRightsCheckMethod());
        assertEquals(rightsCheckMethodConfiguration, createdCollection.getRightsCheckMethodConfiguration());

        // Verify that the collection got actually created on the "server" side.
        Collection savedCollection = getCollectionFromWiki(id);
        assertEquals(RIGHTS_CHECK_METHOD, savedCollection.getRightsCheckMethod());
        Object authorizationConfiguration = savedCollection.getAuthorizationConfiguration();
        assertInstanceOf(RightsCheckConfiguration.class, authorizationConfiguration);
        assertEquals(authorizationParameter, ((RightsCheckConfiguration) authorizationConfiguration).param1());

        AuthorizationManager authorizationManager = mock();
        when(this.customRightsAuthorizationManagerBuilder.build(any())).thenReturn(authorizationManager);

        assertSame(authorizationManager, savedCollection.getAuthorizationManager());

        // Get the argument passed to the builder.
        ArgumentCaptor<BaseObject> configurationObjectCaptor = ArgumentCaptor.forClass(BaseObject.class);
        verify(this.customRightsAuthorizationManagerBuilder, times(2))
            .getConfiguration(configurationObjectCaptor.capture());
        configurationObjectCaptor.getAllValues().forEach(object -> {
            assertEquals(configurationClassReference, object.getXClassReference());
            assertEquals(authorizationParameter, object.getStringValue(AUTHORIZATION_PARAMETER_NAME));
        });
    }

    @Test
    void putCollectionDenied() throws AccessDeniedException, IndexException
    {
        doThrow(new AccessDeniedException(Right.EDIT, USER_REFERENCE, COLLECTION_1_REFERENCE))
            .when(this.oldcore.getMockContextualAuthorizationManager())
            .checkAccess(Right.EDIT, COLLECTION_1_REFERENCE);

        JSONCollection collection = new JSONCollection();
        collection.setEmbeddingModel("embeddingModel");
        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.collectionResource.putCollection(WIKI_NAME, COLLECTION_1, collection));

        assertEquals(401, exception.getResponse().getStatus());

        // Verify that the collection was not updated.
        assertEquals("", getCollectionFromWiki(COLLECTION_1).getChunkingMethod());
    }

    @Test
    void deleteCollection() throws XWikiRestException, IndexException
    {
        assertNotNull(getCollectionFromWiki(COLLECTION_1));

        this.collectionResource.deleteCollection(WIKI_NAME, COLLECTION_1);

        assertNull(getCollectionFromWiki(COLLECTION_1));
    }

    @Test
    void deleteCollectionDenied() throws AccessDeniedException, IndexException
    {
        doThrow(new AccessDeniedException(Right.DELETE, USER_REFERENCE, COLLECTION_1_REFERENCE))
            .when(this.oldcore.getMockContextualAuthorizationManager())
            .checkAccess(Right.DELETE, COLLECTION_1_REFERENCE);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.collectionResource.deleteCollection(WIKI_NAME, COLLECTION_1));

        assertEquals(401, exception.getResponse().getStatus());

        // Verify that the collection was not deleted.
        assertNotNull(getCollectionFromWiki(COLLECTION_1));
    }

    @Test
    void getDocuments() throws QueryException, XWikiRestException
    {
        String doc1 = "doc1";
        String doc2 = "doc2";
        List<Object> documentList = List.of(doc1, doc2);
        when(this.documentsQuery.execute()).thenReturn(documentList);

        when(this.oldcore.getMockContextualAuthorizationManager().hasAccess(eq(Right.VIEW),
                argThat(entityReference -> COLLECTION_1_DOCUMENTS_SPACE.equals(entityReference.getParent()))))
            .thenReturn(true);

        assertEquals(documentList, this.collectionResource.getDocuments(WIKI_NAME, COLLECTION_1, 0, 10));
        assertEquals(List.of(doc1), this.collectionResource.getDocuments(WIKI_NAME, COLLECTION_1, 0, 1));
        assertEquals(List.of(doc2), this.collectionResource.getDocuments(WIKI_NAME, COLLECTION_1, 1, 10));
    }

    @Test
    void getDocumentsViewRestricted() throws QueryException, XWikiRestException
    {
        String accessibleDoc = "accessible";
        String inaccessibleDoc = "inaccessible";
        List<Object> documentList = List.of(accessibleDoc, inaccessibleDoc);
        when(this.documentsQuery.execute()).thenReturn(documentList);

        when(this.oldcore.getMockContextualAuthorizationManager().hasAccess(eq(Right.VIEW),
                argThat(entityReference -> DigestUtils.sha256Hex(accessibleDoc).equals(entityReference.getName())
                    && COLLECTION_1_DOCUMENTS_SPACE.equals(entityReference.getParent()))))
                .thenReturn(true);
        assertEquals(List.of(accessibleDoc), this.collectionResource.getDocuments(WIKI_NAME, COLLECTION_1, 0, 10));
    }

    private Collection getCollectionFromWiki(String collectionName) throws IndexException
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        String oldWiki = context.getWikiId();
        try {
            context.setWikiId(DefaultCollectionResourceTest.WIKI_NAME);
            return this.collectionManager.getCollection(collectionName);
        } finally {
            context.setWikiId(oldWiki);
        }
    }
}
