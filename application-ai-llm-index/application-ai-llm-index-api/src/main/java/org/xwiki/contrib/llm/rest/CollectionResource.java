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
package org.xwiki.contrib.llm.rest;

import java.util.List;

import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.xwiki.rest.XWikiRestException;
import org.xwiki.stability.Unstable;

/**
 * REST resource for managing collections.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@Path("/wikis/{wikiName}/aiLLM/collections/{collectionName}")
public interface CollectionResource
{
    /**
     * Gets a collection by name.
     *
     * @param wikiName the wiki in which the collection is located
     * @param collectionName the name of the collection
     * @return the collection
     * @throws XWikiRestException when there is an error retrieving the collection
     */
    @GET
    JSONCollection getCollection(
        @PathParam("wikiName") String wikiName,
        @PathParam("collectionName") String collectionName
    ) throws XWikiRestException;

    /**
     * Creates or updates a collection.
     *
     * @param wikiName the wiki in which to create or update the collection
     * @param collectionName the name of the collection
     * @param collection the collection to create or update
     * @return the created or updated collection
     * @throws XWikiRestException when there is an error creating or updating the collection
     */
    @PUT
    JSONCollection putCollection(
        @PathParam("wikiName") String wikiName,
        @PathParam("collectionName") String collectionName,
        JSONCollection collection
    ) throws XWikiRestException;

    /**
     * Deletes a collection.
     *
     * @param wikiName the wiki in which the collection is located
     * @param collectionName the name of the collection
     * @throws XWikiRestException when there is an error deleting the collection
     */
    @DELETE
    void deleteCollection(
        @PathParam("wikiName") String wikiName,
        @PathParam("collectionName") String collectionName
    ) throws XWikiRestException;

    /**
     * Gets the documents in a collection.
     *
     * @param wikiName the wiki in which the collection is located
     * @param collectionName the name of the collection
     * @param start the index of the first document to retrieve
     * @param number the number of documents to retrieve, or -1 to retrieve all documents
     * @return the documents in the collection
     * @throws XWikiRestException when there is an error retrieving the documents
     */
    @Path("/documents")
    @GET
    List<String> getDocumentsResource(
        @PathParam("wikiName") String wikiName,
        @PathParam("collectionName") String collectionName,
        @QueryParam("start") @DefaultValue("0") Integer start,
        @QueryParam("number") @DefaultValue("-1") Integer number
    ) throws XWikiRestException;
}
