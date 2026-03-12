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
package org.xwiki.contrib.llm.internal.rest;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.contrib.llm.rest.SearchResource;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.SecurityConfiguration;

import com.xpn.xwiki.XWikiContext;

/**
 * Default implementation of {@link SearchResource}.
 *
 * @version $Id$
 * @since 0.8
 */
@Component
@Named("org.xwiki.contrib.llm.internal.rest.DefaultSearchResource")
public class DefaultSearchResource extends XWikiResource implements SearchResource
{
    @Inject
    @Named("currentUser")
    private CollectionManager collectionManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    // We don't use the generic name as it would conflict with the SecurityConfiguration component
    // in the parent class.
    private SecurityConfiguration llmSecurityConfiguration;

    @Override
    public List<Context> search(
        String wikiName,
        String query,
        List<String> collections,
        int limitKeywordResults,
        int limitSemanticResults
    ) throws XWikiRestException
    {
        XWikiContext context = this.contextProvider.get();

        String currentWiki = context.getWikiId();

        try {
            context.setWikiId(wikiName);

            this.validateLimit(limitKeywordResults);
            this.validateLimit(limitSemanticResults);

            List<String> collectionsToSearch =
                collections.isEmpty() ? this.collectionManager.getCollections() : collections;

            // Authorization is handled by the CollectionManager, only actually accessible collections are searched,
            // and results are filtered accordingly.
            return this.collectionManager.hybridSearch(
                query,
                collectionsToSearch,
                limitKeywordResults,
                limitSemanticResults
            );
        } catch (IndexException e) {
            throw new XWikiRestException("Failed to search", e);
        } finally {
            context.setWikiId(currentWiki);
        }
    }

    private void validateLimit(int limit)
    {
        // In this case, <= 0 means 0 results and not unlimited, so we only check for positive limits.
        int configuredLimit = this.llmSecurityConfiguration.getQueryItemsLimit();
        if (configuredLimit > 0 && limit > configuredLimit) {
            throw new WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Limit must be less than or equal to " + configuredLimit)
                    .type(MediaType.TEXT_PLAIN)
                    .build());
        }
    }
}
