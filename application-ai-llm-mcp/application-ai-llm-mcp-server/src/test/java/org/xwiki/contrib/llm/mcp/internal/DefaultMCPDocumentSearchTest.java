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
package org.xwiki.contrib.llm.mcp.internal;

import java.util.List;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.query.SecureQuery;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultMCPDocumentSearch}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultMCPDocumentSearchTest
{
    private static final String WIKI = "xwiki";

    private static final String STATEMENT = "*";

    @InjectMockComponents
    private DefaultMCPDocumentSearch search;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    private MCPSpaceFilter spaceFilter;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private SolrUtils solrUtils;

    private SecureQuery query;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.solrUtils.toCompleteFilterQueryString(anyString())).thenAnswer(inv -> inv.getArgument(0));

        XWikiContext context = mock(XWikiContext.class);
        when(context.getWikiId()).thenReturn(WIKI);
        when(this.contextProvider.get()).thenReturn(context);

        this.query = mock(SecureQuery.class);
        when(this.queryManager.createQuery(anyString(), eq("solr"))).thenReturn(this.query);
        when(this.query.checkCurrentUser(true)).thenReturn(this.query);
        when(this.query.bindValue(anyString(), any())).thenReturn(this.query);
    }

    @SuppressWarnings("unchecked")
    private List<String> captureFilterQueries() throws QueryException
    {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(this.query).bindValue(eq("fq"), captor.capture());
        return (List<String>) captor.getValue();
    }

    @Test
    void createQueryEnablesRightsPostFilteringAndReturnsTheQuery() throws Exception
    {
        when(this.spaceFilter.filterQueries(WIKI)).thenReturn(List.of());

        assertSame(this.query, this.search.createQuery(STATEMENT, null));
        verify(this.query).checkCurrentUser(true);
    }

    @Test
    void createQueryBindsWikiScopeSpaceFilterAndAdditionalFilterQueries() throws Exception
    {
        when(this.spaceFilter.filterQueries(WIKI)).thenReturn(List.of("space_prefix:(A.B)"));

        this.search.createQuery(STATEMENT, List.of("type:DOCUMENT", "hidden:false"));

        assertEquals(List.of("wiki:xwiki", "space_prefix:(A.B)", "type:DOCUMENT", "hidden:false"),
            captureFilterQueries());
    }

    @Test
    void createQueryWithoutAdditionalFilterQueriesBindsScopeAndSpaceFilterOnly() throws Exception
    {
        when(this.spaceFilter.filterQueries(WIKI)).thenReturn(List.of("-(space_prefix:(A.B))"));

        this.search.createQuery(STATEMENT, null);

        assertEquals(List.of("wiki:xwiki", "-(space_prefix:(A.B))"), captureFilterQueries());
    }

    @Test
    void createQueryWithBlankContextWikiAddsNoWikiScope() throws Exception
    {
        XWikiContext context = mock(XWikiContext.class);
        when(context.getWikiId()).thenReturn(null);
        when(this.contextProvider.get()).thenReturn(context);
        when(this.spaceFilter.filterQueries(null)).thenReturn(List.of());

        this.search.createQuery(STATEMENT, List.of("type:DOCUMENT"));

        // No wiki:... scope clause is added when the context has no wiki id.
        assertEquals(List.of("type:DOCUMENT"), captureFilterQueries());
    }
}
