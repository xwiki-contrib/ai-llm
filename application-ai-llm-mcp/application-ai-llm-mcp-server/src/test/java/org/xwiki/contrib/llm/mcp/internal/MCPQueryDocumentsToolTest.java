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
import java.util.Map;

import javax.inject.Provider;

import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.query.SecureQuery;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPQueryDocumentsTool tool;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    private SolrUtils solrUtils;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @BeforeEach
    void setUp()
    {
        // Make escaping methods passthrough so test query strings are predictable.
        when(this.solrUtils.toFilterQueryString(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(this.solrUtils.toCompleteFilterQueryString(anyString())).thenAnswer(inv -> inv.getArgument(0));

        XWikiContext xcontext = mock(XWikiContext.class);
        when(xcontext.getWikiId()).thenReturn("xwiki");
        when(this.contextProvider.get()).thenReturn(xcontext);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SecureQuery stubQuery(List<SolrDocument> docs) throws QueryException
    {
        return stubQuery(docs, null);
    }

    /**
     * Wires up the QueryManager so the next createQuery call returns a SecureQuery that executes and
     * returns the given documents, optionally with the given highlighting map.
     */
    private SecureQuery stubQuery(List<SolrDocument> docs,
        Map<String, Map<String, List<String>>> highlighting) throws QueryException
    {
        SecureQuery query = mock(SecureQuery.class);
        when(this.queryManager.createQuery(anyString(), eq("solr"))).thenReturn(query);
        when(query.checkCurrentUser(true)).thenReturn(query);
        when(query.setLimit(anyInt())).thenReturn(query);
        when(query.bindValue(anyString(), any())).thenReturn(query);

        SolrDocumentList docList = new SolrDocumentList();
        docList.addAll(docs);
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

    @SuppressWarnings("unchecked")
    private static List<String> captureFilterQueries(SecureQuery query)
    {
        ArgumentCaptor<Object> fqCaptor = ArgumentCaptor.forClass(Object.class);
        verify(query).bindValue(eq("fq"), fqCaptor.capture());
        return (List<String>) fqCaptor.getValue();
    }

    // -------------------------------------------------------------------------
    // Query statement / qf / defType / fl
    // -------------------------------------------------------------------------

    @Test
    void nonBlankQueryPassesStatementVerbatimAndBindsQfAndHighlighting() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());

        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "+foo -bar \"exact phrase\"")));

        ArgumentCaptor<String> statementCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.queryManager).createQuery(statementCaptor.capture(), eq("solr"));
        assertEquals("+foo -bar \"exact phrase\"", statementCaptor.getValue());

        verify(query).bindValue("qf", QF_WEIGHTS);
        verify(query, never()).bindValue(eq("defType"), any());
    }

    @Test
    void defTypeAndFlAreNeverBound() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());

        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "hello")));

        verify(query, never()).bindValue(eq("defType"), any());
        verify(query, never()).bindValue(eq("fl"), any());
    }

    @Test
    void qfWeightsAreBound() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());

        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "hello")));

        verify(query).bindValue("qf", QF_WEIGHTS);
    }

    @Test
    void timeAllowedIsBound() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());

        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "hello")));

        verify(query).bindValue("timeAllowed", "5000");
    }

    @Test
    void nonBlankQueryBindsHighlightingParams() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());

        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "hello")));

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
        SecureQuery query = stubQuery(List.of());

        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of()));

        ArgumentCaptor<String> statementCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.queryManager).createQuery(statementCaptor.capture(), eq("solr"));
        assertEquals("*", statementCaptor.getValue());

        verify(query).bindValue("sort", "date desc");
        verify(query, never()).bindValue(eq("hl"), any());
    }

    @Test
    void blankQueryEmptyResultsUsesBrowseMessage() throws QueryException
    {
        stubQuery(List.of());

        McpSchema.CallToolResult result =
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of()));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("No documents found.", textOf(result));
    }

    @Test
    void nonBlankQueryEmptyResultsEchoesQuery() throws QueryException
    {
        stubQuery(List.of());

        McpSchema.CallToolResult result =
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "nothing here")));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("nothing here"));
    }

    // -------------------------------------------------------------------------
    // Sort
    // -------------------------------------------------------------------------

    @Test
    void sortNewestBindsDateDesc() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "sort", "newest")));
        verify(query).bindValue("sort", "date desc");
    }

    @Test
    void sortOldestBindsDateAsc() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "sort", "oldest")));
        verify(query).bindValue("sort", "date asc");
    }

    @Test
    void sortTitleBindsTitleSortAsc() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "sort", "title")));
        verify(query).bindValue("sort", FieldUtils.TITLE_SORT + " asc");
    }

    @Test
    void sortRelevanceWithQueryBindsNoSort() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "sort", "relevance")));
        verify(query, never()).bindValue(eq("sort"), any());
    }

    @Test
    void invalidSortReturnsErrorWithAllowedValues() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(new McpSchema.CallToolRequest("query_documents",
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
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));
        List<String> fqs = captureFilterQueries(query);
        assertTrue(fqs.contains("type:DOCUMENT"));
        assertTrue(fqs.contains("hidden:false"));
        assertTrue(fqs.stream().anyMatch(f -> f.contains("wiki:")));
    }

    @Test
    void spaceProducesEscapedSpacePrefixFilter() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "space", "Help.Guides")));
        verify(this.solrUtils).toCompleteFilterQueryString("Help.Guides");
        assertTrue(captureFilterQueries(query).contains("space_prefix:Help.Guides"));
    }

    @Test
    void authorProducesEscapedAuthorFilter() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "author", "xwiki:XWiki.Admin")));
        verify(this.solrUtils).toCompleteFilterQueryString("xwiki:XWiki.Admin");
        assertTrue(captureFilterQueries(query).contains("author:xwiki:XWiki.Admin"));
    }

    @Test
    void modifiedWithinWeekProducesDateRangeFilter() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "modifiedWithin", "week")));
        assertTrue(captureFilterQueries(query).contains("date:[NOW-7DAY TO NOW]"));
    }

    @Test
    void modifiedRangeOverridesModifiedWithin() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "modifiedWithin", "week", "modifiedRange", "[2026-01-01T00:00:00Z TO NOW]")));
        List<String> fqs = captureFilterQueries(query);
        assertTrue(fqs.contains("date:[2026-01-01T00:00:00Z TO NOW]"));
        assertFalse(fqs.contains("date:[NOW-7DAY TO NOW]"));
        // Raw range must not be escaped.
        verify(this.solrUtils, never()).toCompleteFilterQueryString("[2026-01-01T00:00:00Z TO NOW]");
    }

    @Test
    void invalidModifiedWithinReturnsErrorWithAllowedValues() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(new McpSchema.CallToolRequest("query_documents",
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
        McpSchema.CallToolResult result = this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "modifiedRange", "[* TO *] OR hidden:true")));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("modifiedRange"));
    }

    @Test
    void modifiedRangeAcceptsValidRange() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "modifiedRange", "[NOW-7DAY TO NOW]")));
        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(captureFilterQueries(query).contains("date:[NOW-7DAY TO NOW]"));
    }

    // -------------------------------------------------------------------------
    // String parameter typing
    // -------------------------------------------------------------------------

    @Test
    void nonStringQueryReturnsError() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(new McpSchema.CallToolRequest("query_documents",
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
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x", "limit", 999)));
        verify(query).setLimit(50);
    }

    @Test
    void explicitLimitIsApplied() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x", "limit", 5)));
        verify(query).setLimit(5);
    }

    @Test
    void defaultLimitWhenNotProvided() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));
        verify(query).setLimit(10);
    }

    @Test
    void limitIsClampedToMinimum() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x", "limit", 0)));
        verify(query).setLimit(1);
    }

    @Test
    void negativeLimitIsClampedToMinimum() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x", "limit", -5)));
        verify(query).setLimit(1);
    }

    @Test
    void nonNumericLimitReturnsError() throws QueryException
    {
        stubQuery(List.of());
        McpSchema.CallToolResult result = this.tool.execute(new McpSchema.CallToolRequest("query_documents",
            Map.of("query", "x", "limit", "lots")));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("integer"));
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
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "matched")));

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
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "match")));

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
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));

        assertTrue(textOf(result).contains("Snippet: Fallback content head."));
    }

    @Test
    void browseModeUsesContentHeadSnippet() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page",
            "Some content for browse.");
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of()));

        assertTrue(textOf(result).contains("Snippet: Some content for browse."));
    }

    @Test
    void snippetOmittedWhenNoContentAndNoHighlight() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Space.NoContent_en", "No Content Page", "xwiki",
            "Space.NoContent", null);
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));

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
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));

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
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));

        assertTrue(textOf(result).contains("Score: 3.14"));
    }

    @Test
    void scoreLineOmittedWhenScoreAbsent() throws QueryException
    {
        SolrDocument doc = buildDoc("xwiki:Help.Page_en", "Help Page", "xwiki", "Help.Page", "content");
        stubQuery(List.of(doc));

        McpSchema.CallToolResult result =
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));

        assertFalse(textOf(result).contains("Score:"));
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
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));

        String text = textOf(result);
        assertTrue(text.contains("Title: Getting Started"));
        assertTrue(text.contains("Reference: xwiki:Help.GettingStarted"));
        assertTrue(text.contains("Title: XWiki Syntax"));
        assertTrue(text.contains("---"));
    }

    @Test
    void usesSecureQueryForAuthorization() throws QueryException
    {
        SecureQuery query = stubQuery(List.of());
        this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "x")));
        verify(query).checkCurrentUser(true);
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void queryExceptionReturnsActionableError() throws QueryException
    {
        when(this.queryManager.createQuery(anyString(), anyString()))
            .thenThrow(new QueryException("unbalanced parentheses", null, null));

        McpSchema.CallToolResult result =
            this.tool.execute(new McpSchema.CallToolRequest("query_documents", Map.of("query", "foo (")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Could not run the search"));
        assertTrue(text.contains("unbalanced parentheses"));
        assertTrue(this.logCapture.getMessage(0).contains("MCP query_documents tool failed"));
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
        assertTrue(definition.inputSchema().required().isEmpty(),
            "query is now optional, so no parameters should be required");
    }

    @Test
    void isEnabledReturnsTrueByDefault()
    {
        assertTrue(this.tool.isEnabled());
    }
}
