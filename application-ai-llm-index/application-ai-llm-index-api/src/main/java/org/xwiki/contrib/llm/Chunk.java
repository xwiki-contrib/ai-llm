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

import javax.inject.Inject;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.user.UserReference;

/**
 * The chunk will be used to store the information in Solr.
 * 
 * @version $Id$
 */
@Component(roles = Chunk.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class Chunk
{
    private String documentID;
    private String documentURL;
    private String language;
    private String collection;
    private int chunkIndex;
    private int posFirstChar;
    private int posLastChar;
    private String content;
    private double[] embeddings;

    @Inject
    private Logger logger;

    @Inject
    private EmbeddingsUtils embeddingsUtils;

    /**
     * Initialize the chunk.
     *  
     * @param documentID the document ID
     * @param collection the collection
     * @param documentURL the document URL
     * @param language the language
     * @param posFirstChar the position of the first character
     * @param posLastChar the position of the last character
     * @param content the content
     */
    public void initialize(
        String documentID,
        String collection,
        String documentURL,
        String language,
        int posFirstChar,
        int posLastChar,
        String content)
    {
        this.documentID = documentID;
        this.collection = collection;
        this.documentURL = documentURL;
        this.language = language;
        this.posFirstChar = posFirstChar;
        this.posLastChar = posLastChar;
        this.content = content;
        this.embeddings = null;
    }

    /**
     * Getter for the document ID.
     *
     * @return the document ID
     */
    public String getDocumentID()
    {
        return documentID;
    }

    /**
     * Getter for the collection.
     *
     * @return the collection
     */
    public String getCollection()
    {
        return collection;
    }

    /**
     * Getter for the document URL.
     *
     * @return the document URL
     */
    public String getDocumentURL()
    {
        return documentURL;
    }

    /**
     * Getter for the language.
     *
     * @return the language
     */
    public String getLanguage()
    {
        return language;
    }

    /**
     * Getter for the chunk index.
     *
     * @return the chunk index
     */
    public Integer getChunkIndex()
    {
        return chunkIndex;
    }

    /**
     * Getter for the position of the first character.
     *
     * @return the position of the first character
     */
    public int getPosFirstChar()
    {
        return posFirstChar;
    }

    /**
     * Getter for the position of the last character.
     *
     * @return the position of the last character
     */
    public int getPosLastChar()
    {
        return posLastChar;
    }

    /**
     * Getter for the content.
     *
     * @return the content
     */
    public String getContent()
    {
        return content;
    }

    /**
     * Getter for the embeddings.
     *
     * @return the embeddings as double array
     */
    public double[] getEmbeddings()
    {
        return embeddings;
    }

    /**
     * Setter for the document ID.
     *
     * @param documentID the document ID
     */
    public void setDocumentID(String documentID)
    {
        this.documentID = documentID;
    }

    /**
     * Setter for the collection.
     *
     * @param collection the collection
     */
    public void setCollection(String collection)
    {
        this.collection = collection;
    }

    /**
     * Setter for the document URL.
     *
     * @param documentURL the document URL
     */
    public void setDocumentURL(String documentURL)
    {
        this.documentURL = documentURL;
    }

    /**
     * Setter for the language.
     *
     * @param language the language
     */
    public void setLanguage(String language)
    {
        this.language = language;
    }

    /**
     * Setter for the chunk index.
     *
     * @param chunkIndex the order of the chunk in the document
     */
    public void setChunkIndex(Integer chunkIndex)
    {
        this.chunkIndex = chunkIndex;
    }

    /**
     * Setter for the position of the first character.
     *
     * @param posFirstChar the position of the first character
     */
    public void setPosFirstChar(int posFirstChar)
    {
        this.posFirstChar = posFirstChar;
    }

    /**
     * Setter for the position of the last character.
     *
     * @param posLastChar the position of the last character
     */
    public void setPosLastChar(int posLastChar)
    {
        this.posLastChar = posLastChar;
    }

    /**
     * Setter for the content.
     *
     * @param content the content
     */
    public void setContent(String content)
    {
        this.content = content;
    }

    /**
     * Compute embeddings for current chunk.
     *
     * @param embeddingModelID the embedding model ID
     * @param userReference the user reference
     */
    public void computeEmbeddings(String embeddingModelID, UserReference userReference) throws IndexException
    {
        this.embeddings = this.embeddingsUtils.computeEmbeddings(this.content, embeddingModelID, userReference);
    }

}
