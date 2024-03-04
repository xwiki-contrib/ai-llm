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

import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatMessage;
import org.xwiki.contrib.llm.ChatResponse;
import org.xwiki.contrib.llm.openai.ChatCompletionChunk;
import org.xwiki.contrib.llm.openai.ChatCompletionChunkChoice;

import com.theokanning.openai.completion.chat.ChatCompletionChoice;
import com.theokanning.openai.completion.chat.ChatCompletionResult;

/**
 * Convert a {@link ChatCompletionResult} object to a {@link ChatResponse} object.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = ChatResponseConverter.class)
@Singleton
public class ChatResponseConverter
{
    private static final String CHAT_COMPLETION_CHUNK = "chat.completion.chunk";

    /**
     * Convert a {@link ChatCompletionResult} object to a {@link ChatResponse} object.
     *
     * @param result the result to convert
     * @return the converted response
     */
    public ChatResponse fromOpenAIResponse(ChatCompletionResult result)
    {
        List<ChatCompletionChoice> chatCompletionChoices = result.getChoices();
        ChatCompletionChoice chatCompletionChoice = chatCompletionChoices.get(0);
        com.theokanning.openai.completion.chat.ChatMessage resultMessage =
            chatCompletionChoice.getMessage();
        return new ChatResponse(chatCompletionChoice.getFinishReason(),
            new ChatMessage(resultMessage.getRole(), resultMessage.getContent()));
    }

    /**
     * Convert a {@link ChatResponse} object to a {@link ChatCompletionResult} object.
     *
     * @param chatResponse the response to convert
     * @param model the model used to generate the response
     * @return the converted result
     */
    public ChatCompletionResult toOpenAIChatCompletionResult(ChatResponse chatResponse, String model)
    {
        ChatCompletionResult result = new ChatCompletionResult();
        result.setObject("chat.completion");
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setFinishReason(chatResponse.getFinishReason());
        choice.setIndex(0);
        choice.setMessage(new com.theokanning.openai.completion.chat.ChatMessage(
            chatResponse.getMessage().getRole(),
            chatResponse.getMessage().getContent()));
        result.setChoices(List.of(choice));
        result.setModel(model);
        return result;
    }

    /**
     * Convert a {@link ChatResponse} object to a {@link ChatCompletionChunk} object.
     *
     * @param chatResponse the response to convert
     * @param model the model used to generate the response
     * @return the converted chunk
     */
    public ChatCompletionChunk toOpenAIChatCompletionChunk(ChatResponse chatResponse, String model)
    {
        com.theokanning.openai.completion.chat.ChatMessage message =
            new com.theokanning.openai.completion.chat.ChatMessage(
                chatResponse.getMessage().getRole(),
                chatResponse.getMessage().getContent());
        var choice = new ChatCompletionChunkChoice(0, message, chatResponse.getFinishReason());
        return new ChatCompletionChunk(null, CHAT_COMPLETION_CHUNK, 0, model, List.of(choice));
    }
}
