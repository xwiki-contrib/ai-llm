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

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.xwiki.stability.Unstable;

/**
 * A chat completion request to complete the given chat messages.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
public class ChatRequest
{
    private final List<ChatMessage> messages;

    private final ChatRequestParameters parameters;

    /**
     * Creates a new chat completion request.
     *
     * @param messages the chat messages to complete
     * @param parameters the parameters to use for the completion
     */
    public ChatRequest(List<ChatMessage> messages, ChatRequestParameters parameters)
    {
        this.messages = messages;
        this.parameters = parameters;
    }

    /**
     * @return the chat messages to complete
     */
    public List<ChatMessage> getMessages()
    {
        return this.messages;
    }

    /**
     * @return the parameters to use for the completion
     */
    public ChatRequestParameters getParameters()
    {
        return this.parameters;
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

        ChatRequest that = (ChatRequest) o;

        return new EqualsBuilder().append(getMessages(), that.getMessages())
            .append(getParameters(), that.getParameters()).isEquals();
    }

    @Override
    public int hashCode()
    {
        return new HashCodeBuilder(17, 37).append(getMessages()).append(getParameters()).toHashCode();
    }
}
