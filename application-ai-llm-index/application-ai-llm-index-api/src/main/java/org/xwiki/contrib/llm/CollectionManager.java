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
package org.xwiki.contrib.llm;

import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.model.reference.DocumentReference;

/**
 * This interface manages collections.
 * 
 * @version $Id$
 */
@Role
public interface CollectionManager
{
    /**
     * Creates a new collection.
     *
     * @param id the id of the collection
     * @return the created collection
     */
    Collection createCollection(String id) throws IndexException;
    
    /**
     * Lists all collections.
     *
     * @return a list of all collections
     */
    List<String> getCollections() throws IndexException;
    
    /**
     * Gets a collection by name.
     *
     * @param id the id of the collection
     * @return the collection with the given name
     */
    Collection getCollection(String id) throws IndexException;
    
    /**
     * Deletes a collection.
     * @param id the id of the collection
     * @param deleteDocuments if true, deletes all documents in the collection
     */
    void deleteCollection(String id, boolean deleteDocuments) throws IndexException;

    /**
     * @param id the id of the collection
     * @return the document reference of the collection
     */
    DocumentReference getDocumentReference(String id);

    /**
    * @param collection the collection to check access to
    * @return {@code true} if the user has access to query a collection, {@code false} otherwise.
    */
    boolean hasAccess(Collection collection);

    /**
     * Clears the solr core of all data.
     * 
     */
    void clearIndexCore() throws IndexException;

    /**
     * @param textQuery the text query
     * @param collections the collections to search in
     * @return a list of document ids that are similar to the text query
     */
    List<List<String>> similaritySearch(String textQuery, List<String> collections) throws IndexException;
}
