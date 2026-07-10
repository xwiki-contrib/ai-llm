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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentSearch;
import org.xwiki.contrib.llm.mcp.MCPReachAwareParams;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that searches and browses XWiki documents using the platform's Solr search index.
 *
 * <p>This is a default tool bundled with the MCP server module. It queries the platform's
 * built-in document search index (not the AI-LLM RAG index), so it is independent of
 * the index module.</p>
 *
 * <p>The query runs against the {@code /select} handler whose default {@code defType} is
 * {@code xdismax} (XWiki's edismax superset with multilingual title expansion). The handler
 * default is relied upon and {@code defType} is never bound, so multilingual title expansion
 * is preserved.</p>
 *
 * <p>The query is created through
 * {@link MCPDocumentSearch#createQuery(String, java.util.List, java.util.List)}, which scopes it to the resolved
 * target wiki(s), applies each wiki's space filter and enables current-user view-rights post-filtering, so that
 * the {@code SolrQueryExecutor} removes any documents the current user cannot view. The target wiki(s) come from
 * the {@code wiki} parameter via {@link MCPWikiReach}; this tool only adds its own non-fq parameters and its
 * content/date filter queries.</p>
 *
 * <p>Solr schema field names come from the platform's internal
 * {@code org.xwiki.search.solr.internal.api.FieldUtils} - a deliberate dependency on an internal package:
 * it also encodes the locale-suffix logic for the per-language content fields ({@code getFieldName}), and the
 * compile-time coupling turns a platform schema or package change into a build error instead of silent
 * field-name drift.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPQueryDocumentsTool.TOOL_ID)
@Singleton
// Author normalization pulls in a user reference resolver and serializer; the extra collaborators push the
// fan-out one over the limit on an otherwise cohesive tool.
@SuppressWarnings("checkstyle:ClassFanOutComplexity")
public class MCPQueryDocumentsTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "query_documents";

    /**
     * Field boosts handed to xdismax via the {@code qf} parameter. Mirrors the platform's default
     * weighting so that titles dominate and content is secondary.
     */
    private static final String QF_WEIGHTS = "title^10.0 doccontent^2.0 doccontentraw^0.4";

    /**
     * Prefix shared by every per-locale rendered-content Solr field (e.g. {@code doccontent_en},
     * {@code doccontent__} for the ROOT locale, {@code doccontent_} for the aggregate). There is no
     * field literally named {@code doccontent}; the logical name is expanded by xdismax.
     */
    private static final String CONTENT_FIELD_PREFIX = "doccontent_";

    private static final String BROWSE_STATEMENT = "*";

    /**
     * Glob bound on {@code hl.fl} so highlighting fires on whichever per-locale rendered-content
     * field actually matched. {@code hl.requireFieldMatch=true} (the handler default) ensures a
     * snippet is only produced for the matched per-locale field.
     */
    private static final String HL_CONTENT_FIELDS = CONTENT_FIELD_PREFIX + BROWSE_STATEMENT;

    private static final int DEFAULT_LIMIT = 10;

    private static final int MAX_LIMIT = 25;

    private static final int MIN_LIMIT = 1;

    private static final int SNIPPET_MAX_LENGTH = 200;

    private static final String LIST_SEPARATOR = ", ";

    private static final String QUERY_PARAM = "query";

    private static final String WIKI_PARAM = "wiki";

    private static final String LIMIT_PARAM = "limit";

    private static final String OFFSET_PARAM = "offset";

    private static final String INCLUDE_HIDDEN_PARAM = "includeHidden";

    private static final String SPACE_PARAM = "space";

    private static final String AUTHOR_PARAM = "author";

    private static final String MODIFIED_WITHIN_PARAM = "modifiedWithin";

    private static final String MODIFIED_RANGE_PARAM = "modifiedRange";

    private static final String SORT_PARAM = "sort";

    private static final String RELEVANCE = "relevance";

    private static final String NEWEST = "newest";

    private static final String OLDEST = "oldest";

    private static final String TITLE = "title";

    private static final String SCORE_FIELD = "score";

    private static final String DATE_DESC = "date desc";

    private static final String DATE_ASC = "date asc";

    private static final String COLON = ":";

    private static final String VIEW_ACTION = "view";

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String PERIOD = ".";

    private static final String RESULT_SEPARATOR = NEW_LINE + "---" + NEW_LINE;

    private static final String HTML_TAG_REGEX = "<[^>]+>";

    private static final String TIME_ALLOWED_KEY = "timeAllowed";

    /**
     * Server-side query time budget, in milliseconds, bound on every Solr query so that a
     * pathological or accidentally expensive query is cut off rather than tying up the search core.
     */
    private static final String TIME_ALLOWED_MS = "5000";

    /**
     * Strict allowlist for the {@code modifiedRange} parameter. The value must be a single bracketed
     * Solr date-range token (e.g. {@code [NOW-7DAY TO NOW]} or {@code [2026-01-01T00:00:00Z TO NOW]}),
     * which prevents an agent from injecting additional boolean predicates into the {@code date:} fq.
     */
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
        "[\\[{]\\s*(\\*|NOW[-+/A-Z0-9]*|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)"
        + "\\s+TO\\s+(\\*|NOW[-+/A-Z0-9]*|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)\\s*[\\]}]");

    private static final String DAY = "day";

    private static final String WEEK = "week";

    private static final String MONTH = "month";

    private static final String YEAR = "year";

    /**
     * Allowed values for the {@code modifiedWithin} parameter, mapped to their Solr date-range
     * expressions on the last-modified date field.
     */
    private static final Map<String, String> MODIFIED_WITHIN_RANGES = Map.of(
        DAY, "[NOW-1DAY TO NOW]",
        WEEK, "[NOW-7DAY TO NOW]",
        MONTH, "[NOW-1MONTH TO NOW]",
        YEAR, "[NOW-1YEAR TO NOW]"
    );

    /**
     * The accepted {@code modifiedWithin} values in display order, for the invalid-value error message
     * ({@code Map.of} key order is unspecified). Must stay in sync with {@link #MODIFIED_WITHIN_RANGES}.
     */
    private static final List<String> MODIFIED_WITHIN_VALUES = List.of(DAY, WEEK, MONTH, YEAR);

    /**
     * Allowed values for the {@code sort} parameter besides {@code relevance}, mapped to their Solr
     * sort clause. The {@code relevance} default has no entry: no sort is bound and the default score
     * ordering applies.
     */
    private static final Map<String, String> SORT_CLAUSES = Map.of(
        NEWEST, DATE_DESC,
        OLDEST, DATE_ASC,
        TITLE, FieldUtils.TITLE_SORT + " asc");

    /**
     * All accepted {@code sort} values in display order, used for validation and the invalid-value
     * error message. Must stay in sync with {@link #SORT_CLAUSES} (plus {@code relevance}).
     */
    private static final List<String> SORT_VALUES = List.of(RELEVANCE, NEWEST, OLDEST, TITLE);

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant omits the
     * {@code wiki} parameter and keeps only wiki-prefix-free examples in the {@code author} description, so no
     * cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPQueryDocumentsTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentSearch documentSearch;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @Inject
    @Named("user")
    private DocumentReferenceResolver<String> userReferenceResolver;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    /**
     * Builds the declared parameter set, only including the cross-wiki {@code wiki} parameter and the
     * wiki-prefixed {@code author} example when cross-wiki reach is advertised, preserving the parameter order
     * in the advertised schema.
     *
     * @param crossWiki whether to advertise the cross-wiki {@code wiki} parameter
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        return MCPToolSupport.builder()
            .string(QUERY_PARAM, "Search terms in edismax syntax: \"exact phrases\", +required and "
                + "-excluded terms, OR, wildcard*. Rephrase the user's question into keywords. Matched "
                + "mainly against document titles (high weight) and content. Leave empty to browse/list "
                + "documents instead of searching.")
            .stringIf(crossWiki, WIKI_PARAM, "Which wiki to search: omit for this wiki, a wiki id for one "
                + "specific wiki, or \"all\" for every reachable wiki. list_wikis shows the reachable wikis.")
            .string(SPACE_PARAM, "Optional local space reference (e.g. \"Help\" or \"Help.Guides\"). "
                + "Restricts results to that space and all its children.")
            .string(AUTHOR_PARAM, "Optional last author. A user name or reference - \"Admin\""
                + (crossWiki ? ", \"XWiki.Admin\" and \"xwiki:XWiki.Admin\" all" : " and \"XWiki.Admin\" both")
                + " resolve to the same user.")
            .string(MODIFIED_WITHIN_PARAM, "Optional relative modification window. One of: day, week, "
                + "month, year. Ignored if 'modifiedRange' is also provided.")
            .string(MODIFIED_RANGE_PARAM, "Advanced. A raw Solr date-range expression on the last-modified "
                + "date, e.g. \"[NOW-7DAY TO NOW]\" or \"[2026-01-01T00:00:00Z TO NOW]\". "
                + "Overrides 'modifiedWithin' when both are given.")
            .string(SORT_PARAM, "Result ordering. One of: relevance (default), newest, oldest, title.")
            .integer(LIMIT_PARAM, "Maximum number of results to return (default: %d, max: %d)."
                .formatted(DEFAULT_LIMIT, MAX_LIMIT))
            .integer(OFFSET_PARAM, "0-based index of the first result to return (default: 0). Use with "
                + "limit to page through results; the result footer tells you the offset of the next page.")
            .bool(INCLUDE_HIDDEN_PARAM, "If true, also match hidden documents (technical pages excluded "
                + "from normal browsing, e.g. configuration and class definitions). Default false.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = PARAMS.advertised(this.wikiReach.isReachEnabled());
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description("Search XWiki documents in the wiki's Solr index, queried with Solr Extended "
                + "DisMax (edismax) syntax; also browses (empty query). Each result includes the document "
                + "reference (use with get_document) and a matched snippet. `man query_documents` shows "
                + "examples and filters.")
            .build();
    }

    @Override
    public String getCategory()
    {
        return "Search & Navigation";
    }

    @Override
    public String getSummary()
    {
        return "Search and browse XWiki documents via the wiki's Solr index.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                The total is an upper bound: it can include documents you are not allowed to view, so
                later pages may contain fewer results than expected.

            EXAMPLES
                Keywords:   query="script service groovy"
                Phrase:     query="\\"programming rights\\" -deprecated"
                In a space: query="release", space="Pro-Apps.Procedures"
                Recent:     sort="newest"   (no query)
                By date:    query="api", modifiedWithin="month"
                Next page:  query="api", offset=10

            SEE ALSO
                man get_document    Read a document found here in full, by its Reference.
                man                 (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            SearchRequest searchRequest = parseRequest(args);

            List<String> targetWikis;
            try {
                targetWikis = this.wikiReach.resolveSearchWikis(searchRequest.wiki());
            } catch (MCPAccessDeniedException e) {
                return MCPToolSupport.errorResult(e.getMessage());
            }

            QueryResponse response = executeSearch(searchRequest, targetWikis);
            return MCPToolSupport.result(formatResults(response, searchRequest));
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (QueryException e) {
            this.logger.warn("MCP query_documents tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP query_documents tool failure details", e);
            return MCPToolSupport.errorResult("Could not run the search. If you used query operators, check "
                + "that quotes and parentheses are balanced, or simplify the query. Run `man query_documents` "
                + "for query syntax examples.");
        }
    }

    private SearchRequest parseRequest(Map<String, Object> args)
    {
        String queryText = PARAMS.parser().string(args, QUERY_PARAM);
        String wiki = PARAMS.parser().string(args, WIKI_PARAM);
        int requestedLimit = PARAMS.parser().integer(args, LIMIT_PARAM, DEFAULT_LIMIT);
        int limit = clampLimit(requestedLimit);
        boolean limitCapped = requestedLimit > MAX_LIMIT;
        int offset = PARAMS.parser().integer(args, OFFSET_PARAM, 0);
        if (offset < 0) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + OFFSET_PARAM + "' must be >= 0.");
        }
        String space = PARAMS.parser().string(args, SPACE_PARAM);
        String author = normalizeAuthor(PARAMS.parser().string(args, AUTHOR_PARAM));
        String dateRange = resolveDateRange(PARAMS.parser().string(args, MODIFIED_WITHIN_PARAM),
            PARAMS.parser().string(args, MODIFIED_RANGE_PARAM));
        String sort = resolveSort(PARAMS.parser().string(args, SORT_PARAM));
        boolean includeHidden = PARAMS.parser().bool(args, INCLUDE_HIDDEN_PARAM);
        return new SearchRequest(queryText, limit, offset, space, author, dateRange, sort, includeHidden, wiki,
            limitCapped);
    }

    private QueryResponse executeSearch(SearchRequest request, List<String> targetWikis) throws QueryException
    {
        boolean browse = request.browse();
        String statement = browse ? BROWSE_STATEMENT : request.queryText();

        // The door creates the secure query, scopes it to the target wikis, applies each wiki's space filter and
        // binds the combined fq (wiki scope + space filter + these additional fqs); the tool only adds non-fq
        // params.
        Query query = this.documentSearch.createQuery(statement, buildFilterQueries(request), targetWikis);
        query.setLimit(request.limit());
        query.setOffset(request.offset());
        query.bindValue("qf", QF_WEIGHTS);
        query.bindValue(TIME_ALLOWED_KEY, TIME_ALLOWED_MS);

        bindSort(query, request.sort(), browse);

        if (!browse) {
            query.bindValue("hl", "true");
            query.bindValue("hl.fl", HL_CONTENT_FIELDS);
            query.bindValue("hl.snippets", "1");
            query.bindValue("hl.fragsize", "200");
            query.bindValue("hl.simple.pre", "");
            query.bindValue("hl.simple.post", "");
        }

        List<?> results = query.execute();
        if (results.isEmpty()) {
            return null;
        }
        return (QueryResponse) results.get(0);
    }

    private List<String> buildFilterQueries(SearchRequest request)
    {
        List<String> filterQueries = new ArrayList<>();
        filterQueries.add("type:DOCUMENT");
        if (!request.includeHidden()) {
            filterQueries.add("hidden:false");
        }

        if (StringUtils.isNotBlank(request.space())) {
            filterQueries.add(FieldUtils.SPACE_PREFIX + COLON
                + this.solrUtils.toCompleteFilterQueryString(request.space()));
        }

        if (StringUtils.isNotBlank(request.author())) {
            filterQueries.add(FieldUtils.AUTHOR + COLON
                + this.solrUtils.toCompleteFilterQueryString(request.author()));
        }

        if (request.dateRange() != null) {
            filterQueries.add(FieldUtils.DATE + COLON + request.dateRange());
        }

        return filterQueries;
    }

    private String resolveDateRange(String modifiedWithin, String modifiedRange)
    {
        if (StringUtils.isNotBlank(modifiedRange)) {
            String value = modifiedRange.trim();
            if (!DATE_RANGE_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + MODIFIED_RANGE_PARAM
                    + "' must be a Solr date range like [NOW-7DAY TO NOW] or "
                    + "[2026-01-01T00:00:00Z TO NOW].");
            }
            return value;
        }
        if (StringUtils.isBlank(modifiedWithin)) {
            return null;
        }
        String range = MODIFIED_WITHIN_RANGES.get(modifiedWithin);
        if (range == null) {
            throw invalidEnumValue(MODIFIED_WITHIN_PARAM, MODIFIED_WITHIN_VALUES);
        }
        return range;
    }

    private static IllegalArgumentException invalidEnumValue(String param, List<String> allowed)
    {
        return new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + param + "' must be one of: "
            + String.join(LIST_SEPARATOR, allowed) + PERIOD);
    }

    private String resolveSort(String sort)
    {
        if (StringUtils.isBlank(sort)) {
            return RELEVANCE;
        }
        if (!SORT_VALUES.contains(sort)) {
            throw invalidEnumValue(SORT_PARAM, SORT_VALUES);
        }
        return sort;
    }

    private void bindSort(Query query, String sort, boolean browse)
    {
        String clause = SORT_CLAUSES.get(sort);
        // In browse mode (blank query) the score is meaningless, so the relevance default falls back
        // to newest-first ordering, mirroring the platform's empty-query behavior.
        if (clause == null && browse) {
            clause = DATE_DESC;
        }
        if (clause != null) {
            query.bindValue(SORT_PARAM, clause);
        }
    }

    private String formatResults(QueryResponse response, SearchRequest request)
    {
        List<SolrDocument> documents = response != null ? response.getResults() : null;
        if (CollectionUtils.isEmpty(documents)) {
            return emptyResultsMessage(response, request);
        }

        Map<String, Map<String, List<String>>> highlighting = response.getHighlighting();
        // The score is only meaningful when results are actually ordered by it.
        boolean showScore = !request.browse() && RELEVANCE.equals(request.sort());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            if (i > 0) {
                sb.append(RESULT_SEPARATOR);
            }
            appendDocument(sb, documents.get(i), highlighting, showScore);
        }
        // numFound can never be smaller than the returned page in a real Solr response; the floor only
        // guards against a degenerate executor.
        long total = Math.max(response.getResults().getNumFound(), documents.size());
        return sb.toString().trim() + DOUBLE_NEW_LINE + buildFooter(total, documents.size(), request);
    }

    /**
     * Builds the message for an empty result page: a not-found message, a paging error when the offset
     * stepped past the last result, or a continue hint when the rights post-filter emptied a page that
     * is within range (raw matches exist but none on this page are viewable).
     *
     * @param response the query response, possibly {@code null}
     * @param request the parsed search request
     * @return the agent-facing message
     * @throws IllegalArgumentException when the offset is beyond the last result
     */
    private String emptyResultsMessage(QueryResponse response, SearchRequest request)
    {
        long total = response != null && response.getResults() != null
            ? response.getResults().getNumFound() : 0;
        if (total > 0) {
            if (request.offset() >= total) {
                throw new IllegalArgumentException("offset " + request.offset()
                    + " is beyond the last result (" + total + " total matches). Use an offset below "
                    + total + PERIOD);
            }
            return "No viewable documents in this page (" + total + " total matches before access "
                + "filtering). Continue with offset=" + (request.offset() + request.limit()) + PERIOD;
        }
        String base = request.browse() ? "No documents found."
            : "No documents found matching \"" + request.queryText() + "\".";
        return base + describeActiveFilters(request);
    }

    /**
     * Builds the trailing count/paging line. The continuation offset steps by {@code limit} (not by the
     * number of returned documents): the rights post-filter can shrink a page after Solr pagination, so
     * stepping by the returned size could skip raw results.
     *
     * @param total the total number of raw matches reported by Solr (before the rights post-filter)
     * @param shown the number of documents in this page after the rights post-filter
     * @param request the parsed search request
     * @return the footer line
     */
    private String buildFooter(long total, int shown, SearchRequest request)
    {
        String suffix = total == 1 ? " matching document." : " matching documents.";
        if (request.offset() == 0 && shown >= total) {
            // Every raw match was returned and none were filtered out, so the count is exact.
            return "Found " + total + suffix;
        }
        String footer = "Found about " + total + suffix + " Showing " + shown + " from offset "
            + request.offset() + PERIOD;
        long nextOffset = (long) request.offset() + request.limit();
        if (nextOffset < total) {
            footer += " Continue with offset=" + nextOffset + PERIOD;
        }
        if (request.limitCapped() && total > MAX_LIMIT) {
            footer += " (The requested limit was capped to the maximum of " + MAX_LIMIT + " per page.)";
        }
        return footer;
    }

    private void appendDocument(StringBuilder sb, SolrDocument doc,
        Map<String, Map<String, List<String>>> highlighting, boolean showScore)
    {
        // Determine the locale for locale-specific field reads.
        String localeStr = (String) doc.get(FieldUtils.LOCALE);
        Locale locale = StringUtils.isNotBlank(localeStr) ? Locale.forLanguageTag(localeStr) : Locale.ROOT;

        String title = (String) doc.getFirstValue(FieldUtils.getFieldName(FieldUtils.TITLE, locale));
        String wiki = (String) doc.get(FieldUtils.WIKI);
        String fullname = (String) doc.get(FieldUtils.FULLNAME);

        sb.append("Title: ").append(title != null ? title : "(untitled)").append(NEW_LINE);
        sb.append("Reference: ").append(serializedReference(wiki, fullname)).append(NEW_LINE);

        String url = safeDocumentUrl(wiki, fullname);
        if (StringUtils.isNotBlank(url)) {
            sb.append("URL: ").append(url).append(NEW_LINE);
        }

        appendModified(sb, doc);

        Object score = doc.get(SCORE_FIELD);
        if (showScore && score instanceof Number n) {
            sb.append("Score: ").append(String.format(Locale.ROOT, "%.2f", n.doubleValue())).append(NEW_LINE);
        }

        String snippet = resolveSnippet(doc, locale, highlighting);
        if (StringUtils.isNotBlank(snippet)) {
            sb.append("Snippet: ").append(snippet).append(NEW_LINE);
        }
    }

    /**
     * Appends the {@code Modified:} line: the last modification instant (UTC, ISO-8601) and the
     * last author as a serialized user reference — the same form the {@code author} filter parameter
     * accepts, so the value can be fed straight back into a follow-up query.
     *
     * @param sb the result buffer
     * @param doc the result document
     */
    private void appendModified(StringBuilder sb, SolrDocument doc)
    {
        String modified = MCPToolSupport.isoInstant(doc.get(FieldUtils.DATE));
        if (modified == null) {
            return;
        }
        sb.append("Modified: ").append(modified);
        Object author = doc.get(FieldUtils.AUTHOR);
        if (author instanceof String authorRef && StringUtils.isNotBlank(authorRef)) {
            sb.append(" by ").append(authorRef);
        }
        sb.append(NEW_LINE);
    }

    private String serializedReference(String wiki, String fullname)
    {
        return StringUtils.isNotBlank(wiki) ? wiki + COLON + fullname : fullname;
    }

    private String safeDocumentUrl(String wiki, String fullname)
    {
        String serialized = serializedReference(wiki, fullname);
        try {
            return this.documentAccessBridge.getDocumentURL(
                this.referenceResolver.resolve(serialized), VIEW_ACTION, null, null, true);
        } catch (Exception e) {
            this.logger.debug("MCP query_documents tool could not build a view URL for [{}]", serialized, e);
            return null;
        }
    }

    private String resolveSnippet(SolrDocument doc, Locale locale,
        Map<String, Map<String, List<String>>> highlighting)
    {
        String highlight = extractHighlight(doc, locale, highlighting);
        if (StringUtils.isNotBlank(highlight)) {
            return highlight.replaceAll(HTML_TAG_REGEX, "").replace(NEW_LINE, " ").trim();
        }
        return contentHeadSnippet(doc, locale);
    }

    /**
     * Returns the raw Solr highlight fragment for the document's rendered content, or {@code null}
     * when none is available.
     *
     * <p>The platform does not trim the highlighting map by view rights, so we only ever look up
     * highlights keyed by a document that already survived the rights post-filter. Callers must
     * therefore iterate {@code response.getResults()} and never the raw highlighting map directly.</p>
     *
     * <p>Highlighting is keyed by the real per-locale field name (e.g. {@code doccontent_en}), never
     * by the logical {@code doccontent}. The document's locale-specific field is tried first; if the
     * doc's {@code locale} disagrees with the field that actually matched, a scan over the
     * {@link #CONTENT_FIELD_PREFIX}-prefixed entries recovers the fragment.</p>
     *
     * @param doc the surviving result document
     * @param locale the document's locale, used to compute its per-locale content field name
     * @param highlighting the (unfiltered) highlighting map from the query response
     * @return the first content highlight fragment, or {@code null} if there is none
     */
    private String extractHighlight(SolrDocument doc, Locale locale,
        Map<String, Map<String, List<String>>> highlighting)
    {
        if (highlighting == null) {
            return null;
        }
        String id = (String) doc.get(FieldUtils.ID);
        if (id == null) {
            return null;
        }
        Map<String, List<String>> docHighlights = highlighting.get(id);
        if (docHighlights == null) {
            return null;
        }

        String contentField = FieldUtils.getFieldName(FieldUtils.DOCUMENT_RENDERED_CONTENT, locale);
        List<String> snippets = docHighlights.get(contentField);
        if (CollectionUtils.isNotEmpty(snippets)) {
            return snippets.get(0);
        }

        return scanContentHighlight(docHighlights);
    }

    /**
     * Falls back to the first non-empty snippet held by any per-locale rendered-content field, to
     * cover the case where the document's {@code locale} field disagrees with the field that the
     * search actually matched and highlighted.
     *
     * @param docHighlights the per-field highlight map for a single document
     * @return the first available content fragment, or {@code null} if none
     */
    private String scanContentHighlight(Map<String, List<String>> docHighlights)
    {
        for (Map.Entry<String, List<String>> entry : docHighlights.entrySet()) {
            if (!entry.getKey().startsWith(CONTENT_FIELD_PREFIX)) {
                continue;
            }
            List<String> values = entry.getValue();
            if (CollectionUtils.isNotEmpty(values)) {
                return values.get(0);
            }
        }
        return null;
    }

    private String contentHeadSnippet(SolrDocument doc, Locale locale)
    {
        String content =
            (String) doc.getFirstValue(FieldUtils.getFieldName(FieldUtils.DOCUMENT_RENDERED_CONTENT, locale));
        if (StringUtils.isBlank(content)) {
            return null;
        }
        String snippet = content.length() > SNIPPET_MAX_LENGTH
            ? content.substring(0, SNIPPET_MAX_LENGTH) + "..."
            : content;
        return snippet.replace(NEW_LINE, " ").trim();
    }

    private int clampLimit(int limit)
    {
        return Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);
    }

    /**
     * Normalizes an author filter into the serialized user reference the Solr {@code author} field stores,
     * so that a bare user name matches. Resolving through the {@code user} resolver defaults the space to
     * {@code XWiki} and the wiki to the current one, so {@code "Admin"}, {@code "XWiki.Admin"} and
     * {@code "xwiki:XWiki.Admin"} all normalize to the same reference. A blank value is returned unchanged;
     * a value that cannot be resolved is left as-is so it is still applied (and surfaced by the
     * empty-result filter echo).
     *
     * @param author the raw author argument, possibly blank
     * @return the serialized user reference, or the original value when blank or unresolvable
     */
    private String normalizeAuthor(String author)
    {
        if (StringUtils.isBlank(author)) {
            return author;
        }
        try {
            DocumentReference userReference = this.userReferenceResolver.resolve(author);
            return userReference != null ? this.entityReferenceSerializer.serialize(userReference) : author;
        } catch (RuntimeException e) {
            this.logger.debug("MCP query_documents could not normalize author [{}]", author, e);
            return author;
        }
    }

    /**
     * Describes the narrowing filters that were applied, so an empty result set tells the agent what it
     * constrained by (a wrong space or author is otherwise indistinguishable from a query that matched
     * nothing). The author is shown in its normalized form.
     *
     * @param request the parsed search request
     * @return a trailing sentence listing the active filters, or an empty string when none were set
     */
    private String describeActiveFilters(SearchRequest request)
    {
        List<String> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(request.space())) {
            parts.add("space=" + request.space());
        }
        if (StringUtils.isNotBlank(request.author())) {
            parts.add("author=" + request.author());
        }
        if (request.dateRange() != null) {
            parts.add("modified=" + request.dateRange());
        }
        if (StringUtils.isNotBlank(request.wiki())) {
            parts.add("wiki=" + request.wiki());
        }
        return parts.isEmpty() ? "" : " Active filters: " + String.join(LIST_SEPARATOR, parts) + PERIOD;
    }

    /**
     * The parsed and validated arguments of a search call, bundled so the query-building and
     * result-formatting steps share one immutable view of the request.
     *
     * @param queryText the search terms, or {@code null}/blank for browse mode
     * @param limit the page size, already clamped to the allowed range
     * @param offset the 0-based index of the first raw result to return, already validated as &gt;= 0
     * @param space the optional local space reference filter
     * @param author the optional last-author filter
     * @param dateRange the resolved Solr date-range token for the last-modified filter, or {@code null}
     * @param sort the resolved sort key, one of the allowed sort values
     * @param includeHidden whether hidden documents are included
     * @param wiki the raw {@code wiki} parameter value (blank for this wiki, a wiki id, or {@code "all"})
     * @param limitCapped whether the requested limit exceeded {@link #MAX_LIMIT} and was reduced to it
     * @version $Id$
     */
    private record SearchRequest(String queryText, int limit, int offset, String space, String author,
        String dateRange, String sort, boolean includeHidden, String wiki, boolean limitCapped)
    {
        /**
         * @return whether this is a browse (blank query) rather than a search
         */
        boolean browse()
        {
            return StringUtils.isBlank(this.queryText);
        }
    }
}
