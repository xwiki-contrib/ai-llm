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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.hc.core5.http.HttpEntity;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.ChatRequest;
import org.xwiki.contrib.llm.ChatRequestFilter;
import org.xwiki.contrib.llm.ChatResponse;
import org.xwiki.contrib.llm.RequestError;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;

/**
 * Chat model implementation that uses the OpenAI API.
 *
 * @version $Id$
 * @since 0.3
 */
public class OpenAIChatModel extends AbstractModel implements ChatRequestFilter
{
    private static final String PATH = "chat/completions";

    private static final String EMPTY = "Response is empty.";

    private static final String RESPONSE_CODE_ERROR = "Response code is %d";

    private final RequestHelper requestHelper;

    private final ChatRequestConverter chatRequestConverter;

    private final ChatResponseConverter chatResponseConverter;

    /**
     * Initialize the model.
     *
     * @param modelConfiguration the configuration of the model
     * @param componentManager the component manager
     */
    public OpenAIChatModel(ModelConfiguration modelConfiguration, ComponentManager componentManager) throws
        ComponentLookupException
    {
        super(modelConfiguration, componentManager);
        this.requestHelper = componentManager.getInstance(RequestHelper.class);
        this.chatRequestConverter = componentManager.getInstance(ChatRequestConverter.class);
        this.chatResponseConverter = componentManager.getInstance(ChatResponseConverter.class);
    }

    @Override
    public void setNext(ChatRequestFilter next)
    {
        // Ignored, this is the last filter.
    }

    @Override
    public void processStreaming(ChatRequest request, FailableConsumer<ChatResponse, IOException> consumer)
        throws IOException, RequestError
    {
        if (Boolean.TRUE.equals(this.getConfig().getCanStream())) {
            ChatCompletionRequest chatCompletionRequest = this.chatRequestConverter.toOpenAI(request,
                this.modelConfiguration.getModel());
            chatCompletionRequest.setStream(true);

            try {
                this.requestHelper.post(this.getConfig(),
                    PATH,
                    chatCompletionRequest,
                    response -> {
                        if (response.getCode() == 200 && response.getEntity() != null) {
                            HttpEntity entity = response.getEntity();
                            InputStream inputStream = entity.getContent();
                            ObjectMapper objectMapper = new ObjectMapper();
                            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

                            // Read the SSE stream and call the consumer for every chunk
                            this.requestHelper.readSSEStream(inputStream, chunk -> {
                                if ("[DONE]\n".equals(chunk)) {
                                    return;
                                }
                                ChatCompletionResult chatCompletionResult =
                                    objectMapper.readValue(chunk, ChatCompletionResult.class);
                                consumer.accept(
                                    this.chatResponseConverter.fromOpenAIResponse(chatCompletionResult));
                            });
                        } else {
                            throw new IOException(String.format(RESPONSE_CODE_ERROR, response.getCode()));
                        }

                        return null;
                    });
            } catch (Exception e) {
                throw new RequestError(500, e.getMessage());
            }
        } else {
            consumer.accept(this.process(request));
        }
    }

    @Override
    public ChatResponse process(ChatRequest request) throws RequestError
    {
        ChatCompletionRequest chatCompletionRequest = this.chatRequestConverter.toOpenAI(request,
            this.modelConfiguration.getModel());

        try {
            return this.requestHelper.post(this.getConfig(),
                PATH,
                chatCompletionRequest,
                response -> {
                    if (response.getCode() == 200) {
                        HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            InputStream inputStream = entity.getContent();
                            ObjectMapper objectMapper = new ObjectMapper();
                            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                            return this.chatResponseConverter.fromOpenAIResponse(
                                objectMapper.readValue(inputStream, ChatCompletionResult.class));
                        } else {
                            throw new IOException(EMPTY);
                        }
                    } else {
                        throw new IOException(String.format(RESPONSE_CODE_ERROR, response.getCode()));
                    }
                });
        } catch (Exception e) {
            throw new RequestError(500, e.getMessage());
        }
    }

    @Override
    public Type getRoleType()
    {
        return ChatRequestFilter.class;
    }
}
