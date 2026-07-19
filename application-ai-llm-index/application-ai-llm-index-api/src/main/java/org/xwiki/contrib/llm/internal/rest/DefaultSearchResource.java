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
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.StringUtils;
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
    /**
     * Shared tail of the locale rejection messages, naming the accepted example forms.
     */
    private static final String LOCALE_FORMS_HINT = ", use forms like \"fr\" or \"pt_BR\".";

    /**
     * Longest locale string the wiki stores (5 characters, e.g. "pt_BR"). This is the same cap as
     * MCPToolSupport#MAX_STORED_LOCALE_LENGTH in the mcp-api module, duplicated as a local constant so this REST
     * resource keeps no MCP imports. A longer filter can only return empty results with a confusing contract, and
     * its raw variant text would otherwise reach Solr-adjacent logging.
     */
    private static final int MAX_STORED_LOCALE_LENGTH = 5;

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
        int limitSemanticResults,
        String locale
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
                limitSemanticResults,
                parseLocale(locale)
            );
        } catch (IndexException e) {
            throw new XWikiRestException("Failed to search", e);
        } finally {
            context.setWikiId(currentWiki);
        }
    }

    private Locale parseLocale(String locale)
    {
        if (StringUtils.isBlank(locale)) {
            return null;
        }
        Locale parsedLocale;
        try {
            parsedLocale = LocaleUtils.toLocale(locale);
        } catch (IllegalArgumentException e) {
            // Static message on purpose: the invalid value is not reflected back.
            throw badRequest("The locale parameter is not a valid locale" + LOCALE_FORMS_HINT);
        }
        if (parsedLocale.toString().length() > MAX_STORED_LOCALE_LENGTH) {
            throw badRequest("The locale parameter is longer than any stored locale (max "
                + MAX_STORED_LOCALE_LENGTH + " characters)" + LOCALE_FORMS_HINT);
        }
        return parsedLocale;
    }

    private void validateLimit(int limit)
    {
        // In this case, <= 0 means 0 results and not unlimited, so we only check for positive limits.
        int configuredLimit = this.llmSecurityConfiguration.getQueryItemsLimit();
        if (configuredLimit > 0 && limit > configuredLimit) {
            throw badRequest("Limit must be less than or equal to " + configuredLimit);
        }
    }

    private WebApplicationException badRequest(String message)
    {
        return new WebApplicationException(
            Response.status(Response.Status.BAD_REQUEST)
                .entity(message)
                .type(MediaType.TEXT_PLAIN)
                .build());
    }
}
