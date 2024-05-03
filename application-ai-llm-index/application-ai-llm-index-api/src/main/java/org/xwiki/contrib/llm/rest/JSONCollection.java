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

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.stability.Unstable;
import org.xwiki.text.XWikiToStringBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Collection representation for the REST API.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class JSONCollection
{
    private String id;

    private String title;
    private String embeddingModel;
    private String chunkingMethod;
    private int chunkingMaxSize;
    private int chunkingOverlapOffset;
    private List<String> documentSpaces;
    private boolean allowGuests;
    private String queryGroups;
    private String rightsCheckMethod;

    private JsonNode rightsCheckMethodConfiguration;

    /**
     * Default constructor.
     */
    public JSONCollection()
    {
    }

    /**
     * Construct a collection from a {@link Collection}.
     *
     * @param collection the collection to construct from
     * @param objectMapper the object mapper to use to serialize the authorization configuration
     */
    public JSONCollection(Collection collection, ObjectMapper objectMapper) throws IndexException
    {
        this.id = collection.getID();
        this.title = collection.getTitle();
        this.embeddingModel = collection.getEmbeddingModel();
        this.chunkingMethod = collection.getChunkingMethod();
        this.chunkingMaxSize = collection.getChunkingMaxSize();
        this.chunkingOverlapOffset = collection.getChunkingOverlapOffset();
        this.documentSpaces = collection.getDocumentSpaces();
        this.allowGuests = collection.getAllowGuests();
        this.queryGroups = collection.getQueryGroups();
        this.rightsCheckMethod = collection.getRightsCheckMethod();

        Object authorizationConfiguration = collection.getAuthorizationConfiguration();
        if (authorizationConfiguration != null) {
            this.rightsCheckMethodConfiguration = objectMapper.valueToTree(authorizationConfiguration);
        }
    }

    /**
     * Applies the non-null properties of this collection to a {@link Collection}.
     *
     * @param collection the collection to apply to
     * @param objectMapper the object mapper to use to deserialize the authorization configuration
     */
    public void applyTo(Collection collection, ObjectMapper objectMapper) throws IndexException
    {
        applyTitle(collection);
        applyEmbeddingModel(collection);
        applyChunkingMethod(collection);
        applyChunkingMaxSize(collection);
        applyChunkingOverlapOffset(collection);
        applyDocumentSpaces(collection);
        applyAllowGuests(collection);
        applyQueryGroups(collection);
        applyRightsCheckMethod(collection);
        if (StringUtils.isNotBlank(collection.getRightsCheckMethod())) {
            try {
                collection.setAuthorizationConfiguration(objectMapper.treeToValue(this.rightsCheckMethodConfiguration,
                    collection.getAuthorizationConfigurationType()));
            } catch (JsonProcessingException e) {
                throw new IndexException("Error deserializing authorization configuration.", e);
            }
        }
        collection.save();
    }

    private void applyTitle(Collection collection) throws IndexException
    {
        if (this.title != null) {
            collection.setTitle(this.title);
        }
    }

    private void applyEmbeddingModel(Collection collection) throws IndexException
    {
        if (this.embeddingModel != null) {
            collection.setEmbeddingModel(this.embeddingModel);
        }
    }
    
    private void applyChunkingMethod(Collection collection) throws IndexException
    {
        if (this.chunkingMethod != null) {
            collection.setChunkingMethod(this.chunkingMethod);
        }
    }

    private void applyChunkingMaxSize(Collection collection) throws IndexException
    {
        if (this.chunkingMaxSize != 0) {
            collection.setChunkingMaxSize(this.chunkingMaxSize);
        }
    }

    private void applyChunkingOverlapOffset(Collection collection) throws IndexException
    {
        if (this.chunkingOverlapOffset != 0) {
            collection.setChunkingOverlapOffset(this.chunkingOverlapOffset);
        }
    }

    private void applyDocumentSpaces(Collection collection) throws IndexException
    {
        if (this.documentSpaces != null) {
            collection.setDocumentSpaces(this.documentSpaces);
        }
    }

    private void applyAllowGuests(Collection collection) throws IndexException
    {
        collection.setAllowGuests(this.allowGuests);
    }

    private void applyQueryGroups(Collection collection) throws IndexException
    {
        if (this.queryGroups != null) {
            collection.setQueryGroups(this.queryGroups);
        }
    }

    private void applyRightsCheckMethod(Collection collection) throws IndexException
    {
        if (this.rightsCheckMethod != null) {
            collection.setRightsCheckMethod(this.rightsCheckMethod);
        }
    }

    /**
     * @return the rights check method configuration as a JSON node
     */
    public JsonNode getRightsCheckMethodConfiguration()
    {
        return this.rightsCheckMethodConfiguration;
    }

    /**
     * @param rightsCheckMethodConfiguration the rights check method configuration as a JSON node
     */
    public void setRightsCheckMethodConfiguration(JsonNode rightsCheckMethodConfiguration)
    {
        this.rightsCheckMethodConfiguration = rightsCheckMethodConfiguration;
    }

    /**
     * @return the name of the collection
     */
    public String getID()
    {
        return this.id;
    }

    /**
     * @return the title of the collection
     */
    public String getTitle()
    {
        return this.id;
    }

    /**
     * @return the embedding model that should be used to embed chunks of the documents in this collection
     */
    public String getEmbeddingModel()
    {
        return this.embeddingModel;
    }

    /**
     * @return the chunking method that should be used to chunk the documents in this collection
     */
    public String getChunkingMethod()
    {
        return this.chunkingMethod;
    }

    /**
     * @return the maximum size of a chunk
     */
    public int getChunkingMaxSize()
    {
        return this.chunkingMaxSize;
    }

    /**
     * @return the overlap offset of chunks
     */
    public int getChunkingOverlapOffset()
    {
        return this.chunkingOverlapOffset;
    }

    /**
     * @return the list of spaces for the documents in the collection
     */
    public List<String> getDocumentSpaces()
    {
        return this.documentSpaces;
    }

    /**
     * @return true if guests are allowed to access the collection, false otherwise
     */
    public boolean getAllowGuests()
    {
        return this.allowGuests;
    }

    /**
     * @return the list of groups that can query the collection
     */
    public String getQueryGroups()
    {
        return this.queryGroups;
    }

    /**
     * @return the rights check method that should be used to check if a user has the right to query the collection
     */
    public String getRightsCheckMethod()
    {
        return this.rightsCheckMethod;
    }

    /**
     * @param id the name of the collection
     */
    public void setID(String id)
    {
        this.id = id;
    }

    /**
     * @param title the name of the collection
     */
    public void setTitle(String title)
    {
        this.title = title;
    }

    /**
     * @param embeddingModel the embedding model that should be used to embed chunks of the documents in this collection
     */
    public void setEmbeddingModel(String embeddingModel)
    {
        this.embeddingModel = embeddingModel;
    }

    /**
     * @param chunkingMethod the chunking method that should be used to chunk the documents in this collection
     */
    public void setChunkingMethod(String chunkingMethod)
    {
        this.chunkingMethod = chunkingMethod;
    }

    /**
     * @param chunkingMaxSize the maximum size of a chunk
     */
    public void setChunkingMaxSize(int chunkingMaxSize)
    {
        this.chunkingMaxSize = chunkingMaxSize;
    }

    /**
     * @param chunkingOverlapOffset the overlap offset chunks
     */
    public void setChunkingOverlapOffset(int chunkingOverlapOffset)
    {
        this.chunkingOverlapOffset = chunkingOverlapOffset;
    }

    /**
     * @param documentSpaces the list of spaces for the documents in the collection
     */
    public void setDocumentSpaces(List<String> documentSpaces)
    {
        this.documentSpaces = documentSpaces;
    }

    /**
     * @param allowGuests true if guests are allowed to access the collection, false otherwise
     */
    public void setAllowGuests(boolean allowGuests)
    {
        this.allowGuests = allowGuests;
    }

    /**
     * @param queryGroups the list of groups that can query the collection
     */
    public void setQueryGroups(String queryGroups)
    {
        this.queryGroups = queryGroups;
    }

    /**
     * @param rightsCheckMethod the rights check method that should be used to check if a user has the right to query
     *                          the collection
     */
    public void setRightsCheckMethod(String rightsCheckMethod)
    {
        this.rightsCheckMethod = rightsCheckMethod;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        JSONCollection that = (JSONCollection) o;

        return new EqualsBuilder()
            .append(getChunkingMaxSize(), that.getChunkingMaxSize())
            .append(getChunkingOverlapOffset(), that.getChunkingOverlapOffset())
            .append(getID(), that.getID())
            .append(getTitle(), that.getTitle())
            .append(getEmbeddingModel(), that.getEmbeddingModel())
            .append(getChunkingMethod(), that.getChunkingMethod())
            .append(getDocumentSpaces(), that.getDocumentSpaces())
            .append(getAllowGuests(), that.getAllowGuests())
            .append(getQueryGroups(), that.getQueryGroups())
            .append(getRightsCheckMethod(), that.getRightsCheckMethod())
            .isEquals();
    }

    @Override
    public int hashCode()
    {
        return new HashCodeBuilder(17, 37)
            .append(getID())
            .append(getEmbeddingModel())
            .append(getChunkingMethod())
            .append(getChunkingMaxSize())
            .append(getChunkingOverlapOffset())
            .append(getDocumentSpaces())
            .append(getAllowGuests())
            .append(getQueryGroups())
            .append(getRightsCheckMethod())
            .toHashCode();
    }

    @Override
    public String toString()
    {
        return new XWikiToStringBuilder(this)
            .append("id", this.id)
            .append("title", this.title)
            .append("embeddingModel", this.embeddingModel)
            .append("chunkingMethod", this.chunkingMethod)
            .append("chunkingMaxSize", this.chunkingMaxSize)
            .append("chunkingOverlapOffset", this.chunkingOverlapOffset)
            .append("documentSpaces", this.documentSpaces)
            .append("allowGuests", this.allowGuests)
            .append("queryGroups", this.queryGroups)
            .append("rightsCheckMethod", this.rightsCheckMethod)
            .toString();
    }
}
