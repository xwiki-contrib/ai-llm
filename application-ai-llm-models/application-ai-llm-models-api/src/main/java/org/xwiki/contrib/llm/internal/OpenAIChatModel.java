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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

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
    public void processStreaming(ChatRequest request, FailableConsumer<String, IOException> consumer)
        throws IOException
    {

        // TODO: Implement this once JAX-RS 2.1 with real streaming is available. For now, fall back to non-streaming.
        // With JAX-RS 2.1, use real streaming if the model supports it, otherwise fall back to non-streaming.
        ChatCompletionRequest chatCompletionRequest = this.chatRequestConverter.toOpenAI(request,
            this.modelConfiguration.getModel());
        chatCompletionRequest.setStream(true);

        this.requestHelper.post(this.getConfig(),
            PATH,
            chatCompletionRequest,
            response -> {
                if (response.getCode() == 200) {
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        InputStream inputStream = entity.getContent();
                        BufferedReader reader = new BufferedReader(
                                                    new InputStreamReader(inputStream, StandardCharsets.UTF_8)
                                                );
                        String line;
                        while ((line = reader.readLine()) != null) {
                            consumer.accept(line);
                        }

                        return null;
                    } else {
                        throw new IOException(EMPTY);
                    }
                } else {
                    throw new IOException(String.format(RESPONSE_CODE_ERROR, response.getCode()));
                }
            });
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
    public Type getRoleType()
    {
        return ChatRequestFilter.class;
    }
}
