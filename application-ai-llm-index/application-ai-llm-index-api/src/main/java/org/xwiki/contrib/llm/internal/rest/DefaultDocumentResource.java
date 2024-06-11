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

import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.rest.DocumentResource;
import org.xwiki.contrib.llm.rest.JSONDocument;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.authorization.AccessDeniedException;

/**
 * Default implementation of {@link DocumentResource}.
 *
 * @version $Id$
 */
@Component
@Named("org.xwiki.contrib.llm.internal.rest.DefaultDocumentResource")
@Singleton
public class DefaultDocumentResource extends AbstractCollectionResource implements DocumentResource
{
    @Override
    public JSONDocument getDocument(String wikiName, String collectionName, String documentID) throws XWikiRestException
    {
        try {
            Collection collection = getInternalCollection(wikiName, collectionName);
            Document document = collection.getDocumentStore().getDocument(documentID);
            if (document == null) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
            return new JSONDocument(document);
        } catch (IndexException | AccessDeniedException e) {
            throw convertDocumentException(collectionName, documentID, "retrieving", e);
        }
    }

    @Override
    public void deleteDocument(String wikiName, String collectionName, String documentID) throws XWikiRestException
    {
        try {
            Collection collection = getInternalCollection(wikiName, collectionName);
            DocumentStore documentStore = collection.getDocumentStore();
            Document document = documentStore.getDocument(documentID);
            if (document == null) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
            documentStore.deleteDocument(document);
        } catch (IndexException | AccessDeniedException e) {
            throw convertDocumentException(collectionName, documentID, "deleting", e);
        }
    }

    @Override
    public JSONDocument putDocument(String wikiName, String collectionName, String documentID, JSONDocument document)
        throws XWikiRestException
    {
        try {
            Collection collection = getInternalCollection(wikiName, collectionName);
            Document existingDocument = collection.getDocumentStore().getDocument(documentID);
            if (existingDocument == null) {
                existingDocument = collection.getDocumentStore().createDocument(documentID);
            }

            // Assign the new document to the existing one
            document.applyTo(existingDocument);
            collection.getDocumentStore().saveDocument(existingDocument);
            return new JSONDocument(existingDocument);
        } catch (IndexException | AccessDeniedException e) {
            throw convertDocumentException(collectionName, documentID, "updating", e);
        }
    }
}
