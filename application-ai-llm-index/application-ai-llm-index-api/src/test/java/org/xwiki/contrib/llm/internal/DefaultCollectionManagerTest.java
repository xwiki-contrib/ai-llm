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
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.apache.solr.client.solrj.SolrServerException;
import org.junit.jupiter.api.Test;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.authorization.AuthorizationManagerBuilder;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link DefaultCollectionManager}.
 *
 * @version $Id$
 */
@OldcoreTest
@ComponentList({ DefaultCollection.class })
@ReferenceComponentList
class DefaultCollectionManagerTest
{
    public static final String WIKI_NAME = "testwiki";

    public static final String COLLECTION_ID = "testcollection";

    public static final DocumentReference COLLECTION_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_ID), "WebHome");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultCollectionManager collectionManager;

    @MockComponent
    private GroupManager groupManager;

    @MockComponent
    @Named("customRights")
    private AuthorizationManagerBuilder customRightsAuthorizationManagerBuilder;

    @MockComponent
    private SolrConnector solrConnector;

    @Test
    void createCollection() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);

        mockCollectionsQuery(List.of());

        DefaultCollection collection = this.collectionManager.createCollection(COLLECTION_ID);

        assertEquals(COLLECTION_REFERENCE, collection.getCollectionDocument().getDocumentReference());
        assertEquals(COLLECTION_ID, collection.getID());

        collection.save();

        XWikiDocument document = this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context);
        assertNotNull(document.getXObject(Collection.XCLASS_REFERENCE));
        assertEquals(COLLECTION_ID, document.getTitle());
    }

    @Test
    void getCollections() throws Exception
    {
        List<Object> collections = List.of("collection1", "collection2");
        mockCollectionsQuery(collections);

        assertEquals(collections, this.collectionManager.getCollections());
    }

    private void mockCollectionsQuery(List<Object> collections) throws QueryException, ComponentLookupException
    {
        Query mockQuery = mock(Query.class);
        when(this.oldcore.getQueryManager().createQuery(anyString(), eq(Query.HQL)))
            .thenReturn(mockQuery);
        when(mockQuery.execute()).thenReturn(collections);
    }

    @Test
    void getCollection() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        XWikiDocument doc = new XWikiDocument(COLLECTION_REFERENCE);
        String title = "Test Collection";
        doc.setTitle(title);
        this.oldcore.getSpyXWiki().saveDocument(doc, context);

        DefaultCollection collection = this.collectionManager.getCollection(COLLECTION_ID);
        assertEquals(title, collection.getTitle());
    }

    @Test
    void deleteCollection() throws Exception
    {
        // Create a collection
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());

        this.collectionManager.createCollection(COLLECTION_ID).save();
        mockCollectionsQuery(List.of(COLLECTION_ID));

        assertFalse(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).isNew());

        // Delete the collection
        this.collectionManager.deleteCollection(COLLECTION_ID, false);

        // Check that the collection has been deleted
        assertTrue(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).isNew());
    }

    @Test
    void getDocumentReference()
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);

        assertEquals(COLLECTION_REFERENCE, this.collectionManager.getDocumentReference(COLLECTION_ID));
    }

    @Test
    void similaritySearch() throws QueryException, ComponentLookupException, IndexException, SolrServerException
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);

        mockCollectionsQuery(List.of());
        String collectionId2 = "testcollection2";
        Map<String, String> embeddingModelMap =
            Map.of(COLLECTION_ID, "testEmbeddingModel1", collectionId2, "testEmbeddingModel2");
        embeddingModelMap.forEach(this::createAndSaveCollection);

        when(this.customRightsAuthorizationManagerBuilder.getConfigurationClassReference())
            .thenReturn(Collection.XCLASS_REFERENCE);
        AuthorizationManager authorization1 = mock();
        AuthorizationManager authorization2 = mock();
        Map<String, Object> authorizationManagers =
            Map.of(COLLECTION_ID, authorization1, collectionId2, authorization2);
        when(this.customRightsAuthorizationManagerBuilder.build(any())).thenAnswer(invocation -> {
            BaseObject configurationObject = invocation.getArgument(0);
            return authorizationManagers.get(configurationObject.getStringValue("id"));
        });

        assertEquals(authorizationManagers.get(COLLECTION_ID),
            this.collectionManager.getCollection(COLLECTION_ID).getAuthorizationManager());
        assertEquals(authorizationManagers.get(collectionId2),
            this.collectionManager.getCollection(collectionId2).getAuthorizationManager());

        List<Context> contextList = List.of(
            new Context(collectionId2, "allowed4", "url4", "content4", 0.8, List.of(0.7f, 0.8f)),
            new Context(COLLECTION_ID, "allowed3", "url3", "content3", 0.7, List.of(0.5f, 0.6f)),
            new Context(collectionId2, "forbidden2", "url2", "content2", 0.6, List.of(0.3f, 0.4f)),
            new Context(COLLECTION_ID, "allowed1", "url1", "content1", 0.5, List.of(0.1f, 0.2f))
        );

        when(authorization1.canView(Set.of("allowed1", "allowed3")))
            .thenReturn(Map.of("allowed1", true, "allowed3", true));
        when(authorization2.canView(Set.of("forbidden2", "allowed4")))
            .thenReturn(Map.of("forbidden2", false, "allowed4", true));

        when(this.solrConnector.similaritySearch(any(), any(), anyInt())).thenReturn(contextList);

        List<Context> result =
            this.collectionManager.similaritySearch("query", List.of(COLLECTION_ID, collectionId2), 10);

        List<Context> expected = List.of(contextList.get(0), contextList.get(1), contextList.get(3));
        assertEquals(expected, result);

        verify(this.solrConnector).similaritySearch("query", embeddingModelMap, 10);
    }

    private void createAndSaveCollection(String collectionId, String embeddingModel)
    {
        try {
            DefaultCollection collection2 = this.collectionManager.createCollection(collectionId);
            collection2.setRightsCheckMethod("customRights");
            collection2.setEmbeddingModel(embeddingModel);
            collection2.setAllowGuests(true);
            collection2.save();
        } catch (IndexException e) {
            throw new RuntimeException(e);
        }
    }
}
