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

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.rest.DocumentResource;
import org.xwiki.contrib.llm.rest.JSONDocument;
import org.xwiki.rest.XWikiRestException;

/**
 * Default implementation of {@link DocumentResource}.
 *
 * @version $Id$
 */
public class DefaultDocumentResource extends AbstractCollectionResource implements DocumentResource
{
    @Override
    public JSONDocument getDocument(String wikiName, String collectionName, String documentID) throws XWikiRestException
    {
        try {
            // TODO: How to handle rights?
            Document document = getInternalCollection(wikiName, collectionName).getDocument(documentID);
            if (document == null) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
            return new JSONDocument(document);
        } catch (IndexException e) {
            this.logger.error("Failed to get document [{}] from collection [{}] in wiki [{}].", documentID,
                collectionName, wikiName, e);
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void deleteDocument(String wikiName, String collectionName, String documentID) throws XWikiRestException
    {
        try {
            // TODO: how to handle rights?
            Collection collection = getInternalCollection(wikiName, collectionName);
            Document document = collection.getDocument(documentID);
            if (document == null) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
            collection.removeDocument(documentID, true, true);
        } catch (IndexException e) {
            this.logger.error("Failed to delete document [{}] from collection [{}] in wiki [{}].", documentID,
                collectionName, wikiName, e);
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }
        throw new WebApplicationException(Response.Status.SERVICE_UNAVAILABLE);
    }

    @Override
    public JSONDocument putDocument(String wikiName, String collectionName, String documentID, JSONDocument document)
        throws XWikiRestException
    {
        try {
            // TODO: how to handle rights?
            Collection collection = getInternalCollection(wikiName, collectionName);
            Document existingDocument = collection.getDocument(documentID);
            if (existingDocument == null) {
                existingDocument = collection.newDocument(documentID);
            }

            // Assign the new document to the existing one
            document.applyTo(existingDocument);
            existingDocument.save();
            return new JSONDocument(existingDocument);
        } catch (IndexException e) {
            this.logger.error("Failed to update document [{}] from collection [{}] in wiki [{}].", documentID,
                collectionName, wikiName, e);
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }
    }
}
