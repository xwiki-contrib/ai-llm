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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.query.SecureQuery;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;

import com.xpn.xwiki.XWikiContext;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that searches and browses XWiki pages using the platform's Solr search index.
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
 * <p>Authorization is enforced by {@link SecureQuery#checkCurrentUser(boolean)}: the
 * {@code SolrQueryExecutor} post-filters results to remove any documents the current
 * user does not have view access to.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPQueryPagesTool.TOOL_ID)
@Singleton
public class MCPQueryPagesTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "query_pages";

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

    private static final int MAX_LIMIT = 50;

    private static final int MIN_LIMIT = 1;

    private static final int SNIPPET_MAX_LENGTH = 200;

    private static final String QUERY_PARAM = "query";

    private static final String LIMIT_PARAM = "limit";

    private static final String SPACE_PARAM = "space";

    private static final String AUTHOR_PARAM = "author";

    private static final String MODIFIED_WITHIN_PARAM = "modifiedWithin";

    private static final String MODIFIED_RANGE_PARAM = "modifiedRange";

    private static final String SORT_PARAM = "sort";

    private static final String RELEVANCE = "relevance";

    private static final String SCORE_FIELD = "score";

    private static final String DATE_DESC = "date desc";

    private static final String DATE_ASC = "date asc";

    private static final String OBJECT = "object";

    private static final String STRING = "string";

    private static final String INTEGER = "integer";

    private static final String TYPE = "type";

    private static final String DESCRIPTION = "description";

    private static final String COLON = ":";

    private static final String NEW_LINE = "\n";

    private static final String RESULT_SEPARATOR = NEW_LINE + "---" + NEW_LINE;

    private static final String ERROR_PREFIX = "Error: '";

    private static final String STRING_PARAM_ERROR_SUFFIX = "' parameter must be a string.";

    private static final String INTEGER_PARAM_ERROR_SUFFIX = "' parameter must be an integer.";

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

    /**
     * Allowed values for the {@code modifiedWithin} parameter, mapped to their Solr date-range
     * expressions on the last-modified date field.
     */
    private static final Map<String, String> MODIFIED_WITHIN_RANGES = Map.of(
        "day", "[NOW-1DAY TO NOW]",
        "week", "[NOW-7DAY TO NOW]",
        "month", "[NOW-1MONTH TO NOW]",
        "year", "[NOW-1YEAR TO NOW]"
    );

    /**
     * Allowed values for the {@code sort} parameter, mapped to their Solr sort clause. The
     * {@code relevance} value maps to {@code null}, meaning no sort is bound and the default
     * score ordering applies.
     */
    private static final Map<String, String> SORT_CLAUSES = new LinkedHashMap<>();

    static {
        SORT_CLAUSES.put(RELEVANCE, null);
        SORT_CLAUSES.put("newest", DATE_DESC);
        SORT_CLAUSES.put("oldest", DATE_ASC);
        SORT_CLAUSES.put("title", FieldUtils.TITLE_SORT + " asc");
    }

    @Inject
    private Logger logger;

    @Inject
    private QueryManager queryManager;

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        Map<String, Object> properties = Map.of(
            QUERY_PARAM, Map.of(
                TYPE, STRING,
                DESCRIPTION, "Search terms. Rephrase the user's question into keywords. Supports "
                    + "\"exact phrases\", +required and -excluded terms. Matched mainly against page titles "
                    + "(high weight) and content. Leave empty to browse/list pages instead of searching."
            ),
            SPACE_PARAM, Map.of(
                TYPE, STRING,
                DESCRIPTION, "Optional local space reference (e.g. \"Help\" or \"Help.Guides\"). "
                    + "Restricts results to that space and all its children."
            ),
            AUTHOR_PARAM, Map.of(
                TYPE, STRING,
                DESCRIPTION, "Optional last author. Expects a serialized user reference, "
                    + "e.g. \"xwiki:XWiki.Admin\"."
            ),
            MODIFIED_WITHIN_PARAM, Map.of(
                TYPE, STRING,
                DESCRIPTION, "Optional relative modification window. One of: day, week, month, year. "
                    + "Ignored if 'modifiedRange' is also provided."
            ),
            MODIFIED_RANGE_PARAM, Map.of(
                TYPE, STRING,
                DESCRIPTION, "Advanced. A raw Solr date-range expression on the last-modified date, "
                    + "e.g. \"[NOW-7DAY TO NOW]\" or \"[2026-01-01T00:00:00Z TO NOW]\". "
                    + "Overrides 'modifiedWithin' when both are given."
            ),
            SORT_PARAM, Map.of(
                TYPE, STRING,
                DESCRIPTION, "Result ordering. One of: relevance (default), newest, oldest, title."
            ),
            LIMIT_PARAM, Map.of(
                TYPE, INTEGER,
                DESCRIPTION, "Maximum number of results to return (default: %d, max: %d)."
                    .formatted(DEFAULT_LIMIT, MAX_LIMIT)
            )
        );
        return McpSchema.Tool.builder()
            .name(TOOL_ID)
            .description("Search and browse XWiki pages via the wiki's Solr index. Rephrase the user's "
                + "question into keywords; supports \"exact phrases\", +required and -excluded terms. "
                + "Leave 'query' empty to browse/list pages (e.g. recent changes). Optional filters: "
                + "space (subtree), author, modification date (modifiedWithin: day|week|month|year, or "
                + "modifiedRange for a raw Solr date range); and sort (relevance|newest|oldest|title). "
                + "Returns title, page reference, relevance score, and a matched snippet.")
            .inputSchema(new McpSchema.JsonSchema(OBJECT, properties, List.of(), null, null, null))
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String queryText = getOptionalStringParam(args, QUERY_PARAM);
            int limit = clampLimit(getIntParam(args, LIMIT_PARAM, DEFAULT_LIMIT));
            String space = getOptionalStringParam(args, SPACE_PARAM);
            String author = getOptionalStringParam(args, AUTHOR_PARAM);
            String modifiedWithin = getOptionalStringParam(args, MODIFIED_WITHIN_PARAM);
            String modifiedRange = getOptionalStringParam(args, MODIFIED_RANGE_PARAM);
            String sort = resolveSort(getOptionalStringParam(args, SORT_PARAM));

            QueryResponse response = executeSearch(queryText, limit, space, author, modifiedWithin,
                modifiedRange, sort);
            return McpSchema.CallToolResult.builder()
                .addTextContent(formatResults(response, queryText))
                .build();
        } catch (IllegalArgumentException e) {
            return buildErrorResult(e.getMessage());
        } catch (QueryException e) {
            this.logger.warn("MCP query_pages tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP query_pages tool failure details", e);
            return buildErrorResult("Could not run the search. If you used query operators, check that quotes "
                + "and parentheses are balanced, or simplify the query. Details: "
                + ExceptionUtils.getRootCauseMessage(e));
        }
    }

    private QueryResponse executeSearch(String queryText, int limit, String space, String author,
        String modifiedWithin, String modifiedRange, String sort) throws QueryException
    {
        boolean browse = StringUtils.isBlank(queryText);
        String statement = browse ? BROWSE_STATEMENT : queryText;

        List<String> filterQueries = buildFilterQueries(space, author, modifiedWithin, modifiedRange);

        Query query = this.queryManager.createQuery(statement, "solr");
        ((SecureQuery) query).checkCurrentUser(true);
        query.setLimit(limit);
        query.bindValue("qf", QF_WEIGHTS);
        query.bindValue("fq", filterQueries);
        query.bindValue(TIME_ALLOWED_KEY, TIME_ALLOWED_MS);

        bindSort(query, sort, browse);

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

    private List<String> buildFilterQueries(String space, String author, String modifiedWithin,
        String modifiedRange)
    {
        List<String> filterQueries = new ArrayList<>();
        filterQueries.add("type:DOCUMENT");
        filterQueries.add("hidden:false");

        XWikiContext xcontext = this.contextProvider.get();
        if (xcontext != null && StringUtils.isNotBlank(xcontext.getWikiId())) {
            filterQueries.add(FieldUtils.WIKI + COLON
                + this.solrUtils.toCompleteFilterQueryString(xcontext.getWikiId()));
        }

        if (StringUtils.isNotBlank(space)) {
            filterQueries.add(FieldUtils.SPACE_PREFIX + COLON
                + this.solrUtils.toCompleteFilterQueryString(space));
        }

        if (StringUtils.isNotBlank(author)) {
            filterQueries.add(FieldUtils.AUTHOR + COLON
                + this.solrUtils.toCompleteFilterQueryString(author));
        }

        String dateRange = resolveDateRange(modifiedWithin, modifiedRange);
        if (dateRange != null) {
            filterQueries.add(FieldUtils.DATE + COLON + dateRange);
        }

        return filterQueries;
    }

    private String resolveDateRange(String modifiedWithin, String modifiedRange)
    {
        if (StringUtils.isNotBlank(modifiedRange)) {
            String value = modifiedRange.trim();
            if (!DATE_RANGE_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException(ERROR_PREFIX + MODIFIED_RANGE_PARAM
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
            throw invalidEnumValue(MODIFIED_WITHIN_PARAM, MODIFIED_WITHIN_RANGES.keySet());
        }
        return range;
    }

    private static IllegalArgumentException invalidEnumValue(String param, Set<String> allowed)
    {
        return new IllegalArgumentException(ERROR_PREFIX + param + "' must be one of: "
            + String.join(", ", allowed) + ".");
    }

    private String resolveSort(String sort)
    {
        if (StringUtils.isBlank(sort)) {
            return RELEVANCE;
        }
        if (!SORT_CLAUSES.containsKey(sort)) {
            throw invalidEnumValue(SORT_PARAM, SORT_CLAUSES.keySet());
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

    private String formatResults(QueryResponse response, String queryText)
    {
        boolean browse = StringUtils.isBlank(queryText);
        SolrDocumentList documents = response != null ? response.getResults() : null;
        if (documents == null || documents.isEmpty()) {
            return browse ? "No pages found." : "No pages found matching \"" + queryText + "\".";
        }

        Map<String, Map<String, List<String>>> highlighting = response.getHighlighting();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            if (i > 0) {
                sb.append(RESULT_SEPARATOR);
            }
            appendDocument(sb, documents.get(i), highlighting);
        }
        return sb.toString().trim();
    }

    private void appendDocument(StringBuilder sb, SolrDocument doc,
        Map<String, Map<String, List<String>>> highlighting)
    {
        // Determine the locale for locale-specific field reads.
        String localeStr = (String) doc.get(FieldUtils.LOCALE);
        Locale locale = StringUtils.isNotBlank(localeStr) ? Locale.forLanguageTag(localeStr) : Locale.ROOT;

        String title = (String) doc.getFirstValue(FieldUtils.getFieldName(FieldUtils.TITLE, locale));
        String wiki = (String) doc.get(FieldUtils.WIKI);
        String fullname = (String) doc.get(FieldUtils.FULLNAME);

        sb.append("Title: ").append(title != null ? title : "(untitled)").append(NEW_LINE);
        sb.append("Reference: ").append(wiki != null ? wiki + COLON + fullname : fullname).append(NEW_LINE);

        Object score = doc.get(SCORE_FIELD);
        if (score instanceof Number n) {
            sb.append("Score: ").append(String.format(Locale.ROOT, "%.2f", n.doubleValue())).append(NEW_LINE);
        }

        String snippet = resolveSnippet(doc, locale, highlighting);
        if (StringUtils.isNotBlank(snippet)) {
            sb.append("Snippet: ").append(snippet).append(NEW_LINE);
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
        if (snippets != null && !snippets.isEmpty()) {
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
            if (values != null && !values.isEmpty()) {
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

    private String getOptionalStringParam(Map<String, Object> args, String key)
    {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException(ERROR_PREFIX + key + STRING_PARAM_ERROR_SUFFIX);
        }
        return StringUtils.trimToNull(str);
    }

    private int getIntParam(Map<String, Object> args, String key, int defaultValue)
    {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !StringUtils.isBlank(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(ERROR_PREFIX + key + INTEGER_PARAM_ERROR_SUFFIX, e);
            }
        }
        throw new IllegalArgumentException(ERROR_PREFIX + key + INTEGER_PARAM_ERROR_SUFFIX);
    }

    private McpSchema.CallToolResult buildErrorResult(String message)
    {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .isError(true)
            .build();
    }
}
