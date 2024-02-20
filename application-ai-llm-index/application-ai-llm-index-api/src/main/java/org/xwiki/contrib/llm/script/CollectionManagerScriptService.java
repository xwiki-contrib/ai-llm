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
package org.xwiki.contrib.llm.script;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.script.service.ScriptService;

/**
 * Provides CollectionManager related Script APIs.
 * 
 * @version $Id$
 */
@Component
@Named("collectionManager")
@Singleton
public class CollectionManagerScriptService implements ScriptService
{
    @Inject
    private CollectionManager collectionManager;

    /**
     * Creates a new collection.
     *
     * @param name the name of the collection
     * @return the created collection
     */
    public Collection createCollection(String name) throws IndexException
    {
        return this.collectionManager.createCollection(name);
    }
    
    /**
     * Lists all collections.
     *
     * @return a list of all collections
     */
    public List<String> getCollections() throws IndexException
    {
        return this.collectionManager.getCollections();
    }

    /**
     * Gets a collection by name.
     *
     * @param name the name of the collection
     * @return the collection with the given name
     */
    public Collection getCollection(String name) throws IndexException
    {
        return this.collectionManager.getCollection(name);
    }

    /**
     * Deletes a collection.
     * @param name
     * @param deleteDocuments if true, deletes all documents in the collection
     */
    public void deleteCollection(String name, boolean deleteDocuments) throws IndexException
    {
        this.collectionManager.deleteCollection(name, deleteDocuments);
    }

    /**
    * @param collection the collection to check access to
    * @return {@code true} if the user has access to query a collection, {@code false} otherwise.
    */
    public boolean hasAccess(Collection collection)
    {
        return this.collectionManager.hasAccess(collection);
    }

    /**
     * Clears the solr core of all data.
     * 
     */
    public void clearIndexCore() throws IndexException
    {
        this.collectionManager.clearIndexCore();
    }

    /**
     * @param textQuery the text query
     * @param collections the collections to search in
     * @param limit the maximum number of results to return
     * @return a list of document ids that are similar to the text query
     */
    public List<List<String>> similaritySearch(String textQuery,
                                                List<String> collections,
                                                int limit) throws IndexException
    {
        return this.collectionManager.similaritySearch(textQuery, collections, limit);
    }

    /**
     * @param collections the collections to filter
     * @return a list of collections that the user has access to
     */
    public List<String> filterCollectionbasedOnUserAccess(List<String> collections)
    {
        return this.collectionManager.filterCollectionbasedOnUserAccess(collections);
    }
}
