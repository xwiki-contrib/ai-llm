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
package org.xwiki.contrib.llm.mcp.internal.tool;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentSearch;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPQueryDocumentsTool}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPQueryDocumentsToolTest
{
    private static final String QF_WEIGHTS = "title^10.0 doccontent^2.0 doccontentraw^0.4";

    private static final String VIEW_URL = "https://wiki.example/bin/view/Help/GettingStarted";

    private static final String WIKI = "xwiki";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPQueryDocumentsTool tool;

    @MockComponent
    private MCPDocumentSearch documentSearch;

    @MockComponent
    private MCPWikiReach wikiReach;

    @MockComponent
    private SolrUtils solrUtils;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @MockComponent
    @Named("user")
    private DocumentReferenceResolver<String> userReferenceResolver;

    @MockComponent
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @BeforeEach
    void setUp() throws Exception
    {
        // Make escaping passthrough so test query strings are predictable.
        when(this.solrUtils.toCompleteFilterQueryString(anyString())).thenAnswer(inv -> inv.getArgument(0));

        lenient().when(this.referenceResolver.resolve(anyString())).thenReturn(mock(DocumentReference.class));
        lenient().when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), isNull(), isNull(), eq(true)))
            .thenReturn(VIEW_URL);
        // By default the wiki reach door resolves any wiki parameter to the current wiki; cross-wiki tests
        // override this per case.
        lenient().when(this.wikiReach.resolveSearchWikis(any())).thenReturn(List.of(WIKI));
        // By default the endpoint has cross-wiki reach, so the advertised schema is the full (cross-wiki)
        // variant; the reach-off test overrides this.
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Query stubQuery(List<SolrDocument> docs) throws QueryException
    {
        return stubQuery(docs, null);
    }

    /**
     * Wires up the document-search door so the next createQuery call returns a Query that executes and
     * returns the given documents, optionally with the given highlighting map. The door owns the secure
     * flag and the wiki/space filter queries, so the mock here is a plain Query.
     */
    private Query stubQuery(List<SolrDocument> docs,
        Map<String, Map<String, List<String>>> highlighting) throws QueryException
    {
        return stubQuery(docs, highlighting, docs.size());
    }

    private Query stubQuery(List<SolrDocument> docs,
        Map<String, Map<String, List<String>>> highlighting, long numFound) throws QueryException
    {
        Query query = mock(Query.class);
        when(this.documentSearch.createQuery(anyString(), anyList(), anyList())).thenReturn(query);
        when(query.setLimit(anyInt())).thenReturn(query);
        when(query.setOffset(anyInt())).thenReturn(query);
        when(query.bindValue(anyString(), any())).thenReturn(query);

        SolrDocumentList docList = new SolrDocumentList();
        docList.addAll(docs);
        docList.setNumFound(numFound);
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docList);
        when(response.getHighlighting()).thenReturn(highlighting);
        when(query.execute()).thenReturn(List.of(response));

        return query;
    }

    private static SolrDocument buildDoc(String id, String title, String wiki, String fullname, String content)
    {
        SolrDocument doc = new SolrDocument();
        doc.addField("locale", "en");
        if (id != null) {
            doc.addField(FieldUtils.ID, id);
        }
        doc.addField("title_en", title);
        doc.addField(FieldUtils.WIKI, wiki);
        doc.addField(FieldUtils.FULLNAME, fullname);
        if (content != null) {
            doc.addField("doccontent_en", content);
        }
        return doc;
    }

    private static String textOf(McpSchema.CallToolResult result)
    {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static McpSchema.CallToolRequest request(String name, Map<String, Object> args)
    {
        return McpSchema.CallToolRequest.builder(name).arguments(args).build();
    }

    @SuppressWarnings("unchecked")
    private List<String> captureFilterQueries() throws QueryException
    {
        ArgumentCaptor<List<String>> fqCaptor = ArgumentCaptor.forClass(List.class);
        verify(this.documentSearch).createQuery(anyString(), fqCaptor.capture(), anyList());
        return fqCaptor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> captureTargetWikis() throws QueryException
    {
        ArgumentCaptor<List<String>> wikisCaptor = ArgumentCaptor.forClass(List.class);
        verify(this.documentSearch).createQuery(anyString(), anyList(), wikisCaptor.capture());
        return wikisCaptor.getValue();
    }

    // -------------------------------------------------------------------------
    // Query statement / qf / defType / fl
    // -------------------------------------------------------------------------

    @Test
    void nonBlankQueryPassesStatementVerbatimAndBindsQfAndHighlighting() throws QueryException
    {
        Query query = stubQuery(List.of());

        this.tool.execute(request("query_documents",
            Map.of("query", "+foo -bar \"exact phrase\"")));

        ArgumentCaptor<String> statementCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.documentSearch).createQuery(statementCaptor.capture(), anyList(), anyList());
        assertEquals("+foo -bar \"exact phrase\"", statementCaptor.getValue());

        verify(query).bindValue("qf", QF_WEIGHTS);
        verify(query, never()).bindValue(eq("defType"), any());
    }

    @Test
    void defTypeAndFlAreNeverBound() throws QueryException
    {
        Query query = stubQuery(List.of());

        this.tool.execute(request("query_documents", Map.of("query", "hello")));

        verify(query, never()).bindValue(eq("defType"), any());
        verify(query, never()).bindValue(eq("fl"), any());
    }

    @Test
    void qfWeightsAreBound() throws QueryException
    {
        Query query = stubQuery(List.of());

        this.tool.execute(request("query_documents", Map.of("query", "hello")));

        verify(query).bindValue("qf", QF_WEIGHTS);
    }

    @Test
    void timeAllowedIsBound() throws QueryException
    {
        Query query = stubQuery(List.of());

        this.tool.execute(request("query_documents", Map.of("query", "hello")));

        verify(query).bindValue("timeAllowed", "5000");
    }

    @Test
    void nonBlankQueryBindsHighlightingParams() throws QueryException
    {
        Query query = stubQuery(List.of());

        this.tool.execute(request("query_documents", Map.of("query", "hello")));

        verify(query).bindValue("hl", "true");
        verify(query).bindValue("hl.fl", "doccontent_*");
        verify(query).bindValue("hl.snippets", "1");
        verify(query).bindValue("hl.fragsize", "200");
        verify(query).bindValue("hl.simple.pre", "");
        verify(query).bindValue("hl.simple.post", "");
    }

    // -------------------------------------------------------------------------
    // Browse mode
    // -------------------------------------------------------------------------

    @Test
    void blankQueryBrowsesAllAndSortsByDateDesc() throws QueryException
    {
        Query query = stubQuery(List.of());

        this.tool.execute(request("query_documents", Map.of()));

        ArgumentCaptor<String> statementCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.documentSearch).createQuery(statementCaptor.capture(), anyList(), anyList());
        assertEquals("*", statementCaptor.getValue());

        verify(query).bindValue("sort", "date desc");
        verify(query, never()).bindValue(eq("hl"), any());
    }

    @Test
    void blankQueryEmptyResultsUsesBrowseMessage() throws QueryException
    {
        stubQuery(List.of());

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of()));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("No documents found.", textOf(result));
    }

    @Test
    void nonBlankQueryEmptyResultsEchoesQuery() throws QueryException
    {
        stubQuery(List.of());

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "nothing here")));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("nothing here"));
    }

    // -------------------------------------------------------------------------
    // Sort
    // -------------------------------------------------------------------------

    @Test
    void sortNewestBindsDateDesc() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "sort", "newest")));
        verify(query).bindValue("sort", "date desc");
    }

    @Test
    void sortOldestBindsDateAsc() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "sort", "oldest")));
        verify(query).bindValue("sort", "date asc");
    }

    @Test
    void sortTitleBindsTitleSortAsc() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "sort", "title")));
        verify(query).bindValue("sort", FieldUtils.TITLE_SORT + " asc");
    }

    @Test
    void sortRelevanceWithQueryBindsNoSort() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "sort", "relevance")));
        verify(query, never()).bindValue(eq("sort"), any());
    }

    @Test
    void invalidSortReturnsErrorWithAllowedValues() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "sort", "sideways")));
        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("relevance"));
        assertTrue(text.contains("newest"));
        assertTrue(text.contains("title"));
    }

    // -------------------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------------------

    @Test
    void baseFiltersAreAlwaysApplied() throws QueryException
    {
        stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x")));
        List<String> fqs = captureFilterQueries();
        assertTrue(fqs.contains("type:DOCUMENT"));
        assertTrue(fqs.contains("hidden:false"));
        // The wiki scope is now the door's responsibility, so the tool must not add it itself.
        assertFalse(fqs.stream().anyMatch(f -> f.contains("wiki:")), fqs.toString());
    }

    @Test
    void spaceProducesEscapedSpacePrefixFilter() throws QueryException
    {
        stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "space", "Help.Guides")));
        verify(this.solrUtils).toCompleteFilterQueryString("Help.Guides");
        assertTrue(captureFilterQueries().contains("space_prefix:Help.Guides"));
    }

    @Test
    void authorProducesEscapedAuthorFilter() throws QueryException
    {
        stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "author", "xwiki:XWiki.Admin")));
        verify(this.solrUtils).toCompleteFilterQueryString("xwiki:XWiki.Admin");
        assertTrue(captureFilterQueries().contains("author:xwiki:XWiki.Admin"));
    }

    @Test
    void authorNameIsNormalizedToUserReference() throws QueryException
    {
        DocumentReference adminReference = mock(DocumentReference.class);
        when(this.userReferenceResolver.resolve("Admin")).thenReturn(adminReference);
        when(this.entityReferenceSerializer.serialize(adminReference)).thenReturn("xwiki:XWiki.Admin");
        stubQuery(List.of());

        this.tool.execute(request("query_documents", Map.of("query", "x", "author", "Admin")));

        assertTrue(captureFilterQueries().contains("author:xwiki:XWiki.Admin"), "bare author name not normalized");
    }

    @Test
    void emptyResultEchoesActiveFilters() throws QueryException
    {
        DocumentReference adminReference = mock(DocumentReference.class);
        when(this.userReferenceResolver.resolve("Admin")).thenReturn(adminReference);
        when(this.entityReferenceSerializer.serialize(adminReference)).thenReturn("xwiki:XWiki.Admin");
        stubQuery(List.of(), null, 0);

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "nomatch", "author", "Admin", "space", "Help")));

        String text = textOf(result);
        assertTrue(text.contains("No documents found matching \"nomatch\""), text);
        assertTrue(text.contains("Active filters:"), text);
        assertTrue(text.contains("space=Help"), text);
        assertTrue(text.contains("author=xwiki:XWiki.Admin"), text);
    }

    @Test
    void modifiedWithinWeekProducesDateRangeFilter() throws QueryException
    {
        stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "modifiedWithin", "week")));
        assertTrue(captureFilterQueries().contains("date:[NOW-7DAY TO NOW]"));
    }

    @Test
    void modifiedRangeOverridesModifiedWithin() throws QueryException
    {
        stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "modifiedWithin", "week", "modifiedRange", "[2026-01-01T00:00:00Z TO NOW]")));
        List<String> fqs = captureFilterQueries();
        assertTrue(fqs.contains("date:[2026-01-01T00:00:00Z TO NOW]"));
        assertFalse(fqs.contains("date:[NOW-7DAY TO NOW]"));
        // Raw range must not be escaped.
        verify(this.solrUtils, never()).toCompleteFilterQueryString("[2026-01-01T00:00:00Z TO NOW]");
    }

    @Test
    void invalidModifiedWithinReturnsErrorWithAllowedValues() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "modifiedWithin", "fortnight")));
        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("day"));
        assertTrue(text.contains("week"));
        assertTrue(text.contains("year"));
    }

    @Test
    void modifiedRangeRejectsInjection() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "modifiedRange", "[* TO *] OR hidden:true")));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("modifiedRange"));
    }

    @Test
    void modifiedRangeAcceptsValidRange() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "modifiedRange", "[NOW-7DAY TO NOW]")));
        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(captureFilterQueries().contains("date:[NOW-7DAY TO NOW]"));
    }

    // -------------------------------------------------------------------------
    // String parameter typing
    // -------------------------------------------------------------------------

    @Test
    void nonStringQueryReturnsError() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", 42)));
        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("query"));
        assertTrue(text.contains("must be a string"));
    }

    // -------------------------------------------------------------------------
    // Limit
    // -------------------------------------------------------------------------

    @Test
    void limitIsClampedToMaximum() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x", "limit", 999)));
        verify(query).setLimit(25);
    }

    @Test
    void explicitLimitIsApplied() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x", "limit", 5)));
        verify(query).setLimit(5);
    }

    @Test
    void defaultLimitWhenNotProvided() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x")));
        verify(query).setLimit(10);
    }

    @Test
    void limitIsClampedToMinimum() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x", "limit", 0)));
        verify(query).setLimit(1);
    }

    @Test
    void negativeLimitIsClampedToMinimum() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x", "limit", -5)));
        verify(query).setLimit(1);
    }

    @Test
    void nonNumericLimitReturnsError() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "limit", "lots")));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("integer"));
    }

    // -------------------------------------------------------------------------
    // Offset / paging
    // -------------------------------------------------------------------------

    @Test
    void explicitOffsetIsApplied() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x", "offset", 20)));
        verify(query).setOffset(20);
    }

    @Test
    void defaultOffsetIsZero() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x")));
        verify(query).setOffset(0);
    }

    @Test
    void negativeOffsetReturnsError() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "offset", -1)));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("offset"));
    }

    @Test
    void offsetBeyondLastResultReturnsError() throws QueryException
    {
        stubQuery(List.of(), null, 12);
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "offset", 50)));
        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("offset 50"), text);
        assertTrue(text.contains("12 total matches"), text);
    }

    @Test
    void footerShowsTotalAndContinuationOffset() throws QueryException
    {
        List<SolrDocument> docs = List.of(
            buildDoc("id1", "Page One", "xwiki", "Help.PageOne", "content one"),
            buildDoc("id2", "Page Two", "xwiki", "Help.PageTwo", "content two"));
        stubQuery(docs, null, 12);

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "limit", 2)));

        String text = textOf(result);
        assertTrue(text.contains("Found about 12 matching documents."), text);
        assertTrue(text.contains("Showing 2 from offset 0."), text);
        assertTrue(text.contains("Continue with offset=2."), text);
    }

    @Test
    void footerNotesWhenRequestedLimitWasCapped() throws QueryException
    {
        List<SolrDocument> docs = List.of(
            buildDoc("id1", "Page One", "xwiki", "Help.PageOne", "content one"),
            buildDoc("id2", "Page Two", "xwiki", "Help.PageTwo", "content two"));
        stubQuery(docs, null, 40);

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "limit", 999)));

        String text = textOf(result);
        assertTrue(text.contains("capped to the maximum of 25 per page"), text);
    }

    @Test
    void footerOmitsCapNoticeWhenAllResultsFitUnderMaximum() throws QueryException
    {
        // A large requested limit that did not actually hide anything (total below the maximum) must not
        // nag with a cap notice.
        stubQuery(List.of(buildDoc("id1", "Page One", "xwiki", "Help.PageOne", "content one")), null, 3);

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "limit", 999)));

        String text = textOf(result);
        assertFalse(text.contains("capped"), text);
    }

    @Test
    void emptyPageWithinRangeAdvisesContinuation() throws QueryException
    {
        // The rights post-filter can empty a page whose offset is still within the raw result range;
        // the agent must be told to keep paging, not that it overshot.
        stubQuery(List.of(), null, 40);

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "offset", 10, "limit", 10)));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No viewable documents in this page"), text);
        assertTrue(text.contains("Continue with offset=20"), text);
    }

    @Test
    void footerOmitsPagingWhenAllResultsShown() throws QueryException
    {
        stubQuery(List.of(buildDoc("id1", "Page One", "xwiki", "Help.PageOne", "content one")));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        String text = textOf(result);
        assertTrue(text.contains("Found 1 matching document."), text);
        assertFalse(text.contains("about"), text);
        assertFalse(text.contains("Continue with offset="), text);
        assertFalse(text.contains("Showing 1 from offset 0"), text);
    }

    // -------------------------------------------------------------------------
    // Hidden documents
    // -------------------------------------------------------------------------

    @Test
    void includeHiddenDropsHiddenFilter() throws QueryException
    {
        stubQuery(List.of());
        this.tool.execute(request("query_documents",
            Map.of("query", "x", "includeHidden", true)));
        List<String> fqs = captureFilterQueries();
        assertFalse(fqs.contains("hidden:false"), fqs.toString());
        assertTrue(fqs.contains("type:DOCUMENT"));
    }

    @Test
    void nonBooleanIncludeHiddenReturnsError() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "includeHidden", 7)));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("boolean"));
    }

    @Test
    void nonTrueFalseBooleanStringReturnsErrorInsteadOfSilentFalse() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "includeHidden", "yes")));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("boolean"), textOf(result));
    }

    @Test
    void fractionalNumberForIntegerParamReturnsError() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "limit", 3.7)));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("integer"), textOf(result));
    }

    // -------------------------------------------------------------------------
    // Modified line
    // -------------------------------------------------------------------------

    @Test
    void modifiedLineShowsDateAndAuthorReference() throws QueryException
    {
        SolrDocument doc = buildDoc("id1", "Help Page", "xwiki", "Help.Page", "content");
        doc.addField(FieldUtils.DATE, Date.from(Instant.parse("2026-06-09T07:21:30Z")));
        doc.addField(FieldUtils.AUTHOR, "xwiki:XWiki.Admin");
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        assertTrue(textOf(result).contains("Modified: 2026-06-09T07:21:30Z by xwiki:XWiki.Admin"),
            textOf(result));
    }

    @Test
    void modifiedLineOmitsAuthorWhenAbsent() throws QueryException
    {
        SolrDocument doc = buildDoc("id1", "Help Page", "xwiki", "Help.Page", "content");
        doc.addField(FieldUtils.DATE, Date.from(Instant.parse("2026-06-09T07:21:30Z")));
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        String text = textOf(result);
        assertTrue(text.contains("Modified: 2026-06-09T07:21:30Z"), text);
        assertFalse(text.contains(" by "), text);
    }

    @Test
    void modifiedLineOmittedWhenNoDate() throws QueryException
    {
        stubQuery(List.of(buildDoc("id1", "Help Page", "xwiki", "Help.Page", "content")));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        assertFalse(textOf(result).contains("Modified:"));
    }

    // -------------------------------------------------------------------------
    // Highlighting and snippets
    // -------------------------------------------------------------------------

    @Test
    void highlightSnippetIsTagStrippedAndUsedOverContentHead() throws QueryException
    {
        // The doc carries locale=en (set by buildDoc), so the lookup computes the real per-locale
        // field name doccontent_en. The highlighting map is keyed by that real field, not the
        // fictional logical "doccontent".
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page",
            "The full rendered content head that should be ignored.");
        Map<String, Map<String, List<String>>> highlighting = Map.of(
            "xwiki:Help.Page_en", Map.of("doccontent_en", List.of("a <em>matched</em> snippet")));
        stubQuery(List.of(doc), highlighting);

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "matched")));

        String text = textOf(result);
        assertTrue(text.contains("Snippet: a matched snippet"), "Expected tag-stripped highlight, got: " + text);
        assertFalse(text.contains("rendered content head"));
    }

    @Test
    void highlightFallsBackToContentFieldScanWhenLocaleMismatches() throws QueryException
    {
        // The doc's locale is en (so the lookup computes doccontent_en), but the field that actually
        // matched and was highlighted is doccontent_fr. The prefix scan must still recover it.
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page",
            "The full rendered content head that should be ignored.");
        Map<String, Map<String, List<String>>> highlighting = Map.of(
            "xwiki:Help.Page_en", Map.of("doccontent_fr", List.of("le <em>match</em> fragment")));
        stubQuery(List.of(doc), highlighting);

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "match")));

        String text = textOf(result);
        assertTrue(text.contains("Snippet: le match fragment"), "Expected scanned highlight, got: " + text);
        assertFalse(text.contains("rendered content head"));
    }

    @Test
    void missingHighlightFallsBackToContentHead() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page",
            "Fallback content head.");
        // Highlighting present but no entry for this doc id.
        stubQuery(List.of(doc), Map.of());

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        assertTrue(textOf(result).contains("Snippet: Fallback content head."));
    }

    @Test
    void browseModeUsesContentHeadSnippet() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page",
            "Some content for browse.");
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of()));

        assertTrue(textOf(result).contains("Snippet: Some content for browse."));
    }

    @Test
    void snippetOmittedWhenNoContentAndNoHighlight() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Space.NoContent_en", "No Content Page", "xwiki",
            "Space.NoContent", null);
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        String text = textOf(result);
        assertTrue(text.contains("Title: No Content Page"));
        assertFalse(text.contains("Snippet:"));
    }

    @Test
    void contentHeadSnippetIsTruncated() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Space.Long_en", "Long Page", "xwiki", "Space.Long",
            "A".repeat(300));
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        String text = textOf(result);
        assertTrue(text.contains("..."));
        String snippetLine = text.lines().filter(l -> l.startsWith("Snippet:")).findFirst().orElse("");
        String snippetContent = snippetLine.substring("Snippet: ".length());
        assertTrue(snippetContent.length() <= 203);
    }

    // -------------------------------------------------------------------------
    // Score
    // -------------------------------------------------------------------------

    @Test
    void scoreLineIsPresentAndFormattedWhenScorePresent() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page", "content");
        doc.addField("score", 3.14159f);
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        assertTrue(textOf(result).contains("Score: 3.14"));
    }

    @Test
    void scoreLineOmittedWhenScoreAbsent() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page", "content");
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        assertFalse(textOf(result).contains("Score:"));
    }

    @Test
    void scoreLineOmittedInBrowseMode() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page", "content");
        doc.addField("score", 1.0f);
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of()));

        assertFalse(textOf(result).contains("Score:"),
            "The constant browse score is noise and must be omitted: " + textOf(result));
    }

    @Test
    void scoreLineOmittedForNonRelevanceSort() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page", "content");
        doc.addField("score", 3.14f);
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "sort", "newest")));

        assertFalse(textOf(result).contains("Score:"),
            "The score is meaningless when not ordering by it: " + textOf(result));
    }

    // -------------------------------------------------------------------------
    // Result formatting and security
    // -------------------------------------------------------------------------

    @Test
    void resultsIncludeTitleReferenceAndSeparator() throws QueryException
    {
        stubQuery(List.of(
            buildDoc("id1", "Getting Started", "xwiki", "Help.GettingStarted", "Guide content."),
            buildDoc("id2", "XWiki Syntax", "xwiki", "XWiki.XWikiSyntax", "Syntax content.")));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        String text = textOf(result);
        assertTrue(text.contains("Title: Getting Started"));
        assertTrue(text.contains("Reference: xwiki:Help.GettingStarted"));
        assertTrue(text.contains("Title: XWiki Syntax"));
        assertTrue(text.contains("---"));
    }

    @Test
    void resultsIncludeVerbatimViewUrl() throws QueryException
    {
        stubQuery(List.of(
            buildDoc("id1", "Getting Started", "xwiki", "Help.GettingStarted", "Guide content.")));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        String text = textOf(result);
        String urlLine = text.lines().filter(l -> l.startsWith("URL: ")).findFirst().orElse("");
        assertEquals("URL: " + VIEW_URL, urlLine, text);
        int refIndex = text.indexOf("Reference: ");
        int urlIndex = text.indexOf("URL: ");
        assertTrue(refIndex < urlIndex, "URL line must sit after the Reference line: " + text);
        assertFalse(text.contains("/xwiki/"), "No /xwiki/ prefix may be added to the factory URL: " + text);
    }

    @Test
    void urlOmittedWhenResolveFails() throws QueryException
    {
        stubQuery(List.of(
            buildDoc("id1", "Getting Started", "xwiki", "Help.GettingStarted", "Guide content.")));
        when(this.referenceResolver.resolve(anyString())).thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertFalse(text.contains("URL:"), "URL line must be omitted when resolution fails: " + text);
        assertTrue(text.contains("Title: Getting Started"), text);
        assertTrue(text.contains("Reference: xwiki:Help.GettingStarted"), text);
    }

    @Test
    void nullWikiUsesBareFullnameForReferenceAndUrl() throws QueryException
    {
        // A document with no wiki field falls back to the bare fullname for both the Reference line
        // and the reference resolved for the URL; the two must stay consistent.
        stubQuery(List.of(buildDoc("id1", "Getting Started", null, "Help.GettingStarted", "Guide content.")));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x")));

        String text = textOf(result);
        assertTrue(text.contains("Reference: Help.GettingStarted"), text);
        assertFalse(text.contains("Reference: :Help.GettingStarted"), text);
        verify(this.referenceResolver).resolve("Help.GettingStarted");
        assertTrue(text.contains("URL: " + VIEW_URL), text);
    }

    @Test
    void delegatesQueryCreationToDoorAndDoesNotBindFqItself() throws QueryException
    {
        Query query = stubQuery(List.of());
        this.tool.execute(request("query_documents", Map.of("query", "x")));
        // The door owns query creation, the secure flag and the fq binding; the tool obtains its query
        // from the door and must never bind fq itself (that would drop the door's wiki/space scope).
        verify(this.documentSearch).createQuery(anyString(), anyList(), anyList());
        verify(query, never()).bindValue(eq("fq"), any());
    }

    // -------------------------------------------------------------------------
    // Wiki parameter / cross-wiki reach
    // -------------------------------------------------------------------------

    @Test
    void omittingWikiResolvesToCurrentWikiAndForwardsItToTheDoor() throws Exception
    {
        stubQuery(List.of());

        this.tool.execute(request("query_documents", Map.of("query", "x")));

        // A blank wiki parameter is passed to the door as null, and the door's resolved wikis are forwarded.
        verify(this.wikiReach).resolveSearchWikis(null);
        assertEquals(List.of(WIKI), captureTargetWikis());
    }

    @Test
    void allWikisResolvesToNullScopeAndForwardsNullToTheDoor() throws Exception
    {
        // "all" resolves to a null wiki scope (the whole farm); the tool must forward that null to the door.
        Query query = mock(Query.class);
        when(this.documentSearch.createQuery(anyString(), anyList(), isNull())).thenReturn(query);
        when(query.setLimit(anyInt())).thenReturn(query);
        when(query.setOffset(anyInt())).thenReturn(query);
        when(query.bindValue(anyString(), any())).thenReturn(query);
        when(query.execute()).thenReturn(List.of());
        when(this.wikiReach.resolveSearchWikis("all")).thenReturn(null);

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "x", "wiki", "all")));

        assertNotEquals(Boolean.TRUE, result.isError());
        verify(this.wikiReach).resolveSearchWikis("all");
        verify(this.documentSearch).createQuery(anyString(), anyList(), isNull());
    }

    @Test
    void reachDeniedWikiReturnsTheDenialMessageAndDoesNotSearch() throws Exception
    {
        when(this.wikiReach.resolveSearchWikis("secret"))
            .thenThrow(new MCPAccessDeniedException("Cross-wiki search is not enabled for this endpoint."));

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "wiki", "secret")));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Cross-wiki search is not enabled for this endpoint.", textOf(result));
        verify(this.documentSearch, never()).createQuery(anyString(), anyList(), anyList());
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void queryExceptionReturnsActionableError() throws QueryException
    {
        when(this.documentSearch.createQuery(anyString(), anyList(), anyList()))
            .thenThrow(new QueryException("unbalanced parentheses", null, null));

        McpSchema.CallToolResult result =
            this.tool.execute(request("query_documents", Map.of("query", "foo (")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Could not run the search"));
        assertTrue(text.contains("man query_documents"));
        // The Solr root cause stays off the wire (it can name cores, fields or hosts); it is only logged.
        assertFalse(text.contains("unbalanced parentheses"));
        assertTrue(this.logCapture.getMessage(0).contains("MCP query_documents tool failed"));
        assertTrue(this.logCapture.getMessage(0).contains("unbalanced parentheses"));
    }

    // -------------------------------------------------------------------------
    // Definition
    // -------------------------------------------------------------------------

    @Test
    void toolDefinitionHasCorrectNameAndNoRequiredParams()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertEquals(MCPQueryDocumentsTool.TOOL_ID, definition.name());
        assertEquals("query_documents", definition.name());
        assertTrue(((List<?>) definition.inputSchema().get("required")).isEmpty(),
            "query is now optional, so no parameters should be required");
    }

    @Test
    void reachOnAdvertisesWikiParameter()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);

        Map<?, ?> properties = (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");

        assertTrue(properties.containsKey("wiki"), properties.keySet().toString());
        String authorDescription = (String) ((Map<?, ?>) properties.get("author")).get("description");
        assertTrue(authorDescription.contains("\"xwiki:XWiki.Admin\""), authorDescription);
    }

    @Test
    void reachOffDropsWikiParameterFromAdvertisedSchema()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        McpSchema.Tool definition = this.tool.getToolDefinition();
        Map<?, ?> properties = (Map<?, ?>) definition.inputSchema().get("properties");

        assertFalse(properties.containsKey("wiki"), properties.keySet().toString());
        // No advertised description may carry a wiki-prefixed example on a reach-off endpoint.
        assertFalse(definition.inputSchema().toString().contains("xwiki:"),
            "Reach-off advertised schema must not contain wiki-prefixed examples");
        assertFalse(definition.description().contains("xwiki:"), definition.description());
    }

    @Test
    void isEnabledReturnsTrueByDefault()
    {
        assertTrue(this.tool.isEnabled());
    }

    @Test
    void languageLineMarksTranslationRows() throws QueryException
    {
        SolrDocument enDoc = buildDoc("id-en", "Home", WIKI, "Help.Home", "content");
        // The default row stores an empty document locale.
        enDoc.addField("doclocale", "");
        SolrDocument frDoc = new SolrDocument();
        frDoc.addField("locale", "fr");
        frDoc.addField("doclocale", "fr");
        frDoc.addField("title_fr", "Accueil");
        frDoc.addField(FieldUtils.WIKI, WIKI);
        frDoc.addField(FieldUtils.FULLNAME, "Help.Home");
        stubQuery(List.of(enDoc, frDoc));

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents", Map.of("query", "x")));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        // A translation row is marked, steering the agent to the get_document locale parameter.
        assertTrue(text.contains("Language: fr (translation)"), text);
        // A default row carries the plain language line, no suffix.
        assertTrue(text.contains("Language: en\n"), text);
        assertFalse(text.contains("Language: en (translation)"), text);
    }

    @Test
    void localeFilterProducesEscapedNormalizedLocaleFilter() throws QueryException
    {
        stubQuery(List.of());

        this.tool.execute(request("query_documents", Map.of("query", "x", "locale", "fr-FR")));

        // The validated Locale's toString normalizes the dash form to the indexed underscore form.
        verify(this.solrUtils).toCompleteFilterQueryString("fr_FR");
        assertTrue(captureFilterQueries().contains("locale:fr_FR"));
    }

    @Test
    void invalidLocaleFilterReturnsTeachingRefusal() throws QueryException
    {
        stubQuery(List.of());

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "x", "locale", "french")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Error: 'locale' is not a valid locale: \"french\""), text);
        assertTrue(text.contains("\"fr\" or \"pt_BR\""), text);
    }

    @Test
    void languageLineStripsLineBreaksFromStoredLocale() throws QueryException
    {
        SolrDocument doc = buildDoc("id-1", "Home", WIKI, "Help.Home", "content");
        // Defense in depth: a stored locale value carrying a line break must not forge extra result
        // lines.
        doc.setField("locale", "fr\nForged: yes");
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents", Map.of("query", "x")));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Language: frForged: yes"), text);
        assertFalse(text.contains("\nForged"), text);
    }

    @Test
    void emptyResultEchoesLocaleFilter() throws QueryException
    {
        stubQuery(List.of(), null, 0);

        McpSchema.CallToolResult result = this.tool.execute(request("query_documents",
            Map.of("query", "nomatch", "locale", "fr")));

        String text = textOf(result);
        assertTrue(text.contains("No documents found matching \"nomatch\""), text);
        assertTrue(text.contains("Active filters:"), text);
        assertTrue(text.contains("locale=fr"), text);
    }
}
