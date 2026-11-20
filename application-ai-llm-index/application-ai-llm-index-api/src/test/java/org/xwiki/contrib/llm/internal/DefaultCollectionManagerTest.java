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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Named;

import org.apache.solr.client.solrj.SolrServerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.authorization.AuthorizationManagerBuilder;
import org.xwiki.contrib.llm.internal.authorization.PublicAuthorizationManagerBuilder;
import org.xwiki.contrib.llm.internal.authorization.XWikiAuthorizationManagerBuilder;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStore;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStoreHelper;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryFilter;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link DefaultCollectionManager}.
 *
 * @version $Id$
 */
@OldcoreTest
@ComponentList({
    DefaultCollection.class,
    XWikiAuthorizationManagerBuilder.class,
    XWikiDocumentStoreHelper.class,
    PublicAuthorizationManagerBuilder.class
})
@ReferenceComponentList
class DefaultCollectionManagerTest
{
    public static final String WIKI_NAME = "testwiki";

    public static final String COLLECTION_ID = "testcollection";

    public static final DocumentReference COLLECTION_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_ID), "WebHome");

    private static final String QUERY = "query";

    private static final String CUSTOM_RIGHTS = "customRights";

    private static final String EMBEDDING_MODEL = "testEmbeddingModel";

    private static final DocumentReference USER_REFERENCE = new DocumentReference(WIKI_NAME, "XWiki", "Alice");

    private static final String VIEWABLE_ID = "testwiki:Space.Viewable;";

    private static final DocumentReference VIEWABLE_REFERENCE = new DocumentReference(WIKI_NAME, "Space", "Viewable");

    private static final String OTHER_VIEWABLE_ID = "testwiki:Space.OtherViewable;";

    private static final DocumentReference OTHER_VIEWABLE_REFERENCE =
        new DocumentReference(WIKI_NAME, "Space", "OtherViewable");

    private static final String DENIED_TRANSLATION_ID = "testwiki:Space.Denied;fr";

    private static final DocumentReference DENIED_REFERENCE = new DocumentReference(WIKI_NAME, "Space", "Denied");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultCollectionManager collectionManager;

    @MockComponent
    private WriteAttribution writeAttribution;

    @MockComponent
    private GroupManager groupManager;

    @MockComponent
    @Named("customRights")
    private AuthorizationManagerBuilder customRightsAuthorizationManagerBuilder;

    @MockComponent
    private SolrConnector solrConnector;

    @MockComponent
    @Named(InternalDocumentStore.NAME)
    private DocumentStore internalDocumentStore;

    @MockComponent
    @Named(XWikiDocumentStore.NAME)
    private DocumentStore xwikiDocumentStore;

    @MockComponent
    @Named("language")
    private QueryFilter languageFilter;

    @MockComponent
    @Named("count")
    private QueryFilter countFilter;

    @MockComponent
    private SpaceReferenceResolver<String> spaceReferenceResolver;

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
    void getCollectionsReturnsTheIdsOfTheCollectionPagesOnly() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        // The query returns the page of a collection, a copy of a collection page in another space, the page of a
        // collection whose id contains a dot (escaped in the full name of the page), the template page, whose
        // collections object carries an empty id, and the first page again.
        Query query = mockCollectionsQuery(List.of("AI.Collections.collection1.WebHome", "Copies.collection2.WebHome",
            "AI.Collections.my\\.dotted.WebHome", "AI.Collections.Code.CollectionsTemplate",
            "AI.Collections.collection1.WebHome"));

        assertEquals(List.of("collection1", "my.dotted"), this.collectionManager.getCollections());

        verify(this.oldcore.getQueryManager()).createQuery("select distinct doc.fullName from XWikiDocument doc, "
            + "BaseObject obj where doc.fullName = obj.name and obj.className = :className", Query.HQL);
        verify(query).bindValue("className", "AI.Collections.Code.CollectionsClass");
    }

    @Test
    void getCollectionsWrapsQueryFailures() throws Exception
    {
        QueryException cause = new QueryException("boom", null, null);
        when(this.oldcore.getQueryManager().createQuery(anyString(), eq(Query.HQL))).thenThrow(cause);

        IndexException exception = assertThrows(IndexException.class, () -> this.collectionManager.getCollections());

        assertSame(cause, exception.getCause());
    }

    private Query mockCollectionsQuery(List<Object> collections) throws QueryException, ComponentLookupException
    {
        Query mockQuery = mock(Query.class);
        when(this.oldcore.getQueryManager().createQuery(anyString(), eq(Query.HQL)))
            .thenReturn(mockQuery);
        when(mockQuery.execute()).thenReturn(collections);
        return mockQuery;
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
    void deleteCollectionWithDocumentsSkipsMissingDocument() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());
        this.collectionManager.createCollection(COLLECTION_ID).save();

        String missingDocumentId = "missing";
        String existingDocumentId = "existing";
        when(this.internalDocumentStore.getDocumentNames(0, -1))
            .thenReturn(List.of(missingDocumentId, existingDocumentId));
        when(this.internalDocumentStore.getDocument(missingDocumentId)).thenReturn(null);
        Document existingDocument = mock(Document.class);
        when(this.internalDocumentStore.getDocument(existingDocumentId)).thenReturn(existingDocument);

        this.collectionManager.deleteCollection(COLLECTION_ID, true);

        verify(this.internalDocumentStore).deleteDocument(existingDocument);
        verify(this.internalDocumentStore, never()).deleteDocument(null);
        assertTrue(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).isNew());
        assertEquals("Skipping the deletion of document [missing] of collection [testcollection] as its page does "
            + "not exist", this.logCapture.getMessage(0));
    }

    @Test
    void deleteCollectionWithDocumentsKeepsThePagesOfAnXWikiStoreCollection() throws Exception
    {
        createAndSaveXWikiStoreCollection("public");
        XWikiContext context = this.oldcore.getXWikiContext();
        // The XWiki store is read-only: deleting one of the pages it lists fails.
        when(this.xwikiDocumentStore.getDocumentNames(0, -1)).thenReturn(List.of(VIEWABLE_ID));
        when(this.xwikiDocumentStore.getDocument(VIEWABLE_ID)).thenReturn(mock(Document.class));
        doThrow(new UnsupportedOperationException("read-only")).when(this.xwikiDocumentStore).deleteDocument(any());
        assertFalse(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).isNew());

        this.collectionManager.deleteCollection(COLLECTION_ID, true);

        assertTrue(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).isNew());
        // The pages that the collection indexes are neither listed nor deleted.
        verify(this.xwikiDocumentStore, never()).getDocumentNames(anyInt(), anyInt());
        verify(this.xwikiDocumentStore, never()).deleteDocument(any());
    }

    @Test
    void deleteCollectionWithDocumentsOnAReadOnlyStoreDeletesTheCollection() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());
        this.collectionManager.createCollection(COLLECTION_ID).save();

        String firstDocumentId = "first";
        String secondDocumentId = "second";
        when(this.internalDocumentStore.getDocumentNames(0, -1))
            .thenReturn(List.of(firstDocumentId, secondDocumentId));
        Document firstDocument = mock(Document.class);
        when(this.internalDocumentStore.getDocument(firstDocumentId)).thenReturn(firstDocument);
        doThrow(new UnsupportedOperationException("read-only")).when(this.internalDocumentStore)
            .deleteDocument(firstDocument);

        this.collectionManager.deleteCollection(COLLECTION_ID, true);

        assertTrue(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).isNew());
        // The first refusal ends the deletion of documents: the next document is not even loaded.
        verify(this.internalDocumentStore).deleteDocument(firstDocument);
        verify(this.internalDocumentStore, never()).getDocument(secondDocumentId);
    }

    @Test
    void clearIndexCoreClearsTheContextWiki() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);

        this.collectionManager.clearIndexCore();

        verify(this.solrConnector).clearIndexCore(WIKI_NAME);
    }

    @Test
    void clearIndexCoreWrapsSolrFailures() throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        SolrServerException cause = new SolrServerException("down");
        doThrow(cause).when(this.solrConnector).clearIndexCore(WIKI_NAME);

        IndexException exception =
            assertThrows(IndexException.class, () -> this.collectionManager.clearIndexCore());

        assertEquals("Failed to clear the index of wiki [testwiki]", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void getDocumentReference()
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);

        assertEquals(COLLECTION_REFERENCE, this.collectionManager.getDocumentReference(COLLECTION_ID));
    }

    @ParameterizedTest
    @MethodSource("hybridSearchLocales")
    void hybridSearch(Locale locale)
        throws QueryException, ComponentLookupException, IndexException, SolrServerException
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
            new Context(collectionId2, "allowed4", null, "url4", "content4", 0.8, List.of(0.7f, 0.8f)),
            new Context(COLLECTION_ID, "allowed3", null, "url3", "content3", 0.7, List.of(0.5f, 0.6f)),
            new Context(collectionId2, "forbidden2", null, "url2", "content2", 0.6, List.of(0.3f, 0.4f)),
            new Context(COLLECTION_ID, "allowed1", null, "url1", "content1", 0.5, List.of(0.1f, 0.2f))
        );

        when(authorization1.canView(Set.of("allowed1", "allowed3")))
            .thenReturn(Map.of("allowed1", true, "allowed3", true));
        when(authorization2.canView(Set.of("forbidden2", "allowed4")))
            .thenReturn(Map.of("forbidden2", false, "allowed4", true));

        when(this.solrConnector.similaritySearch(any(), any(), anyMap(), anyInt(), any())).thenReturn(contextList.subList(0, 3));
        when(this.solrConnector.keywordSearch(any(), anyMap(), anyInt(), any())).thenReturn(contextList.subList(1, 4));

        // The locale-free entry point must forward a null locale; the locale-aware one must forward its locale
        // into both search paths.
        List<Context> result = locale == null
            ? this.collectionManager.hybridSearch("query", List.of(COLLECTION_ID, collectionId2), 4, 3)
            : this.collectionManager.hybridSearch("query", List.of(COLLECTION_ID, collectionId2), 4, 3, locale);

        // The forbidden chunk is removed from both lists before they are merged. The chunk found by both searches
        // comes first, then the first remaining chunk of the semantic search and the one of the keyword search.
        List<Context> expected = List.of(contextList.get(1), contextList.get(0), contextList.get(3));
        assertEquals(expected, result);

        // The collections have no stored document store: each one is searched in the internal store only.
        Map<String, String> storeHintMap =
            Map.of(COLLECTION_ID, InternalDocumentStore.NAME, collectionId2, InternalDocumentStore.NAME);
        verify(this.solrConnector).similaritySearch("query", embeddingModelMap, storeHintMap, 4, locale);
        verify(this.solrConnector).keywordSearch("query", storeHintMap, 3, locale);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", " " })
    void hybridSearchWithBlankQueryReturnsNothingWithoutSearching(String blankQuery) throws Exception
    {
        // The collection is searchable: only the blank query keeps the connector from being called.
        createAndSavePublicCollection(COLLECTION_ID);

        assertEquals(List.of(), this.collectionManager.hybridSearch(blankQuery, List.of(COLLECTION_ID), 4, 3));
        assertEquals(List.of(), this.collectionManager.similaritySearch(blankQuery, List.of(COLLECTION_ID), 4));

        verify(this.solrConnector, never()).similaritySearch(any(), any(), anyMap(), anyInt(), any());
        verify(this.solrConnector, never()).keywordSearch(any(), anyMap(), anyInt(), any());
    }

    @Test
    void hybridSearchRanksByPositionAndNotByScore() throws Exception
    {
        createAndSavePublicCollection(COLLECTION_ID);
        // The scores of the keyword search are on another scale than the ones of the semantic search.
        Context semanticA = new Context(COLLECTION_ID, "documentA", null, "urlA", "contentA", 0.82, List.of(0.1f));
        Context semanticB = new Context(COLLECTION_ID, "documentB", null, "urlB", "contentB", 0.80, List.of(0.1f));
        Context keywordC = new Context(COLLECTION_ID, "documentC", null, "urlC", "contentC", 14.0, List.of(0.1f));
        Context keywordB = new Context(COLLECTION_ID, "documentB", null, "urlB", "contentB", 9.0, List.of(0.1f));
        when(this.solrConnector.similaritySearch(any(), any(), anyMap(), anyInt(), any()))
            .thenReturn(List.of(semanticA, semanticB));
        when(this.solrConnector.keywordSearch(any(), anyMap(), anyInt(), any()))
            .thenReturn(List.of(keywordC, keywordB));

        List<Context> result = this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID), 4, 3);

        // The chunk found by both searches comes first, with the score of the semantic search. The best semantic hit
        // comes before the best keyword hit although its score is lower.
        assertEquals(List.of(semanticB, semanticA, keywordC), result);
    }

    @Test
    void hybridSearchRemovesDuplicatedContentAfterTheRightsCheck() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());
        createAndSaveCollection(COLLECTION_ID, EMBEDDING_MODEL);
        when(this.customRightsAuthorizationManagerBuilder.getConfigurationClassReference())
            .thenReturn(Collection.XCLASS_REFERENCE);
        AuthorizationManager customManager = mock();
        when(this.customRightsAuthorizationManagerBuilder.build(any())).thenReturn(customManager);
        when(customManager.canView(Set.of("forbidden", "allowed")))
            .thenReturn(Map.of("forbidden", false, "allowed", true));

        // The same content is indexed from a document that the user cannot view and from one that the user can view.
        String sharedContent = "shared content";
        Context forbidden = new Context(COLLECTION_ID, "forbidden", null, "url0", sharedContent, 0.9, List.of(0.1f));
        Context allowed = new Context(COLLECTION_ID, "allowed", null, "url1", sharedContent, 7.0, List.of(0.1f));
        when(this.solrConnector.similaritySearch(any(), any(), anyMap(), anyInt(), any()))
            .thenReturn(List.of(forbidden));
        when(this.solrConnector.keywordSearch(any(), anyMap(), anyInt(), any())).thenReturn(List.of(allowed));

        List<Context> result = this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID), 4, 3);

        assertEquals(List.of(allowed), result);
        // Both searches are checked in a single call.
        verify(customManager).canView(Set.of("forbidden", "allowed"));
        verifyNoMoreInteractions(customManager);
    }

    @Test
    void similaritySearchOnSeveralCollectionsKeepsTheOrderOfTheConnector() throws Exception
    {
        String collectionId2 = "testcollection2";
        createAndSavePublicCollection(COLLECTION_ID);
        createAndSavePublicCollection(collectionId2);
        // The scores are deliberately not in descending order: the order of the connector is the one returned.
        List<Context> semanticResults = List.of(
            new Context(collectionId2, "document0", null, "url0", "content0", 0.5, List.of(0.1f)),
            new Context(COLLECTION_ID, "document1", null, "url1", "content1", 0.9, List.of(0.1f)),
            new Context(collectionId2, "document2", null, "url2", "content2", 0.7, List.of(0.1f)),
            new Context(COLLECTION_ID, "document3", null, "url3", "content3", 0.6, List.of(0.1f)));
        when(this.solrConnector.similaritySearch(any(), any(), anyMap(), anyInt(), any()))
            .thenReturn(semanticResults);
        when(this.solrConnector.keywordSearch(any(), anyMap(), anyInt(), any())).thenReturn(List.of());

        List<Context> result =
            this.collectionManager.similaritySearch(QUERY, List.of(COLLECTION_ID, collectionId2), 4);

        assertEquals(semanticResults, result);
        // A semantic search asks the connector for no keyword result.
        Map<String, String> storeHintMap =
            Map.of(COLLECTION_ID, InternalDocumentStore.NAME, collectionId2, InternalDocumentStore.NAME);
        verify(this.solrConnector).keywordSearch(QUERY, storeHintMap, 0, null);
    }

    @Test
    void hybridSearchExcludesACollectionWhoseRightsMethodThrowsUnchecked() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());
        String brokenCollectionId = "brokencollection";
        createAndSaveCollection(COLLECTION_ID, EMBEDDING_MODEL);
        createAndSaveCollection(brokenCollectionId, EMBEDDING_MODEL);

        when(this.customRightsAuthorizationManagerBuilder.getConfigurationClassReference())
            .thenReturn(Collection.XCLASS_REFERENCE);
        AuthorizationManager workingManager = mock();
        when(workingManager.canView(Set.of("document0"))).thenReturn(Map.of("document0", true));
        when(this.customRightsAuthorizationManagerBuilder.build(any())).thenAnswer(invocation -> {
            BaseObject configurationObject = invocation.getArgument(0);
            if (brokenCollectionId.equals(configurationObject.getStringValue("id"))) {
                throw new IllegalStateException("broken rights method");
            }
            return workingManager;
        });
        mockSearchResults("document0");

        List<Context> result =
            this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID, brokenCollectionId), 4, 3);

        assertEquals(List.of("document0"), result.stream().map(Context::documentId).toList());
        // Only the collection with a working rights method is searched.
        Map<String, String> storeHintMap = Map.of(COLLECTION_ID, InternalDocumentStore.NAME);
        verify(this.solrConnector)
            .similaritySearch(QUERY, Map.of(COLLECTION_ID, EMBEDDING_MODEL), storeHintMap, 4, null);
        verify(this.solrConnector).keywordSearch(QUERY, storeHintMap, 3, null);
        assertEquals(1, this.logCapture.size());
        assertEquals("Failed to get authorization manager for collection [brokencollection], excluding it from the"
            + " similarity search: [IllegalStateException: broken rights method]", this.logCapture.getMessage(0));
    }

    @Test
    void hybridSearchWithXWikiStoreAndPublicMethodChecksViewRightOnPages() throws Exception
    {
        createAndSaveXWikiStoreCollection("public");
        mockSearchResults(VIEWABLE_ID, DENIED_TRANSLATION_ID);
        mockViewRight(VIEWABLE_REFERENCE, true);
        mockViewRight(DENIED_REFERENCE, false);

        List<Context> result = this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID), 4, 3);

        assertEquals(List.of(VIEWABLE_ID), result.stream().map(Context::documentId).toList());
        // The right is checked on the page: the locale of the document id is not part of the reference.
        verify(this.oldcore.getMockAuthorizationManager()).hasAccess(Right.VIEW, USER_REFERENCE, DENIED_REFERENCE);
    }

    @Test
    void hybridSearchWithXWikiStoreAndCustomMethodRequiresBothChecks() throws Exception
    {
        createAndSaveXWikiStoreCollection(CUSTOM_RIGHTS);
        mockSearchResults(VIEWABLE_ID, OTHER_VIEWABLE_ID, DENIED_TRANSLATION_ID);
        mockViewRight(VIEWABLE_REFERENCE, true);
        mockViewRight(OTHER_VIEWABLE_REFERENCE, true);
        mockViewRight(DENIED_REFERENCE, false);

        when(this.customRightsAuthorizationManagerBuilder.getConfigurationClassReference())
            .thenReturn(Collection.XCLASS_REFERENCE);
        AuthorizationManager customManager = mock();
        when(this.customRightsAuthorizationManagerBuilder.build(any())).thenReturn(customManager);
        // The custom method refuses a page that the user can view, and would allow the page the user cannot view.
        when(customManager.canView(any())).thenReturn(
            Map.of(VIEWABLE_ID, true, OTHER_VIEWABLE_ID, false, DENIED_TRANSLATION_ID, true));

        List<Context> result = this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID), 4, 3);

        assertEquals(List.of(VIEWABLE_ID), result.stream().map(Context::documentId).toList());
        // The custom method is only asked about the documents whose page the user can view.
        verify(customManager).canView(Set.of(VIEWABLE_ID, OTHER_VIEWABLE_ID));
        verifyNoMoreInteractions(customManager);
    }

    @Test
    void xwikiStoreWithCustomMethodDeniesIdsMissingFromTheCustomResult() throws Exception
    {
        createAndSaveXWikiStoreCollection(CUSTOM_RIGHTS);
        mockViewRight(VIEWABLE_REFERENCE, true);
        mockViewRight(OTHER_VIEWABLE_REFERENCE, true);
        mockViewRight(DENIED_REFERENCE, false);

        when(this.customRightsAuthorizationManagerBuilder.getConfigurationClassReference())
            .thenReturn(Collection.XCLASS_REFERENCE);
        AuthorizationManager customManager = mock();
        when(this.customRightsAuthorizationManagerBuilder.build(any())).thenReturn(customManager);
        when(customManager.canView(Set.of(VIEWABLE_ID, OTHER_VIEWABLE_ID))).thenReturn(Map.of(VIEWABLE_ID, true));

        AuthorizationManager manager = this.collectionManager.getCollection(COLLECTION_ID).getAuthorizationManager();

        // Every requested id is part of the result.
        assertEquals(Map.of(VIEWABLE_ID, true, OTHER_VIEWABLE_ID, false, DENIED_TRANSLATION_ID, false),
            manager.canView(Set.of(VIEWABLE_ID, OTHER_VIEWABLE_ID, DENIED_TRANSLATION_ID)));
    }

    @Test
    void xwikiStoreWithCustomMethodDeniesEverythingWhenTheCustomResultIsNull() throws Exception
    {
        createAndSaveXWikiStoreCollection(CUSTOM_RIGHTS);
        mockViewRight(VIEWABLE_REFERENCE, true);
        mockViewRight(DENIED_REFERENCE, false);

        when(this.customRightsAuthorizationManagerBuilder.getConfigurationClassReference())
            .thenReturn(Collection.XCLASS_REFERENCE);
        AuthorizationManager customManager = mock();
        when(this.customRightsAuthorizationManagerBuilder.build(any())).thenReturn(customManager);
        when(customManager.canView(any())).thenReturn(null);

        AuthorizationManager manager = this.collectionManager.getCollection(COLLECTION_ID).getAuthorizationManager();

        assertEquals(Map.of(VIEWABLE_ID, false, DENIED_TRANSLATION_ID, false),
            manager.canView(Set.of(VIEWABLE_ID, DENIED_TRANSLATION_ID)));
        verify(customManager).canView(Set.of(VIEWABLE_ID));
    }

    @Test
    void xwikiStoreWithCustomMethodDoesNotAskTheCustomMethodWhenNoPageIsViewable() throws Exception
    {
        createAndSaveXWikiStoreCollection(CUSTOM_RIGHTS);
        mockViewRight(DENIED_REFERENCE, false);

        when(this.customRightsAuthorizationManagerBuilder.getConfigurationClassReference())
            .thenReturn(Collection.XCLASS_REFERENCE);
        AuthorizationManager customManager = mock();
        when(this.customRightsAuthorizationManagerBuilder.build(any())).thenReturn(customManager);

        AuthorizationManager manager = this.collectionManager.getCollection(COLLECTION_ID).getAuthorizationManager();

        assertEquals(Map.of(DENIED_TRANSLATION_ID, false), manager.canView(Set.of(DENIED_TRANSLATION_ID)));
        verify(customManager, never()).canView(any());
    }

    @Test
    void hybridSearchWithXWikiStoreAndXWikiMethodChecksViewRightOnce() throws Exception
    {
        createAndSaveXWikiStoreCollection(XWikiAuthorizationManagerBuilder.NAME);
        mockSearchResults(VIEWABLE_ID, DENIED_TRANSLATION_ID);
        mockViewRight(VIEWABLE_REFERENCE, true);
        mockViewRight(DENIED_REFERENCE, false);

        List<Context> result = this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID), 4, 3);

        assertEquals(List.of(VIEWABLE_ID), result.stream().map(Context::documentId).toList());
        verify(this.oldcore.getMockAuthorizationManager()).hasAccess(Right.VIEW, USER_REFERENCE, VIEWABLE_REFERENCE);
        verify(this.oldcore.getMockAuthorizationManager()).hasAccess(Right.VIEW, USER_REFERENCE, DENIED_REFERENCE);
        // Only the chunks indexed from the XWiki document store are searched.
        Map<String, String> storeHintMap = Map.of(COLLECTION_ID, XWikiDocumentStore.NAME);
        verify(this.solrConnector)
            .similaritySearch(QUERY, Map.of(COLLECTION_ID, EMBEDDING_MODEL), storeHintMap, 4, null);
        verify(this.solrConnector).keywordSearch(QUERY, storeHintMap, 3, null);
    }

    @Test
    void hybridSearchWithXWikiStoreAndUnknownMethodExcludesTheCollection() throws Exception
    {
        createAndSaveXWikiStoreCollection("unknownMethod");
        mockSearchResults(VIEWABLE_ID);

        assertEquals(List.of(), this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID), 4, 3));

        verify(this.solrConnector, never()).similaritySearch(any(), any(), anyMap(), anyInt(), any());
        verify(this.solrConnector, never()).keywordSearch(any(), anyMap(), anyInt(), any());
        assertEquals(1, this.logCapture.size());
        assertTrue(this.logCapture.getMessage(0).startsWith("Failed to get authorization manager for collection "
            + "[testcollection], excluding it from the similarity search: "));
    }

    @Test
    void hybridSearchWithInternalStoreAndPublicMethodDoesNotCheckPages() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());
        DefaultCollection collection = this.collectionManager.createCollection(COLLECTION_ID);
        collection.setRightsCheckMethod("public");
        collection.setEmbeddingModel(EMBEDDING_MODEL);
        collection.setAllowGuests(true);
        collection.save();
        mockSearchResults("document1", "document2");

        List<Context> result = this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID), 4, 3);

        assertEquals(Set.of("document1", "document2"),
            result.stream().map(Context::documentId).collect(Collectors.toSet()));
        verifyNoInteractions(this.oldcore.getMockAuthorizationManager());
    }

    @Test
    void filterCollectionBasedOnUserAccessLogsARequestedNameWithoutLineBreaks() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        String requestedName = "bad\r\nname";
        doThrow(new XWikiException()).when(this.oldcore.getSpyXWiki())
            .getDocument(DefaultCollection.getDocumentReference(WIKI_NAME, requestedName), context);

        assertEquals(List.of(), this.collectionManager.filterCollectionbasedOnUserAccess(List.of(requestedName)));

        assertEquals(1, this.logCapture.size());
        assertTrue(this.logCapture.getMessage(0)
            .startsWith("Failed to check access to collection [bad  name], excluding it: [XWikiException: "));
    }

    @Test
    void hybridSearchWithADuplicatedCollectionIdSearchesTheCollectionOnce() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());
        DefaultCollection collection = this.collectionManager.createCollection(COLLECTION_ID);
        collection.setRightsCheckMethod("public");
        collection.setEmbeddingModel(EMBEDDING_MODEL);
        collection.setAllowGuests(true);
        collection.save();
        mockSearchResults("document1");

        // A REST or MCP caller may name the same collection twice.
        List<Context> result =
            this.collectionManager.hybridSearch(QUERY, List.of(COLLECTION_ID, COLLECTION_ID), 4, 3);

        assertEquals(List.of("document1"), result.stream().map(Context::documentId).toList());
        Map<String, String> storeHintMap = Map.of(COLLECTION_ID, InternalDocumentStore.NAME);
        verify(this.solrConnector)
            .similaritySearch(QUERY, Map.of(COLLECTION_ID, EMBEDDING_MODEL), storeHintMap, 4, null);
        verify(this.solrConnector).keywordSearch(QUERY, storeHintMap, 3, null);
    }

    private void createAndSavePublicCollection(String collectionId) throws Exception
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());
        DefaultCollection collection = this.collectionManager.createCollection(collectionId);
        collection.setRightsCheckMethod("public");
        collection.setEmbeddingModel(EMBEDDING_MODEL);
        collection.setAllowGuests(true);
        collection.save();
    }

    private void createAndSaveXWikiStoreCollection(String rightsCheckMethod) throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        context.setUserReference(USER_REFERENCE);
        mockCollectionsQuery(List.of());

        DefaultCollection collection = this.collectionManager.createCollection(COLLECTION_ID);
        collection.setRightsCheckMethod(rightsCheckMethod);
        collection.setEmbeddingModel(EMBEDDING_MODEL);
        collection.setAllowGuests(true);
        collection.save();

        XWikiDocument document = this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).clone();
        document.getXObject(Collection.XCLASS_REFERENCE)
            .setStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME, XWikiDocumentStore.NAME);
        this.oldcore.getSpyXWiki().saveDocument(document, context);
    }

    private void mockSearchResults(String... documentIds) throws SolrServerException
    {
        List<Context> contexts = new ArrayList<>();
        for (int i = 0; i < documentIds.length; i++) {
            contexts.add(new Context(COLLECTION_ID, documentIds[i], null, "url" + i, "content" + i,
                1.0 - i * 0.1, List.of(0.1f)));
        }
        when(this.solrConnector.similaritySearch(any(), any(), anyMap(), anyInt(), any())).thenReturn(contexts);
        when(this.solrConnector.keywordSearch(any(), anyMap(), anyInt(), any())).thenReturn(List.of());
    }

    private void mockViewRight(DocumentReference pageReference, boolean allowed)
    {
        when(this.oldcore.getMockAuthorizationManager().hasAccess(Right.VIEW, USER_REFERENCE, pageReference))
            .thenReturn(allowed);
    }

    private static Stream<Locale> hybridSearchLocales()
    {
        return Stream.of(null, Locale.FRENCH);
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
