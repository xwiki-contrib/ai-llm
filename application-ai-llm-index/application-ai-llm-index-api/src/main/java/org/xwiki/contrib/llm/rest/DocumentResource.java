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

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import org.xwiki.rest.XWikiRestException;
import org.xwiki.stability.Unstable;

/**
 * REST resource for managing documents.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@Path("/wikis/{wikiName}/aiLLM/collections/{collectionName}/documents/{documentID}")
public interface DocumentResource
{
    /**
     * Gets a document by ID.
     *
     * @param wikiName the wiki in which the document is located
     * @param collectionName the collection in which the document is located
     * @param documentID the ID of the document
     * @return the document
     * @throws XWikiRestException when there is an error retrieving the document
     */
    @GET
    JSONDocument getDocument(
        @PathParam("wikiName") String wikiName,
        @PathParam("collectionName") String collectionName,
        @PathParam("documentID") String documentID
    ) throws XWikiRestException;

    /**
     * Deletes a document by ID.
     *
     * @param wikiName the wiki in which the document is located
     * @param collectionName the collection in which the document is located
     * @param documentID the ID of the document
     * @throws XWikiRestException when there is an error deleting the document
     */
    @DELETE
    void deleteDocument(
        @PathParam("wikiName") String wikiName,
        @PathParam("collectionName") String collectionName,
        @PathParam("documentID") String documentID
    ) throws XWikiRestException;

    /**
     * Creates or updates a document.
     *
     * @param wikiName the wiki in which to create or update the document
     * @param collectionName the collection in which to create or update the document
     * @param documentID the ID of the document
     * @param document the document to create or update
     * @return the created or updated document
     * @throws XWikiRestException when there is an error creating or updating the document
     */
    @PUT
    JSONDocument putDocument(
        @PathParam("wikiName") String wikiName,
        @PathParam("collectionName") String collectionName,
        @PathParam("documentID") String documentID,
        JSONDocument document
    ) throws XWikiRestException;
}
