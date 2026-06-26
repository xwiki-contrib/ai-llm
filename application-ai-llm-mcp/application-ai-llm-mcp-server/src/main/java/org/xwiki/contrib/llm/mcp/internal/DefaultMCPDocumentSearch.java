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

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.query.SecureQuery;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;

import com.xpn.xwiki.XWikiContext;

/**
 * Default {@link MCPDocumentSearch}: builds a secure Solr query scoped to the current wiki and narrowed by the
 * wiki's {@link MCPSpaceFilter}, with current-user view-rights post-filtering enabled.
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

    @Inject
    private QueryManager queryManager;

    @Inject
    private MCPSpaceFilter spaceFilter;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private SolrUtils solrUtils;

    @Override
    public Query createQuery(String statement, List<String> additionalFilterQueries) throws QueryException
    {
        Query query = this.queryManager.createQuery(statement, SOLR);
        // Deliberate raw cast (instead of the platform's instanceof pattern): if a Solr query were ever
        // not a SecureQuery, failing loudly here is safer than silently skipping the rights check.
        ((SecureQuery) query).checkCurrentUser(true);

        XWikiContext context = this.contextProvider.get();
        String wikiId = context == null ? null : context.getWikiId();
        List<String> filterQueries = new ArrayList<>();
        if (context != null && StringUtils.isNotBlank(wikiId)) {
            filterQueries.add(FieldUtils.WIKI + COLON + this.solrUtils.toCompleteFilterQueryString(wikiId));
        }
        filterQueries.addAll(this.spaceFilter.filterQueries(wikiId));
        if (additionalFilterQueries != null) {
            filterQueries.addAll(additionalFilterQueries);
        }
        query.bindValue("fq", filterQueries);
        return query;
    }
}
