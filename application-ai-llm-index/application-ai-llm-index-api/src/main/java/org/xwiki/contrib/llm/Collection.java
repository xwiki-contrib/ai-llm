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
import org.xwiki.model.reference.LocalDocumentReference;

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
     * The name of the application space.
     */
    String APP_SPACE_NAME = "AI";

    /**
     * The name of the collections space.
     */
    String COLLECTIONS_SPACE_NAME = "Collections";

    /**
     * The name of the code space.
     */
    String CODE_SPACE_NAME = "Code";

    /**
     * The space of the XClass that represents a collection.
     */
    List<String> CODE_SPACE_NAMES = List.of(APP_SPACE_NAME, COLLECTIONS_SPACE_NAME, CODE_SPACE_NAME);

    /**
     * The delimiter used to separate spaces in a reference.
     */
    String SPACE_DELIMITER = ".";
    /**
     * The space of the XClass that represents a collection.
     */
    String XCLASS_SPACE_STRING =  String.join(SPACE_DELIMITER, CODE_SPACE_NAMES);

    /**
     * The reference of the XClass that represents a collection.
     */
    LocalDocumentReference XCLASS_REFERENCE = new LocalDocumentReference(CODE_SPACE_NAMES, XCLASS_NAME);

    /**
     * The fullName of the XClass that represents a collection.
     */
    String XCLASS_FULLNAME = XCLASS_SPACE_STRING + SPACE_DELIMITER + XCLASS_NAME;

    /**
     * The default space for collections.
     */
    List<String> DEFAULT_COLLECTION_SPACE = List.of(APP_SPACE_NAME, COLLECTIONS_SPACE_NAME);

    /**
     * The default suffix for collection fullNames.
     */
    String DEFAULT_COLLECTION_SUFFIX = ".WebHome";

    /**
     * Gets the name of the collection.
     * 
     * @return The name of the collection.
     */
    String getID();

    /**
     * Gets the title of the collection.
     * 
     * @return The title of the collection.
     */
    String getTitle();

    /**
     * Gets the embedding model used by the collection.
     * 
     * @return A string representing the embedding model id.
     */
    String getEmbeddingModel();
    
    /**
     * Gets the chunking method used by the collection.
     * 
     * @return A string representing the chunking method.
     */
    String getChunkingMethod();

    /**
     * Gets llm model usef for formatting the chunks.
     * 
     * @return A string representing the llm model id used for formatting.
     */
    String getChunkingLLMModel();
    
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
     * Boolean value if guests are allowed to access the collection.
     * 
     * @return true if guests are allowed to access the collection, false otherwise.
     */
    boolean getAllowGuests();

    /**
     * Gets the list of groups that can query the collection.
     * 
     * @return A list of groups.
     */
    String getQueryGroups();
    
    /**
     * Gets the rights check method associated with the collection.
     * 
     * @return A string representing the rights check method of the collection.
     */
    String getRightsCheckMethod();

    /**
     * Sets the id of the collection.
     * 
     * @param id The id of the collection.
     */
    void setID(String id) throws IndexException;

    /**
     * Sets the title of the collection.
     * 
     * @param title The title of the collection.
     */
    void setTitle(String title) throws IndexException;

    /**
     * Sets the embedding model of the collection.
     * 
     * @param embeddingModel The embedding model of the collection.
     */
    void setEmbeddingModel(String embeddingModel) throws IndexException;
    
    /**
     * Sets the chunking method of the collection.
     * 
     * @param chunkingMethod The chunking method of the collection.
     */
    void setChunkingMethod(String chunkingMethod) throws IndexException;
    
    /**
     * Sets the llm model used for formatting the chunks.
     * 
     * @param chunkingLLMModel The llm model used for formatting the chunks.
     */
    void setChunkingLLMModel(String chunkingLLMModel) throws IndexException;

    /**
     * Sets the maximum size of a chunk.
     * 
     * @param chunkingMaxSize The maximum size of a chunk.
     */
    void setChunkingMaxSize(int chunkingMaxSize) throws IndexException;
    
    /**
     * Sets the overlap offset of a chunk.
     * 
     * @param chunkingOverlapOffset The overlap offset of a chunk.
     */
    void setChunkingOverlapOffset(int chunkingOverlapOffset) throws IndexException;
    
    /**
     * Sets the list of spaces for the documents in the collection.
     * 
     * @param documentSpaces A list of spaces.
     */
    void setDocumentSpaces(List<String> documentSpaces) throws IndexException;

    /**
     * Sets whether guests are allowed to access the collection.
     * 
     * @param allowGuests A boolean value indicating whether guests are allowed to access the collection.
     */
    void setAllowGuests(boolean allowGuests) throws IndexException;

    /**
     * Sets the list of groups that can query the collection.
     * 
     * @param queryGroups A list of groups.
     */
    void setQueryGroups(String queryGroups) throws IndexException;

    /**
     * Sets the rights check method associated with the collection.
     * 
     * @param rightsCheckMethod A string representing the rights check method of the collection.
     */
    void setRightsCheckMethod(String rightsCheckMethod) throws IndexException;

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
                         boolean removeFromStorage) throws IndexException;
    
    /**
     * Saves the collection.
     *
     */
    void save() throws IndexException;

    /**
     * @return the authorization manager for this collection that needs to be used to check rights on any retrieved
     * document
     * @throws IndexException if the authorization manager cannot be built
     */
    AuthorizationManager getAuthorizationManager() throws IndexException;

    /**
     * @return the authorization configuration for this collection, the type of the object returned is determined by the
     * selected rights check method. Can be {@code null} if the authorization method doesn't require any specific
     * configuration.
     */
    Object getAuthorizationConfiguration() throws IndexException;

    /**
     * @return the type of the authorization configuration object returned by {@link #getAuthorizationConfiguration()}
     */
    Class<?> getAuthorizationConfigurationType() throws IndexException;

    /**
     * @param authorizationConfiguration the authorization configuration for this collection
     */
    void setAuthorizationConfiguration(Object authorizationConfiguration) throws IndexException;
}
