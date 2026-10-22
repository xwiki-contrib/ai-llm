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
package org.xwiki.contrib.llm;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Provider;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.XWikiSolrCore;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SolrConnector}.
 *
 * @version $Id$
 */
@ComponentTest
class SolrConnectorTest
{
    private static final String DOC_ID = "mywiki:AI.Documents.MyDocument;fr";

    private static final String DOC_URL = "https://wiki.example.com/MyDocument";

    private static final String CONTENT = "Contenu";

    private static final String QUERY = "query";

    private static final String MODEL = "model1";

    private static final String INTERNAL_COLLECTION = "internalCol";

    private static final String XWIKI_COLLECTION = "xwikiCol";

    private static final String INTERNAL_STORE = "internal";

    private static final String XWIKI_STORE = "xwiki";

    private static final String STORE_HINT_FIELD = "storeHint";

    private static final String MIXED_STORE_FILTER_QUERY =
        "(collection:escaped(internalCol) AND (storeHint:escaped(internal) OR (*:* AND -storeHint:[* TO *])))"
            + " OR (collection:escaped(xwikiCol) AND storeHint:escaped(xwiki))";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.ERROR);

    @InjectMockComponents
    private SolrConnector solrConnector;

    @MockComponent
    private Solr solr;

    @MockComponent
    private SolrUtils solrUtils;

    @MockComponent
    private EmbeddingsUtils embeddingsUtils;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @Mock
    private XWikiContext xcontext;

    @Mock
    private XWikiSolrCore core;

    @Mock
    private SolrClient client;

    @Mock
    private QueryResponse queryResponse;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE)).thenReturn(this.core);
        when(this.core.getClient()).thenReturn(this.client);
        when(this.client.query(any(SolrQuery.class), eq(SolrRequest.METHOD.POST)))
            .thenReturn(this.queryResponse);
        when(this.contextProvider.get()).thenReturn(this.xcontext);
        when(this.xcontext.getWikiId()).thenReturn("mywiki");
    }

    @Test
    void keywordSearchMapsTheLanguageField() throws Exception
    {
        SolrDocument document = new SolrDocument();
        document.setField(AiLLMSolrCoreInitializer.FIELD_COLLECTION, "col1");
        document.setField(AiLLMSolrCoreInitializer.FIELD_DOC_ID, DOC_ID);
        document.setField(AiLLMSolrCoreInitializer.FIELD_LANGUAGE, "fr");
        document.setField(AiLLMSolrCoreInitializer.FIELD_DOC_URL, DOC_URL);
        document.setField(AiLLMSolrCoreInitializer.FIELD_CONTENT, CONTENT);
        document.setField("score", 0.42f);
        SolrDocumentList documents = new SolrDocumentList();
        documents.add(document);
        when(this.queryResponse.getResults()).thenReturn(documents);

        List<Context> results = this.solrConnector.keywordSearch("query", List.of("col1"), 5);

        assertEquals(1, results.size());
        Context context = results.get(0);
        assertEquals("col1", context.collectionId());
        assertEquals(DOC_ID, context.documentId());
        assertEquals("fr", context.language());
        assertEquals(DOC_URL, context.url());
        assertEquals(CONTENT, context.content());
        assertEquals(0.42, context.similarityScore(), 0.0001);
        assertNull(context.vector());
        // The platform caches one client per core and owns its lifecycle - the connector must never close it
        // (closing a remote HttpSolrClient destroys the shared connection pool for every later call).
        verify(this.client, never()).close();
    }

    @Test
    void keywordSearchLeavesTheLanguageNullWhenTheFieldIsMissing() throws Exception
    {
        SolrDocument document = new SolrDocument();
        document.setField(AiLLMSolrCoreInitializer.FIELD_COLLECTION, "col1");
        document.setField(AiLLMSolrCoreInitializer.FIELD_DOC_ID, "uploaded-doc");
        document.setField(AiLLMSolrCoreInitializer.FIELD_CONTENT, CONTENT);
        document.setField("score", 0.1f);
        SolrDocumentList documents = new SolrDocumentList();
        documents.add(document);
        when(this.queryResponse.getResults()).thenReturn(documents);

        List<Context> results = this.solrConnector.keywordSearch("query", List.of("col1"), 5);

        assertEquals(1, results.size());
        assertNull(results.get(0).language());
    }

    @Test
    void similaritySearchAppliesLocaleAsFilterQueryNotInKnnQuery() throws Exception
    {
        when(this.embeddingsUtils.computeEmbeddings(anyString(), any(), any(), any()))
            .thenReturn(new double[] { 0.1, 0.2 });
        when(this.solrUtils.toCompleteFilterQueryString("fr")).thenReturn("escaped(fr)");
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());

        this.solrConnector.similaritySearch("query", Map.of("col1", "model1"), 5, Locale.FRENCH);

        SolrQuery query = capturedQuery();
        // The locale restriction must be a filter query (a KNN pre-filter), never part of the knn main query.
        assertTrue(Arrays.asList(query.getFilterQueries()).contains("language:escaped(fr)"),
            Arrays.toString(query.getFilterQueries()));
        assertTrue(query.getQuery().startsWith("{!knn"), query.getQuery());
        assertFalse(query.getQuery().contains("language"), query.getQuery());
    }

    @Test
    void similaritySearchLogsAnEmbeddingFailureWithoutTheQuery() throws Exception
    {
        when(this.embeddingsUtils.computeEmbeddings(anyString(), any(), any(), any()))
            .thenThrow(new IndexException("Failed to compute embeddings for [1] texts with model [m]"));

        List<Context> results = this.solrConnector.similaritySearch("secret question", Map.of("col1", MODEL), 5);

        assertTrue(results.isEmpty());
        assertEquals(1, this.logCapture.size());
        assertEquals("Similarity search failed", this.logCapture.getMessage(0));
        // The failure is logged with its exception, whose message names the text count, not the query.
        assertNotNull(this.logCapture.getLogEvent(0).getThrowableProxy());
        assertFalse(this.logCapture.getLogEvent(0).getThrowableProxy().getMessage().contains("secret question"));
    }

    @Test
    void searchLogsAFailureWithItsException() throws Exception
    {
        when(this.client.query(any())).thenThrow(new SolrServerException("Solr down"));

        List<Context> results = this.solrConnector.search("*:*", 5, false);

        assertTrue(results.isEmpty());
        assertEquals(1, this.logCapture.size());
        assertEquals("Search failed", this.logCapture.getMessage(0));
        assertNotNull(this.logCapture.getLogEvent(0).getThrowableProxy());
    }

    @Test
    void similaritySearchWithoutLocaleAddsNoLanguageFilter() throws Exception
    {
        when(this.embeddingsUtils.computeEmbeddings(anyString(), any(), any(), any()))
            .thenReturn(new double[] { 0.1, 0.2 });
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());

        this.solrConnector.similaritySearch("query", Map.of("col1", "model1"), 5);

        SolrQuery query = capturedQuery();
        assertTrue(Arrays.stream(query.getFilterQueries()).noneMatch(fq -> fq.startsWith("language:")),
            Arrays.toString(query.getFilterQueries()));
    }

    @Test
    void keywordSearchAppliesLocaleAsFilterQueryNotInMainQuery() throws Exception
    {
        when(this.solrUtils.toCompleteFilterQueryString("fr")).thenReturn("escaped(fr)");
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());

        this.solrConnector.keywordSearch("query", List.of("col1"), 5, Locale.FRENCH);

        SolrQuery query = capturedQuery();
        assertTrue(Arrays.asList(query.getFilterQueries()).contains("language:escaped(fr)"),
            Arrays.toString(query.getFilterQueries()));
        assertFalse(query.getQuery().contains("language"), query.getQuery());
    }

    @Test
    void keywordSearchWithoutLocaleAddsNoLanguageFilter() throws Exception
    {
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());

        this.solrConnector.keywordSearch("query", List.of("col1"), 5);

        SolrQuery query = capturedQuery();
        assertTrue(Arrays.stream(query.getFilterQueries()).noneMatch(fq -> fq.startsWith("language:")),
            Arrays.toString(query.getFilterQueries()));
    }

    @Test
    void keywordSearchAppliesTheWikiFilterLikeTheSemanticPath() throws Exception
    {
        // Collection names in the filter query are not wiki-qualified: without the wiki filter, a
        // same-named collection on another wiki leaked its chunks into keyword results (LLMAI-164).
        when(this.solrUtils.toCompleteFilterQueryString("mywiki")).thenReturn("escaped(mywiki)");
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());

        this.solrConnector.keywordSearch("query", List.of("col1"), 5);

        SolrQuery query = capturedQuery();
        assertTrue(Arrays.stream(query.getFilterQueries()).anyMatch(fq -> fq.contains("wiki:escaped(mywiki)")),
            Arrays.toString(query.getFilterQueries()));
    }

    @Test
    void keywordSearchWithStoreHintsRestrictsEachCollectionToItsStore() throws Exception
    {
        mockEscaping(INTERNAL_COLLECTION, XWIKI_COLLECTION, INTERNAL_STORE, XWIKI_STORE);
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());

        this.solrConnector.keywordSearch(QUERY, mixedStoreHints(), 5, null);

        SolrQuery query = capturedQuery();
        assertTrue(Arrays.asList(query.getFilterQueries()).contains(MIXED_STORE_FILTER_QUERY),
            Arrays.toString(query.getFilterQueries()));
        assertFalse(query.getQuery().contains(STORE_HINT_FIELD), query.getQuery());
    }

    @Test
    void similaritySearchWithStoreHintsRestrictsEachCollectionToItsStoreAsFilterQuery() throws Exception
    {
        when(this.embeddingsUtils.computeEmbeddings(anyString(), any(), any(), any()))
            .thenReturn(new double[] { 0.1, 0.2 });
        mockEscaping(INTERNAL_COLLECTION, XWIKI_COLLECTION, INTERNAL_STORE, XWIKI_STORE);
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());
        Map<String, String> embeddingModels = new LinkedHashMap<>();
        embeddingModels.put(INTERNAL_COLLECTION, MODEL);
        embeddingModels.put(XWIKI_COLLECTION, MODEL);

        this.solrConnector.similaritySearch(QUERY, embeddingModels, mixedStoreHints(), 5, null);

        SolrQuery query = capturedQuery();
        // The store restriction must be a filter query (a KNN pre-filter), never part of the knn main query.
        assertTrue(Arrays.asList(query.getFilterQueries()).contains(MIXED_STORE_FILTER_QUERY),
            Arrays.toString(query.getFilterQueries()));
        assertTrue(query.getQuery().startsWith("{!knn"), query.getQuery());
        assertFalse(query.getQuery().contains(STORE_HINT_FIELD), query.getQuery());
    }

    @Test
    void similaritySearchWithStoreHintsSkipsCollectionsWithoutHint() throws Exception
    {
        when(this.embeddingsUtils.computeEmbeddings(anyString(), any(), any(), any()))
            .thenReturn(new double[] { 0.1, 0.2 });
        mockEscaping(XWIKI_COLLECTION, XWIKI_STORE);
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());
        Map<String, String> storeHints = new HashMap<>();
        storeHints.put(XWIKI_COLLECTION, XWIKI_STORE);
        storeHints.put("blankHintCol", " ");
        storeHints.put("nullHintCol", null);

        this.solrConnector.similaritySearch(QUERY,
            Map.of(XWIKI_COLLECTION, MODEL, "blankHintCol", MODEL, "nullHintCol", MODEL, "noHintCol", MODEL),
            storeHints, 5, null);

        SolrQuery query = capturedQuery();
        assertTrue(Arrays.asList(query.getFilterQueries())
                .contains("(collection:escaped(xwikiCol) AND storeHint:escaped(xwiki))"),
            Arrays.toString(query.getFilterQueries()));
        assertTrue(Arrays.stream(query.getFilterQueries()).noneMatch(fq -> fq.contains("HintCol")),
            Arrays.toString(query.getFilterQueries()));
    }

    @Test
    void similaritySearchWithStoreHintsSearchesNothingWhenNoCollectionHasAHint() throws Exception
    {
        assertEquals(List.of(),
            this.solrConnector.similaritySearch(QUERY, Map.of("col1", MODEL), Map.of(), 5, null));

        verify(this.client, never()).query(any());
        verify(this.embeddingsUtils, never()).computeEmbeddings(anyString(), any(), any(), any());
    }

    @Test
    void keywordSearchWithStoreHintsSearchesNothingWhenNoCollectionHasAHint() throws Exception
    {
        Map<String, String> storeHints = new HashMap<>();
        storeHints.put("blankHintCol", "");
        storeHints.put("nullHintCol", null);

        // Without any collection there would be no collection filter at all: the search must not run.
        assertEquals(List.of(), this.solrConnector.keywordSearch(QUERY, storeHints, 5, null));
        assertEquals(List.of(), this.solrConnector.keywordSearch(QUERY, Map.of(), 5, null));

        verify(this.client, never()).query(any());
    }

    @Test
    void searchesWithoutStoreHintsAddNoStoreRestriction() throws Exception
    {
        when(this.embeddingsUtils.computeEmbeddings(anyString(), any(), any(), any()))
            .thenReturn(new double[] { 0.1, 0.2 });
        mockEscaping("col1");
        when(this.queryResponse.getResults()).thenReturn(new SolrDocumentList());

        this.solrConnector.keywordSearch(QUERY, List.of("col1"), 5, null);
        this.solrConnector.similaritySearch(QUERY, Map.of("col1", MODEL), 5, null);

        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(client, times(2)).query(
            captor.capture(), eq(SolrRequest.METHOD.POST));
        for (SolrQuery query : captor.getAllValues()) {
            assertTrue(Arrays.asList(query.getFilterQueries()).contains("collection:escaped(col1)"),
                Arrays.toString(query.getFilterQueries()));
            assertTrue(Arrays.stream(query.getFilterQueries()).noneMatch(fq -> fq.contains(STORE_HINT_FIELD)),
                Arrays.toString(query.getFilterQueries()));
        }
    }

    private static Map<String, String> mixedStoreHints()
    {
        Map<String, String> storeHints = new LinkedHashMap<>();
        storeHints.put(INTERNAL_COLLECTION, INTERNAL_STORE);
        storeHints.put(XWIKI_COLLECTION, XWIKI_STORE);
        return storeHints;
    }

    @Test
    void clearIndexCoreDeletesOnlyTheChunksOfTheGivenWiki() throws Exception
    {
        mockEscaping("otherwiki");

        this.solrConnector.clearIndexCore("otherwiki");

        // Chunks stored before version 0.4 have no wiki field and are removed as well.
        verify(this.client).deleteByQuery("((" + AiLLMSolrCoreInitializer.FIELD_WIKI + ":escaped(otherwiki)) OR (*:*"
            + " AND -" + AiLLMSolrCoreInitializer.FIELD_WIKI + ":[* TO *]))", 10000);
        verify(this.client).commit(null, false, true, true);
    }

    private void mockEscaping(String... values)
    {
        for (String value : values) {
            when(this.solrUtils.toCompleteFilterQueryString(value)).thenReturn("escaped(" + value + ")");
        }
    }

    private SolrQuery capturedQuery() throws Exception
    {
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(client).query(captor.capture(), eq(SolrRequest.METHOD.POST));
        return captor.getValue();
    }
}
