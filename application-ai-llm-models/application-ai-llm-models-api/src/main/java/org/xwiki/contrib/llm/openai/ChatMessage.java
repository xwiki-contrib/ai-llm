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
package org.xwiki.contrib.llm.openai;

import java.util.List;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.xwiki.stability.Unstable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * A chat message.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ChatMessage
{
    private final String role;

    // Always include content in the JSON representation, even if it's null.
    @JsonInclude()
    private String content;

    private List<Context> context;

    /**
     * Creates a new chat message.
     *
     * @param role the role of the message author, e.g., user, assistant, system or tool.
     * @param content the content of the message
     */
    public ChatMessage(String role, String content)
    {
        this.role = role;
        this.content = content;
    }

    /**
     * Creates a new chat message.
     *
     * @param role the role of the message author, e.g., user, assistant, system or tool.
     * @param content the content of the message
     * @param context the context of the message
     */
    public ChatMessage(String role, String content, List<Context> context)
    {
        this.role = role;
        this.content = content;
        this.context = context;
    }

    /**
     * @return the role of the message author, e.g., user, assistant, system or tool.
     */
    public String getRole()
    {
        return this.role;
    }

    /**
     * @return the content of the message
     */
    public String getContent()
    {
        return this.content;
    }

    /**
     * @return the context of the message
     */
    public List<Context> getContext()
    {
        return this.context;
    }

    /**
     * Sets the context of the message.
     *
     * @param context the context of the message
     */
    public void setContext(List<Context> context)
    {
        this.context = context;
    }
    
    /**
     * Sets the content of the message.
     *
     * @param content the content of the message
     */
    public void setContent(String content)
    {
        this.content = content;
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

        ChatMessage that = (ChatMessage) o;

        return new EqualsBuilder()
            .append(getRole(), that.getRole())
            .append(getContent(), that.getContent())
            .append(getContext(), that.getContext())
            .isEquals();
    }

    @Override
    public int hashCode()
    {
        return new HashCodeBuilder(17, 37).append(getRole()).append(getContent()).append(getContext()).toHashCode();
    }
}
