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

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

import org.xwiki.component.annotation.Role;
/**
 * Represents a collection of documents within the AI-LLM indexing system.
 *
 * @version $Id$
 */
@Role
public interface Collection
{
    /**
     * Gets the name of the collection.
     * 
     * @return The name of the collection.
     */
    String getName();

    /**
     * Retrieves a list of all documents in the collection.
     * 
     * @return A list of documents.
     */
    List<Document> getDocumentList();

    /**
     * Retrieves a specific document by its ID from the collection.
     * 
     * @param id The unique identifier of the document.
     * @return The document with the specified ID, or null if not found.
     */
    Document getDocument(String id);

    /**
     * Gets the permissions associated with the collection.
     * 
     * @return A string representing the permissions of the collection.
     */
    String getPermissions();

    /**
     * Gets the embedding model used by the collection.
     * 
     * @return A string representing the embedding model.
     */
    String getEmbeddingModel();

    /**
     * Removes a document from the collection. Optionally, it can also delete the document.
     * 
     * @param id The unique identifier of the document to be removed.
     * @param deleteDocument If true, the document is also deleted; otherwise, it is only removed from the collection.
     * @return True if the operation was successful, false otherwise.
     */
    boolean removeDocument(String id, boolean deleteDocument);

    /**
     * Assigns a unique ID to a document within the collection.
     * 
     * @param document The document to which the ID will be assigned.
     * @param id The unique identifier to be assigned to the document.
     */
    void assignIdToDocument(Document document, String id);

    /**
     * Creates a new document in the collection with a unique ID. The properties of the document can be set afterwards.
     * 
     * @return The newly created document.
     * @throws XWikiException
     */
    Document createDocument() throws XWikiException;

    /**
     * Creates a new document in the collection with a unique ID. The properties of the document can be set afterwards.
     * 
     * @param id The unique identifier to be assigned to the document.
     * @return The newly created document.
     * @throws XWikiException
     */
    Document createDocument(String id) throws XWikiException;

    /**
     * Sets the name of the collection.
     * 
     * @param name The name of the collection.
     * @return True if the operation was successful, false otherwise.
     */
    boolean setName(String name);

    /**
     * Sets the permissions of the collection.
     * 
     * @param permissions The permissions of the collection.
     * @return True if the operation was successful, false otherwise.
     */
    boolean setPermissions(String permissions);

    /**
     * Sets the embedding model of the collection.
     * 
     * @param embeddingModel The embedding model of the collection.
     * @return True if the operation was successful, false otherwise.
     */
    boolean setEmbeddingModel(String embeddingModel);

    /**
     * Sets the properteis of a collection based on the properties of the specified XWiki document's object.
     * 
     * @param xwikiDocument
     * @return The updated collection.
     */
    Collection fromXWikiDocument(XWikiDocument xwikiDocument);

    /**
     * Sets the properties of a XWiki document's object based on the properties of the collection.
     * 
     * @param xwikiDocument The XWiki document to update.
     * @return The updated XWiki document.
     */
    XWikiDocument toXWikiDocument(XWikiDocument xwikiDocument);
}
