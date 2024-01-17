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

import org.xwiki.contrib.llm.Collection;
import org.xwiki.stability.Unstable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Collection representation for the REST API.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
public class JSONCollection
{
    private String name;


    @JsonProperty("embedding_model")
    private String embeddingModel;

    /**
     * Construct a collection from a {@link Collection}.
     *
     * @param collection the collection to construct from
     */
    public JSONCollection(Collection collection)
    {
        this.name = collection.getName();
        this.embeddingModel = collection.getEmbeddingModel();
    }

    /**
     * Applies the non-null properties of this collection to a {@link Collection}.
     *
     * @param collection the collection to apply to
     */
    public void applyTo(Collection collection)
    {
        if (this.embeddingModel != null) {
            collection.setEmbeddingModel(this.embeddingModel);
        }
    }

    /**
     * @return the name of the collection
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * @return the embedding model that should be used to embed chunks of the documents in this collection
     */
    public String getEmbeddingModel()
    {
        return this.embeddingModel;
    }

    /**
     * @param name the name of the collection
     */
    public void setName(String name)
    {
        this.name = name;
    }


    /**
     * @param embeddingModel the embedding model that should be used to embed chunks of the documents in this collection
     */
    public void setEmbeddingModel(String embeddingModel)
    {
        this.embeddingModel = embeddingModel;
    }
}
