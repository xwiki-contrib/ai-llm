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

import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.rest.XWikiResource;

import com.xpn.xwiki.XWikiContext;

/**
 * Abstract base class for collection resources.
 *
 * @version $Id$
 */
public abstract class AbstractCollectionResource extends XWikiResource
{
    @Inject
    protected CollectionManager collectionManager;

    @Inject
    protected Provider<XWikiContext> contextProvider;

    @Inject
    protected Logger logger;

    protected Collection getInternalCollection(String wikiName, String collectionName)
    {
        XWikiContext context = this.contextProvider.get();

        String currentWiki = context.getWikiId();

        try {
            context.setWikiId(wikiName);

            // TODO: How to handle rights?
            Collection collection = this.collectionManager.getCollection(collectionName);
            if (collection == null) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }

            return collection;
        } catch (IndexException e) {
            this.logger.error("Error retriving internal collection with name [{}]: [{}]",
                collectionName, e.getMessage());
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            context.setWikiId(currentWiki);
        }
    }
}
