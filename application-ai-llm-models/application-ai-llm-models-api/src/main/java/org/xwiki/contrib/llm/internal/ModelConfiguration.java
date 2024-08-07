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
package org.xwiki.contrib.llm.internal;

import java.util.List;

import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.user.UserReference;

/**
 * Model configuration, basically a parameter object.
 *
 * @version $Id$
 * @since 0.3
 */
public class ModelConfiguration
{
    private String id;

    private String name;

    private String serverName;

    private String model;

    private String embeddingIndexPrefix;

    private String embeddingQueryPrefix;

    private int dimensions;

    private int contextSize;

    private int maximumParallelism;

    private boolean allowGuests;

    private List<DocumentReference> allowedGroups;

    private ObjectReference objectReference;

    private UserReference author;

    /**
     * @return the id of the model
     */
    public String getID()
    {
        return this.id;
    }

    /**
     * @return the display name of the model
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * @return the name of the server configuration to use
     */
    public String getServerName()
    {
        return this.serverName;
    }

    /**
     * @return the id of the model to use
     */
    public String getModel()
    {
        return this.model;
    }

    /**
     * @return the prefix of the index to use for the embeddings
     */
    public String getEmbeddingIndexPrefix()
    {
        return this.embeddingIndexPrefix;
    }

    /**
     * @return the prefix of the query to use for the embeddings
     */
    public String getEmbeddingQueryPrefix()
    {
        return this.embeddingQueryPrefix;
    }

    /**
     * @return the number of dimensions of the model in the case of an embedding model
     */
    public int getDimensions()
    {
        return this.dimensions;
    }

    /**
     * @return the context size of the model
     */
    public int getContextSize()
    {
        return this.contextSize;
    }

    /**
     * @return the maximum number of embedding requests to put in a single request
     */
    public int getMaximumParallelism()
    {
        return this.maximumParallelism;
    }

    /**
     * @return the reference of the object that contains the model configuration
     */
    public ObjectReference getObjectReference()
    {
        return this.objectReference;
    }

    /**
     * @return the author of the document containing the model configuration
     */
    public UserReference getAuthor()
    {
        return this.author;
    }

    /**
     * @return {@code true} if guests are allowed to access the model, {@code false} otherwise
     */
    public boolean isAllowGuests()
    {
        return this.allowGuests;
    }

    /**
     * @return the list of user groups allowed to access the model
     */
    public List<DocumentReference> getAllowedGroups()
    {
        return this.allowedGroups;
    }

    /**
     * @param id the id of the model
     */
    public void setId(String id)
    {
        this.id = id;
    }

    /**
     * @param name the display name of the model
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * @param serverName the name of the server configuration to use
     */
    public void setServerName(String serverName)
    {
        this.serverName = serverName;
    }

    /**
     * @param model the id of the model to use
     */
    public void setModel(String model)
    {
        this.model = model;
    }

    /**
     * @param embeddingIndexPrefix the prefix of the index to use for the embeddings
     */
    public void setEmbeddingIndexPrefix(String embeddingIndexPrefix)
    {
        this.embeddingIndexPrefix = embeddingIndexPrefix;
    }

    /**
     * @param embeddingQueryPrefix the prefix of the query to use for the embeddings
     */
    public void setEmbeddingQueryPrefix(String embeddingQueryPrefix)
    {
        this.embeddingQueryPrefix = embeddingQueryPrefix;
    }
    
    /**
     * @param dimensions the number of dimensions of the model in the case of an embedding model
     */
    public void setDimensions(int dimensions)
    {
        this.dimensions = dimensions;
    }

    /**
     * @param contextSize the context size of the model
     */
    public void setContextSize(int contextSize)
    {
        this.contextSize = contextSize;
    }

    /**
     * @param maximumParallelism the maximum number of embedding requests to put in a single request
     */
    public void setMaximumParallelism(int maximumParallelism)
    {
        this.maximumParallelism = maximumParallelism;
    }

    /**
     * @param allowGuests {@code true} if guests are allowed to access the model, {@code false} otherwise
     */
    public void setAllowGuests(boolean allowGuests)
    {
        this.allowGuests = allowGuests;
    }

    /**
     * @param allowedGroups the list of user groups allowed to access the model
     */
    public void setAllowedGroups(List<DocumentReference> allowedGroups)
    {
        this.allowedGroups = allowedGroups;
    }

    /**
     * @param objectReference the reference of the object that contains the model configuration
     */
    public void setObjectReference(ObjectReference objectReference)
    {
        this.objectReference = objectReference;
    }

    /**
     * @param author the author of the document containing the model configuration
     */
    public void setAuthor(UserReference author)
    {
        this.author = author;
    }
}
