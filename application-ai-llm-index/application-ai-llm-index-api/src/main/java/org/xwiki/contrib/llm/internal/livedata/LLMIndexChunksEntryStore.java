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

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocumentList;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.livedata.LiveData;
import org.xwiki.livedata.LiveDataEntryStore;
import org.xwiki.livedata.LiveDataException;
import org.xwiki.livedata.LiveDataQuery;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;

/**
 * The store for the LLM index chunk entries.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Singleton
@Named("llmIndexChunks")
public class LLMIndexChunksEntryStore implements LiveDataEntryStore
{
    @Inject
    private ContextualAuthorizationManager contextualAuthorizationManager;

    @Inject
    private Provider<Map<String, LLMIndexProperty>> propertiesProvider;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Solr solr;

    @Inject
    private SolrUtils solrUtils;

    @Override
    public Optional<Map<String, Object>> get(Object entryId) throws LiveDataException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public LiveData get(LiveDataQuery query) throws LiveDataException
    {
        XWikiContext context = this.contextProvider.get();
        if (!this.contextualAuthorizationManager.hasAccess(Right.ADMIN, context.getWikiReference())) {
            throw new LiveDataException("Access denied, access is only granted for wiki admins.");
        }

        Map<String, LLMIndexProperty> properties = this.propertiesProvider.get();

        try (SolrClient client = this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            SolrQuery solrQuery = new SolrQuery();

            solrQuery.setStart(Math.toIntExact(query.getOffset()));
            solrQuery.setRows(query.getLimit());

            for (LiveDataQuery.SortEntry sortEntry : query.getSort()) {
                LLMIndexProperty property = properties.get(sortEntry.getProperty());
                if (property != null) {
                    solrQuery.addSort(property.getSolrProperty(),
                        sortEntry.isDescending() ? SolrQuery.ORDER.desc : SolrQuery.ORDER.asc);
                }
            }

            // Restrict searching to the current wiki, or chunks indexed in LLM version < 0.4 without the wiki field.
            solrQuery.addFilterQuery("%s:%s OR (*:* AND -%s:[* TO *])".formatted(
                AiLLMSolrCoreInitializer.FIELD_WIKI,
                this.solrUtils.toCompleteFilterQueryString(context.getWikiId()),
                AiLLMSolrCoreInitializer.FIELD_WIKI
            ));

            for (LiveDataQuery.Filter filter : query.getFilters()) {
                LLMIndexProperty property = properties.get(filter.getProperty());
                if (property != null) {
                    String filterQuery = property.getFilterQuery(filter);
                    if (StringUtils.isNotBlank(filterQuery)) {
                        solrQuery.addFilterQuery(filterQuery);
                    }
                }
            }

            LiveData result = new LiveData();

            SolrDocumentList results = client.query(solrQuery).getResults();
            result.setCount(results.getNumFound());
            result.getEntries().addAll(
                results.stream().map(solrDocument ->
                    properties.values().stream()
                        .collect(Collectors.toMap(LLMIndexProperty::getId, p -> p.getValue(solrDocument)))
                ).toList());

            return result;
        } catch (SolrException | IOException | SolrServerException e) {
            throw new LiveDataException("Failed to query the Solr index", e);
        }
    }
}
