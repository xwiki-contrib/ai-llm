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
import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.CollectionIndexingTaskConsumer;
import org.xwiki.contrib.llm.rest.CollectionResource;
import org.xwiki.contrib.llm.rest.JSONCollection;
import org.xwiki.contrib.llm.rest.ReindexOptions;
import org.xwiki.index.TaskManager;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    @Inject
    private ContextualAuthorizationManager contextualAuthorizationManager;

    @Inject
    private TaskManager taskManager;

    @Inject
    private SolrConnector solrConnector;

    @Override
    public JSONCollection getCollection(String wikiName, String collectionName)
        throws XWikiRestException
    {
        try {
            return new JSONCollection(getInternalCollection(wikiName, collectionName), new ObjectMapper());
        } catch (IndexException e) {
            throw convertException(collectionName, e, "retrieving");
        }
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
            ObjectMapper objectMapper = new ObjectMapper();
            collection.applyTo(existingCollection, objectMapper);

            return new JSONCollection(existingCollection, objectMapper);
        } catch (IndexException e) {
            throw convertException(collectionName, e, "updating");
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

            this.collectionManager.deleteCollection(collectionName, true);
        } catch (IndexException e) {
            throw convertException(collectionName, e, "deleting");
        } finally {
            context.setWikiId(currentWiki);
        }
    }

    @Override
    public List<String> getDocuments(String wikiName, String collectionName, Integer start, Integer number)
        throws XWikiRestException
    {
        Collection collection = getInternalCollection(wikiName, collectionName);
        try {
            return collection.getDocumentStore().getDocumentNames(start, number);
        } catch (IndexException e) {
            throw new XWikiRestException("Failed to list documents", e);
        }
    }

    @Override
    public Response reindexCollection(String wikiName, String collectionName, ReindexOptions options)
    {
        Collection collection = getInternalCollection(wikiName, collectionName);

        // Check for wiki admin right on the wiki of the collection.
        try {
            this.contextualAuthorizationManager.checkAccess(
                Right.ADMIN, collection.getDocumentReference().getWikiReference());
        } catch (AccessDeniedException e) {
            throw new WebApplicationException("Re-indexing collections is only allowed for wiki admins.",
                Response.Status.UNAUTHORIZED);
        }

        if (options.clean()) {
            this.solrConnector.deleteChunksByCollection(wikiName, collectionName);
        }

        this.taskManager.addTask(wikiName, collection.getDocumentId(), CollectionIndexingTaskConsumer.NAME);

        return Response.ok().build();
    }
}
