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

import org.xwiki.stability.Unstable;

/**
 * A descriptor for an embedding model.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
public class EmbeddingModelDescriptor
{
    private final String id;

    private final String name;

    private final int dimensions;

    /**
     * Constructor.
     *
     * @param id the id of the model
     * @param name the name of the model that should be displayed to the user
     * @param dimensions the number of dimensions the embedding has
     */
    public EmbeddingModelDescriptor(String id, String name, int dimensions)
    {
        this.id = id;
        this.name = name;
        this.dimensions = dimensions;
    }

    /**
     * @return the id of the model
     */
    public String getId()
    {
        return this.id;
    }

    /**
     * @return the name of the model that should be displayed to the user
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * @return the number of dimensions the embedding has
     */
    public int getDimensions()
    {
        return this.dimensions;
    }
}
