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

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.xwiki.stability.Unstable;

/**
 * Response of a chat completion request.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
public class ChatResponse
{
    private final String finishReason;

    private final ChatMessage message;

    /**
     * Creates a new chat response.
     *
     * @param finishReason the reason why the chat was finished, e.g., "stop", "length", "content_filter"
     * @param message the completed message
     */
    public ChatResponse(String finishReason, ChatMessage message)
    {
        this.finishReason = finishReason;
        this.message = message;
    }

    /**
     * @return the reason why the chat was finished, e.g., "stop", "length", "content_filter"
     */
    public String getFinishReason()
    {
        return finishReason;
    }

    /**
     * @return the completed message
     */
    public ChatMessage getMessage()
    {
        return message;
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

        ChatResponse that = (ChatResponse) o;

        return new EqualsBuilder().append(getFinishReason(), that.getFinishReason())
            .append(getMessage(), that.getMessage()).isEquals();
    }

    @Override
    public int hashCode()
    {
        return new HashCodeBuilder(17, 37).append(getFinishReason()).append(getMessage()).toHashCode();
    }
}
