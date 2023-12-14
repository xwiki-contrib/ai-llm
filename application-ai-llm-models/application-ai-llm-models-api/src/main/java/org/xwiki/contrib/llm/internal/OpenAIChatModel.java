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
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.hc.core5.http.HttpEntity;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.ChatMessage;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatRequest;
import org.xwiki.contrib.llm.ChatResponse;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.RequestError;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.completion.chat.ChatCompletionChoice;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;

/**
 * Chat model implementation that uses the OpenAI API.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = { OpenAIChatModel.class })
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class OpenAIChatModel implements ChatModel
{
    @Inject
    private RequestHelper requestHelper;

    private GPTAPIConfig config;

    private String model;

    /**
     * Initialize the model.
     *
     * @param config the API configuration
     * @param model the model to use
     */
    public void initialize(GPTAPIConfig config, String model)
    {
        this.config = config;
        this.model = model;
    }

    @Override
    public void processStreaming(ChatRequest request, Consumer<ChatResponse> consumer) throws RequestError
    {

        // TODO: Implement this once JAX-RS 2.1 with real streaming is available. For now, fall back to non-streaming.
        // With JAX-RS 2.1, use real streaming if the model supports it, otherwise fall back to non-streaming.
        consumer.accept(process(request));
    }

    @Override
    public ChatResponse process(ChatRequest request) throws RequestError
    {
        List<com.theokanning.openai.completion.chat.ChatMessage> messages = request.getMessages().stream()
            .map(message ->
                new com.theokanning.openai.completion.chat.ChatMessage(message.getRole(), message.getContent()))
            .collect(Collectors.toList());
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
            .model(this.model)
            .temperature(request.getParameters().getTemperature())
            .messages(messages)
            .build();

        try {
            return this.requestHelper.post(this.config,
                "/chat/completions",
                chatCompletionRequest,
                response -> {
                    if (response.getCode() == 200) {
                        HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            InputStream inputStream = entity.getContent();
                            ObjectMapper objectMapper = new ObjectMapper();
                            OpenAiResponse<ChatCompletionResult> modelOpenAiResponse =
                                objectMapper.readValue(inputStream,
                                    new TypeReference<OpenAiResponse<ChatCompletionResult>>()
                                    {
                                    });
                            List<ChatCompletionChoice> chatCompletionChoices =
                                modelOpenAiResponse.getData().get(0).getChoices();
                            ChatCompletionChoice chatCompletionChoice = chatCompletionChoices.get(0);
                            com.theokanning.openai.completion.chat.ChatMessage resultMessage =
                                chatCompletionChoice.getMessage();
                            return new ChatResponse(chatCompletionChoice.getFinishReason(),
                                new ChatMessage(resultMessage.getRole(), resultMessage.getContent()));
                        } else {
                            throw new IOException("Response is empty.");
                        }
                    } else {
                        throw new IOException("Response code is " + response.getCode());
                    }
                });
        } catch (Exception e) {
            throw new RequestError(500, e.getMessage());
        }

        /*
        // TODO: don't recreate the client every time. However, the client is also not thread-safe so we can't just
        // use a single client for all requests. We could use a thread-local client but that would require us to
        // handle cleanup, which is not trivial. An object pool might be a better solution.
        Client client = ClientBuilder.newClient();
        Invocation.Builder completionRequest = client.target(config.getURL())
            .path("chat/completions")
            .request(MediaType.APPLICATION_JSON)
            .header(AUTHORIZATION, BEARER + config.getToken());

        GenericType<OpenAiResponse<ChatCompletionResult>> chatCompletionResponseType = new GenericType<>()
        {
        };

        try {
            // TODO: change this to use jax-rs 2.0 where we can get a response object and then read different types
            //  of entities depending on the success/error.
            OpenAiResponse<ChatCompletionResult> modelOpenAiResponse =
                completionRequest.post(Entity.entity(chatCompletionRequest, MediaType.APPLICATION_JSON_TYPE),
                    chatCompletionResponseType);

            // TODO: Better error handling.
            List<ChatCompletionChoice> chatCompletionChoices = modelOpenAiResponse.getData().get(0).getChoices();
            ChatCompletionChoice chatCompletionChoice = chatCompletionChoices.get(0);
            com.theokanning.openai.completion.chat.ChatMessage resultMessage = chatCompletionChoice.getMessage();
            return new ChatResponse(chatCompletionChoice.getFinishReason(),
                new ChatMessage(resultMessage.getRole(), resultMessage.getContent()));
        } catch (WebApplicationException e) {
            throw new RequestError(e.getResponse().getStatus(), e.getMessage());
        }
        */
    }

    @Override
    public boolean supportsStreaming()
    {
        return this.config.getCanStream();
    }
}
