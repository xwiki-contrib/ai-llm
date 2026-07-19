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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Provider;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.XWikiSolrCore;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(this.client.query(any())).thenReturn(this.queryResponse);
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
        verify(this.client).close();
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

    private SolrQuery capturedQuery() throws Exception
    {
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(this.client).query(captor.capture());
        return captor.getValue();
    }
}
