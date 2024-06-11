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
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.stability.Unstable;
import org.xwiki.user.UserReference;

/**
 * A store of documents that can be indexed by this extension.
 *
 * @version $Id$
 * @since 0.4
 */
@Role
@Unstable
public interface DocumentStore
{
    /**
     * Initialize the document store to retrieve documents for the given collection.
     *
     * @param collection the collection to initialize the document store for
     * @param userReference the user for which rights should be checked
     * @throws IndexException if initializing the document store failed
     */
    void initialize(Collection collection, UserReference userReference) throws IndexException;

    /**
     * List documents.
     *
     * @param offset the first document to list
     * @param limit the number of documents list, -1 to list all
     * @return the list of documents
     * @throws IndexException if loading the list of documents failed or the documents aren't managed by this
     *     document store
     */
    List<String> getDocumentNames(int offset, int limit) throws IndexException;

    /**
     * Get a document.
     *
     * @param name the name of the document to get
     * @return the document
     * @throws IndexException if loading the document failed or the document isn't managed by this document store
     */
    Document getDocument(String name) throws IndexException, AccessDeniedException;

    /**
     * Create a document.
     *
     * @param name the name of the document to create
     * @return the created document
     * @throws IndexException if creating the document failed
     */
    Document createDocument(String name) throws IndexException, AccessDeniedException;

    /**
     * Save a document.
     *
     * @param document the document to save
     * @throws IndexException if saving the document failed or the document isn't managed by this document store
     */
    void saveDocument(Document document) throws IndexException, AccessDeniedException;

    /**
     * Delete a document.
     *
     * @param document the document to delete
     * @throws IndexException if deleting the document failed
     */
    void deleteDocument(Document document) throws IndexException, AccessDeniedException;
}
