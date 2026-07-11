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
package org.xwiki.contrib.llm.mcp.internal.access;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPDocumentSearch;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.query.SecureQuery;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;

/**
 * Default {@link MCPDocumentSearch}: builds a secure Solr query scoped to the requested wikis (or the whole farm)
 * and narrowed by the source endpoint's {@link MCPSpaceFilter}, with current-user view-rights post-filtering
 * enabled.
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Singleton
public class DefaultMCPDocumentSearch implements MCPDocumentSearch
{
    private static final String SOLR = "solr";

    private static final String COLON = ":";

    private static final String OPEN_PAREN = "(";

    private static final String CLOSE_PAREN = ")";

    /** Solr filter query that matches no document; a defensive wiki scope when an empty target list was supplied. */
    private static final String MATCH_NOTHING = "-*:*";

    @Inject
    private QueryManager queryManager;

    @Inject
    private MCPSpaceFilter spaceFilter;

    @Inject
    private SolrUtils solrUtils;

    @Override
    public Query createQuery(String statement, List<String> additionalFilterQueries, List<String> targetWikiIds)
        throws QueryException
    {
        Query query = this.queryManager.createQuery(statement, SOLR);
        // Deliberate raw cast (instead of the platform's instanceof pattern): if a Solr query were ever
        // not a SecureQuery, failing loudly here is safer than silently skipping the rights check.
        ((SecureQuery) query).checkCurrentUser(true);

        List<String> filterQueries = new ArrayList<>(wikiScopeClauses(targetWikiIds));
        filterQueries.addAll(this.spaceFilter.filterQueries());
        if (additionalFilterQueries != null) {
            filterQueries.addAll(additionalFilterQueries);
        }
        query.bindValue("fq", filterQueries);
        return query;
    }

    /**
     * Builds the wiki-scope filter-query clauses for the given target wikis. A {@code null} list means the whole
     * farm, so no wiki-scope clause is added. An empty list is a defensive no-match. A single wiki is scoped with
     * one {@code wiki:W} clause; several are OR-joined into one clause (kept general for safety, though in practice
     * only {@code null} or a singleton reaches here).
     *
     * @param targetWikiIds the wikis to scope the search to, or {@code null} for the whole farm
     * @return the wiki-scope filter-query clauses
     */
    private List<String> wikiScopeClauses(List<String> targetWikiIds)
    {
        if (targetWikiIds == null) {
            return List.of();
        }
        if (targetWikiIds.isEmpty()) {
            return List.of(MATCH_NOTHING);
        }
        if (targetWikiIds.size() == 1) {
            return List.of(wikiClause(targetWikiIds.get(0)));
        }
        List<String> disjuncts = new ArrayList<>();
        for (String wiki : targetWikiIds) {
            disjuncts.add(wikiClause(wiki));
        }
        return List.of(OPEN_PAREN + String.join(" OR ", disjuncts) + CLOSE_PAREN);
    }

    private String wikiClause(String wiki)
    {
        return FieldUtils.WIKI + COLON + this.solrUtils.toCompleteFilterQueryString(wiki);
    }
}
