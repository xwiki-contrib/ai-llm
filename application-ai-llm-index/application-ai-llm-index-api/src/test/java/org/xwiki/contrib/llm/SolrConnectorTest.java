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

import java.util.List;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.XWikiSolrCore;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
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
}
