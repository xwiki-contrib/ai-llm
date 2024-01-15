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

import javax.inject.Inject;
import javax.inject.Provider;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.CurrentUserReference;

import com.xpn.xwiki.XWikiContext;
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
    private String language;
    private int chunkIndex;
    private int posFirstChar;
    private int posLastChar;
    private String content;
    private double[] embeddings;

    @Inject 
    private EmbeddingModelManager embeddingModelManager;

    @Inject
    private Logger logger;

    @Inject 
    private Provider<XWikiContext> contextProvider;
    
    /**
     * Initialize the chunk.
     *  
     * @param documentID the document ID
     * @param language the language
     * @param chunkIndex the chunk index
     * @param posFirstChar the position of the first character
     * @param posLastChar the position of the last character
     * @param content the content
     */
    public void initialize(String documentID, String language, int chunkIndex, int posFirstChar, int posLastChar,
        String content)
    {
        this.documentID = documentID;
        this.language = language;
        this.chunkIndex = chunkIndex;
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
     */
    public void computeEmbeddings()
    {
        try {
            XWikiContext context = this.contextProvider.get();
            WikiReference wikiReference = context.getWikiReference();
            UserReference userReference = CurrentUserReference.INSTANCE;
            List<EmbeddingModelDescriptor> embeddingModelDescriptors = embeddingModelManager
                    .getModelDescriptors(wikiReference, userReference);
            EmbeddingModel embeddingModel = embeddingModelManager
                    .getModel(wikiReference, embeddingModelDescriptors.get(0).getId(), userReference);
            this.embeddings = embeddingModel.embed(this.content);
        } catch (Exception e) {
            logger.error("Failure to compute embeddings.", e);
            this.embeddings = null;
        }
    }
}
