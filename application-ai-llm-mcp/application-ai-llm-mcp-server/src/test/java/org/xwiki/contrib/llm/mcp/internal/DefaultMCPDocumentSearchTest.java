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
    private SolrUtils solrUtils;

    private SecureQuery query;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.solrUtils.toCompleteFilterQueryString(anyString())).thenAnswer(inv -> inv.getArgument(0));

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
        when(this.spaceFilter.filterQueries()).thenReturn(List.of());

        assertSame(this.query, this.search.createQuery(STATEMENT, null, List.of(WIKI)));
        verify(this.query).checkCurrentUser(true);
    }

    @Test
    void singleWikiBindsWikiScopeSourceFilterAndAdditionalFilterQueriesAsSeparateEntries() throws Exception
    {
        when(this.spaceFilter.filterQueries()).thenReturn(List.of("(wiki:xwiki AND space_prefix:A.B)"));

        this.search.createQuery(STATEMENT, List.of("type:DOCUMENT", "hidden:false"), List.of(WIKI));

        assertEquals(List.of("wiki:xwiki", "(wiki:xwiki AND space_prefix:A.B)", "type:DOCUMENT", "hidden:false"),
            captureFilterQueries());
    }

    @Test
    void singleWikiWithoutAdditionalFilterQueriesBindsScopeAndSourceFilterOnly() throws Exception
    {
        when(this.spaceFilter.filterQueries()).thenReturn(List.of("-((wiki:xwiki AND space_prefix:A.B))"));

        this.search.createQuery(STATEMENT, null, List.of(WIKI));

        assertEquals(List.of("wiki:xwiki", "-((wiki:xwiki AND space_prefix:A.B))"), captureFilterQueries());
    }

    @Test
    void nullTargetWikisBindsNoWikiScopeJustSourceFilterAndAdditional() throws Exception
    {
        // A null target list means the whole farm: no wiki-scope clause is added, only the source endpoint's
        // space filter (which carries its own per-entry wiki scope) and the additional filter queries.
        when(this.spaceFilter.filterQueries()).thenReturn(List.of("(wiki:second AND space_prefix:Sandbox)"));

        this.search.createQuery(STATEMENT, List.of("type:DOCUMENT"), null);

        assertEquals(List.of("(wiki:second AND space_prefix:Sandbox)", "type:DOCUMENT"), captureFilterQueries());
    }

    @Test
    void nullTargetWikisWithNoSourceFilterBindsOnlyAdditional() throws Exception
    {
        when(this.spaceFilter.filterQueries()).thenReturn(List.of());

        this.search.createQuery(STATEMENT, List.of("type:DOCUMENT"), null);

        assertEquals(List.of("type:DOCUMENT"), captureFilterQueries());
    }

    @Test
    void emptyTargetWikisBindsMatchNothing() throws Exception
    {
        when(this.spaceFilter.filterQueries()).thenReturn(List.of());

        this.search.createQuery(STATEMENT, List.of("type:DOCUMENT"), List.of());

        assertEquals(List.of("-*:*", "type:DOCUMENT"), captureFilterQueries());
    }

    @Test
    void multipleTargetWikisBindOneOrJoinedWikiScopeClause() throws Exception
    {
        // resolveSearchWikis yields only null/empty/singleton today, but the OR-join branch stays correct for a
        // multi-wiki list so a future change cannot silently mis-scope a cross-wiki search.
        when(this.spaceFilter.filterQueries()).thenReturn(List.of());

        this.search.createQuery(STATEMENT, null, List.of("alpha", "beta"));

        assertEquals(List.of("(wiki:alpha OR wiki:beta)"), captureFilterQueries());
    }
}
