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
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatRequest;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;

/**
 * Convert a {@link ChatRequest} object to a {@link ChatCompletionRequest}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = ChatRequestConverter.class)
@Singleton
public class ChatRequestConverter
{
    /**
     * Convert a {@link ChatRequest} object to a {@link ChatCompletionRequest} object.
     *
     * @param request the request to convert
     * @param model the model to use
     * @return the converted request
     */
    public ChatCompletionRequest toOpenAI(ChatRequest request, String model)
    {
        List<ChatMessage> messages = request.getMessages().stream()
            .map(message ->
                new com.theokanning.openai.completion.chat.ChatMessage(message.getRole(), message.getContent()))
            .collect(Collectors.toList());
        return ChatCompletionRequest.builder()
            .model(model)
            .temperature(request.getParameters().getTemperature())
            .messages(messages)
            .build();
    }
}
