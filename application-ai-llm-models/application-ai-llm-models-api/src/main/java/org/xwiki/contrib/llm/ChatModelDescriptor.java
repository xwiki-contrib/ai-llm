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

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * A descriptor for a chat model.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ChatModelDescriptor
{
    private String id;

    private String name;

    private int contextLength;

    private boolean canStream;

    /**
     * Default constructor.
     */
    public ChatModelDescriptor()
    {
    }

    /**
     * Constructor.
     *
     * @param id the id of the model
     * @param name the name of the model that should be displayed to the user
     * @param contextLength the number of tokens that this model supports as context
     * @param canStream whether the model supports streaming
     */
    public ChatModelDescriptor(String id, String name, int contextLength, boolean canStream)
    {
        this.id = id;
        this.name = name;
        this.contextLength = contextLength;
        this.canStream = canStream;
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
     * @return the number of tokens that this model supports as context
     */
    public int getContextLength()
    {
        return this.contextLength;
    }

    /**
     * @param id the id of the model
     */
    public void setId(String id)
    {
        this.id = id;
    }

    /**
     * @param name the name of the model that should be displayed to the user
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * @param contextLength the number of tokens that this model supports as context
     */
    public void setContextLength(int contextLength)
    {
        this.contextLength = contextLength;
    }

    /**
     * @return whether the model supports streaming
     */
    public boolean getCanStream()
    {
        return this.canStream;
    }

    /**
     * @param canStream whether the model supports streaming
     */
    public void setCanStream(boolean canStream)
    {
        this.canStream = canStream;
    }
}
