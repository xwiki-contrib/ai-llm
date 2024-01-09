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
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.internal.DefaultCollection;
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
     * Lists all collections.
     *
     * @return a list of all collections
     */
    public List<DefaultCollection> listCollections()
    {
        return collectionManager.listCollections();
    }

    /**
     * Pulls all collections from XWiki.
     *
     * @return boolean indicating success or failure
     */
    public boolean pullCollections()
    {
        return collectionManager.pullCollections();
    }

    /**
     * Creates a new collection.
     *
     * @param name the name of the collection
     * @return the created collection
     */
    public DefaultCollection createCollection(String name)
    {
        return collectionManager.createCollection(name);
    }

    /**
     * Gets a collection by name.
     *
     * @param name the name of the collection
     * @return the collection with the given name
     */
    public DefaultCollection getCollection(String name)
    {
        return collectionManager.getCollection(name);
    }

    /**
     * Deletes a collection.
     * @param name
     * @return boolean indicating success or failure
     */
    public boolean deleteCollection(String name)
    {
        return collectionManager.deleteCollection(name);
    }
}
