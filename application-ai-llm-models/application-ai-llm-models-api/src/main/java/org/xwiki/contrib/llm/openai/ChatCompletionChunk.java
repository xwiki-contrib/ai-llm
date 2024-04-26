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

import org.xwiki.stability.Unstable;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Represents a chunk of a streaming chat completion response.
 *
 * @param id the id of the chunk, each chunk of the request has the same id
 * @param object the object type, should be "chat.completion.chunk"
 * @param created the unix timestamp (in seconds) when the chat completion was created, each chunk of a request has
 * the same timestamp
 * @param model the model used to generate the completion
 * @param choices the chat completion choices
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@JsonAutoDetect(creatorVisibility = JsonAutoDetect.Visibility.ANY)
public record ChatCompletionChunk(
    String id,
    String object,
    long created,
    String model,
    List<ChatCompletionChunkChoice> choices)
{
    /**
     * Creates a new chat completion chunk.
     *
     * @param id the id of the chunk
     * @param created the unix timestamp (in seconds) when the chat completion was created
     * @param model the model used to generate the completion
     * @param choices the chat completion choices
     */
    public ChatCompletionChunk(String id, long created, String model, List<ChatCompletionChunkChoice> choices)
    {
        this(id, "chat.completion.chunk", created, model, choices);
    }
}
