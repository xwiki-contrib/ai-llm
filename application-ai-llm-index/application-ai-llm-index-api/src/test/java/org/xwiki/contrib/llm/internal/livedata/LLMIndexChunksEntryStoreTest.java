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
package org.xwiki.contrib.llm.internal.livedata;

import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.livedata.LiveData;
import org.xwiki.livedata.LiveDataException;
import org.xwiki.livedata.LiveDataQuery;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.XWikiSolrCore;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LLMIndexChunksEntryStore}.
 *
 * @version $Id$
 */
@ComponentTest
class LLMIndexChunksEntryStoreTest
{
    private static final String WIKI = "xwiki";

    private static final String ID_PROPERTY = "id";

    private static final String CHUNK_ID = "xwiki:AI.Documents.MyDocument_0";

    @InjectMockComponents
    private LLMIndexChunksEntryStore entryStore;

    @MockComponent
    private Solr solr;

    @MockComponent
    private SolrUtils solrUtils;

    @MockComponent
    private ContextualAuthorizationManager contextualAuthorizationManager;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private Provider<Map<String, LLMIndexProperty>> propertiesProvider;

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
        WikiReference wikiReference = new WikiReference(WIKI);
        when(this.contextProvider.get()).thenReturn(this.xcontext);
        when(this.xcontext.getWikiId()).thenReturn(WIKI);
        when(this.xcontext.getWikiReference()).thenReturn(wikiReference);
        when(this.contextualAuthorizationManager.hasAccess(Right.ADMIN, wikiReference)).thenReturn(true);

        when(this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE)).thenReturn(this.core);
        when(this.core.getClient()).thenReturn(this.client);
        when(this.solrUtils.toCompleteFilterQueryString(WIKI)).thenReturn(WIKI);

        LLMIndexProperty idProperty = mock(LLMIndexProperty.class);
        when(idProperty.getId()).thenReturn(ID_PROPERTY);
        when(idProperty.getSolrProperty()).thenReturn(ID_PROPERTY);
        when(idProperty.getValue(any(SolrDocument.class))).thenReturn(CHUNK_ID);
        when(this.propertiesProvider.get()).thenReturn(Map.of(ID_PROPERTY, idProperty));

        SolrDocumentList results = new SolrDocumentList();
        results.add(new SolrDocument());
        results.setNumFound(1);
        when(this.queryResponse.getResults()).thenReturn(results);
        when(this.client.query(any(SolrQuery.class))).thenReturn(this.queryResponse);
    }

    @Test
    void getQueriesTheSharedClientWithoutClosingIt() throws Exception
    {
        LiveData liveData = this.entryStore.get(query());

        assertEquals(1, liveData.getCount());
        assertEquals(1, liveData.getEntries().size());
        assertEquals(CHUNK_ID, liveData.getEntries().get(0).get(ID_PROPERTY));
        verify(this.client).query(any(SolrQuery.class));
        // The platform caches one client per core and owns its lifecycle - the store must never close it
        // (closing a remote HttpSolrClient destroys the shared connection pool for every later call).
        verify(this.client, never()).close();
    }

    @Test
    void getRefusesNonAdmins() throws Exception
    {
        when(this.contextualAuthorizationManager.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(false);

        assertThrows(LiveDataException.class, () -> this.entryStore.get(query()));

        verify(this.client, never()).query(any(SolrQuery.class));
    }

    @Test
    void getWrapsSolrFailures() throws Exception
    {
        when(this.client.query(any(SolrQuery.class))).thenThrow(new SolrServerException("Solr down"));

        LiveDataException exception = assertThrows(LiveDataException.class, () -> this.entryStore.get(query()));

        assertEquals("Failed to query the Solr index", exception.getMessage());
        assertEquals(SolrServerException.class, exception.getCause().getClass());
        verify(this.client, never()).close();
    }

    private static LiveDataQuery query()
    {
        LiveDataQuery query = new LiveDataQuery();
        query.setOffset(0L);
        query.setLimit(10);
        query.setSort(List.of());
        query.setFilters(List.of());
        return query;
    }
}
