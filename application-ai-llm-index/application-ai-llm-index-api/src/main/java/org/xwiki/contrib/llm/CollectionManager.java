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
     * @param fullName the name of the collection
     * @return the created collection
     */
    Collection createCollection(String fullName);
    
    /**
     * Lists all collections.
     *
     * @return a list of all collections
     */
    List<String> getCollections();
    
    /**
     * Gets a collection by name.
     *
     * @param name the name of the collection
     * @return the collection with the given name
     */
    Collection getCollection(String name) throws IndexException;
    
    /**
     * Deletes a collection.
     * @param name
     * @return boolean indicating success or failure
     */
    boolean deleteCollection(String name);

    /**
     * Clears the solr core of all data.
     * 
     * @return boolean indicating success or failure
     */
    boolean clearIndexCore();

}
