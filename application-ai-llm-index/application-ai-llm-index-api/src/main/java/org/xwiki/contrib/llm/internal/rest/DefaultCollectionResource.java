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
import java.util.stream.Collectors;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.rest.CollectionResource;
import org.xwiki.contrib.llm.rest.JSONCollection;
import org.xwiki.rest.XWikiRestException;

import com.xpn.xwiki.XWikiContext;

/**
 * Default implementation of {@link CollectionResource}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named("org.xwiki.contrib.llm.internal.rest.DefaultCollectionResource")
@Singleton
public class DefaultCollectionResource extends AbstractCollectionResource implements CollectionResource
{
    @Override
    public JSONCollection getCollection(String wikiName, String collectionName)
        throws XWikiRestException
    {
        return new JSONCollection(getInternalCollection(wikiName, collectionName));
    }

    @Override
    public JSONCollection putCollection(String wikiName, String collectionName,
        JSONCollection collection) throws XWikiRestException
    {
        XWikiContext context = this.contextProvider.get();

        String currentWiki = context.getWikiId();

        try {
            context.setWikiId(wikiName);

            Collection existingCollection = this.collectionManager.getCollection(collectionName);
            if (existingCollection == null) {
                existingCollection = this.collectionManager.createCollection(collectionName);
            }

            // Assign the new collection to the existing one
            collection.applyTo(existingCollection);
            // TODO: how to save the collection, while respecting rights?

            return new JSONCollection(existingCollection);
        } catch (IndexException e) {
            this.logger.error("Error updating collection [{}]: [{}]", collectionName, e.getMessage());
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            context.setWikiId(currentWiki);
        }
    }

    @Override
    public void deleteCollection(String wikiName, String collectionName) throws XWikiRestException
    {
        XWikiContext context = this.contextProvider.get();

        String currentWiki = context.getWikiId();

        try {
            context.setWikiId(wikiName);

            // TODO: How to handle rights?
            if (!this.collectionManager.deleteCollection(collectionName)) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
        } finally {
            context.setWikiId(currentWiki);
        }
    }

    @Override
    public List<String> getDocumentsResource(String wikiName, String collectionName, Integer start, Integer number)
        throws XWikiRestException
    {
        Collection collection = getInternalCollection(wikiName, collectionName);
        // TODO: How to handle rights?
        return collection.getDocuments().stream()
            // TODO: Use real pagination and do not load all documents in memory
            .skip(start)
            .limit(number > -1 ? number : Integer.MAX_VALUE)
            .collect(Collectors.toList());
    }
}
