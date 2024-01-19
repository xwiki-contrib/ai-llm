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
 * Represents a collection of documents within the AI-LLM indexing system.
 *
 * @version $Id$
 */
@Role
public interface Collection
{

    
    /**
     * The name of the XClass that represents a collection.
     */
    String XCLASS_NAME = "CollectionsClass";
    
    /**
     * The space of the XClass that represents a collection.
     */
    String XCLASS_SPACE_STRING = "AI.Collections.Code";
    
    /**
     * The fullName of the XClass that represents a collection.
     */
    String XCLASS_FULLNAME = XCLASS_SPACE_STRING + "." + XCLASS_NAME;

    /**
     * The default space for collections.
     */
    String DEFAULT_COLLECTION_SPACE = "AI.Collections";

    /**
     * The default suffix for collection fullNames.
     */
    String DEFAULT_COLLECTION_SUFFIX = ".WebHome";

    /**
     * Gets the name of the collection.
     * 
     * @return The name of the collection.
     */
    String getName();

    /**
     * Gets the name of the collection.
     * 
     * @return The name of the collection.
     */
    String getFullName();
    
    /**
     * Gets the embedding model used by the collection.
     * 
     * @return A string representing the embedding model.
     */
    String getEmbeddingModel();
    
    /**
     * Gets the chunking method used by the collection.
     * 
     * @return A string representing the chunking method.
     */
    String getChunkingMethod();
    
    /**
     * Gets the maximum size of a chunk.
     * 
     * @return The maximum size of a chunk.
     */
    int getChunkingMaxSize();
    
    /**
     * Gets the overlap offset of a chunk.
     * 
     * @return The overlap offset of a chunk.
     */
    int getChunkingOverlapOffset();
    
    /**
     * Gets the list of spaces for the documents in the collection.
     * 
     * @return A list of spaces.
     */
    List<String> getDocumentSpaces();
    
    /**
     * Gets the list of groups that can query the collection.
     * 
     * @return A list of groups.
     */
    String getQueryGroups();
    
    /**
     * Gets the list of groups that can edit the collection.
     * 
     * @return A list of groups.
     */
    String getEditGroups();
    
    /**
     * Gets the list of groups that can administer the collection.
     * 
     * @return A list of groups.
     */
    String getAdminGroups();
    
    /**
     * Gets the rights check method associated with the collection.
     * 
     * @return A string representing the rights check method of the collection.
     */
    String getRightsCheckMethod();
    
    /**
     * Gets the rights check method parameter associated with the collection.
     * 
     * @return A string representing the rights check method parameter of the collection.
     */
    String getRightsCheckMethodParam();
    
    /**
     * Sets the name of the collection.
     * 
     * @param name The name of the collection.
     */
    void setName(String name);
    
    /**
     * Sets the embedding model of the collection.
     * 
     * @param embeddingModel The embedding model of the collection.
     */
    void setEmbeddingModel(String embeddingModel);
    
    /**
     * Sets the chunking method of the collection.
     * 
     * @param chunkingMethod The chunking method of the collection.
     */
    void setChunkingMethod(String chunkingMethod);
    
    /**
     * Sets the maximum size of a chunk.
     * 
     * @param chunkingMaxSize The maximum size of a chunk.
     */
    void setChunkingMaxSize(int chunkingMaxSize);
    
    /**
     * Sets the overlap offset of a chunk.
     * 
     * @param chunkingOverlapOffset The overlap offset of a chunk.
     */
    void setChunkingOverlapOffset(int chunkingOverlapOffset);
    
    /**
     * Sets the list of spaces for the documents in the collection.
     * 
     * @param documentSpaces A list of spaces.
     */
    void setDocumentSpaces(List<String> documentSpaces);
    
    /**
     * Sets the list of groups that can query the collection.
     * 
     * @param queryGroups A list of groups.
     */
    void setQueryGroups(String queryGroups);
    
    /**
     * Sets the list of groups that can edit the collection.
     * 
     * @param editGroups A list of groups.
     */
    void setEditGroups(String editGroups);
    
    /**
     * Sets the list of groups that can administer the collection.
     * 
     * @param adminGroups A list of groups.
     */
    void setAdminGroups(String adminGroups);
    
    /**
     * Sets the rights check method associated with the collection.
     * 
     * @param rightsCheckMethod A string representing the rights check method of the collection.
     */
    void setRightsCheckMethod(String rightsCheckMethod);
    
    /**
     * Sets the rights check method parameter associated with the collection.
     * 
     * @param rightsCheckMethodParam A string representing the rights check method parameter of the collection.
     */
    void setRightsCheckMethodParam(String rightsCheckMethodParam);
    
    /**
     * Retrieves a list of all documents in the collection.
     * 
     * @return A list of documents.
     */
    List<String> getDocuments();
    
    /**
     * Retrieves a specific document by its ID from the collection.
     * 
     * @param documentId The unique identifier of the document.
     * @return The document with the specified ID, or null if not found.
     */
    Document newDocument(String documentId) throws IndexException;
    
    /**
     * Retrieves a specific document by its ID from the collection.
     * 
     * @param documentId The unique identifier of the document.
     * @return The document with the specified ID, or null if not found.
     */
    Document getDocument(String documentId) throws IndexException;
    
    /**
     * Removes a document from the collection.
     * 
     * @param documentId The document to remove.
     * @param removeFromVectorDB Whether to remove the document from the vector database.
     * @param removeFromStorage Whether to remove the document from the storage.
     */
    void removeDocument(String documentId,
                         boolean removeFromVectorDB, 
                         boolean removeFromStorage);
    
    /**
     * Saves the collection.
     *
     */
    void save();
}
