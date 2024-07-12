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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.FailableConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.ChatRequestFilter;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.contrib.llm.openai.ChatCompletionChunk;
import org.xwiki.contrib.llm.openai.ChatCompletionChunkChoice;
import org.xwiki.contrib.llm.openai.ChatCompletionRequest;
import org.xwiki.contrib.llm.openai.ChatCompletionResult;
import org.xwiki.contrib.llm.openai.StreamOptions;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiError;

/**
 * Chat model implementation that uses the OpenAI API.
 *
 * @version $Id$
 * @since 0.3
 */
public class OpenAIChatModel extends AbstractModel implements ChatRequestFilter
{
    private static final String PATH = "chat/completions";

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAIChatModel.class);

    private final RequestHelper requestHelper;

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
    }

    @Override
    public void setNext(ChatRequestFilter next)
    {
        // Ignored, this is the last filter.
    }

    @Override
    public void processStreaming(ChatCompletionRequest request,
        FailableConsumer<ChatCompletionChunk, IOException> consumer) throws IOException
    {
        GPTAPIConfig config = this.getConfig();
        if (Boolean.TRUE.equals(config.getCanStream())) {
            // Set the model to the model in the configuration
            ChatCompletionRequest adaptedRequest = setModel(request, true,
                // Only include the stream options when calling the OpenAI API as other providers do not seem to like
                // it (in particular, Fireworks and Mistral).
                StringUtils.startsWith(config.getURL(), "https://api.openai.com"));

            HttpResponse<InputStream> httpResponse = this.requestHelper.post(config, PATH,
                adaptedRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = httpResponse.body()) {
                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

                if (httpResponse.statusCode() == 200) {
                    // Read the SSE stream and call the consumer for every chunk
                    this.requestHelper.readSSEStream(body, chunk -> {
                        if ("[DONE]\n".equals(chunk)) {
                            return;
                        }

                        try {
                            ChatCompletionChunk chatCompletionResult =
                                objectMapper.readValue(chunk, ChatCompletionChunk.class);
                            // Replace the model by the model from the request
                            ChatCompletionChunk newChunk = chatCompletionResult.but().model(request.model()).build();
                            consumer.accept(newChunk);
                        } catch (JacksonException e) {
                            throw handleError(objectMapper, 200, chunk);
                        }
                    });
                } else {
                    throw handleError(objectMapper, httpResponse.statusCode(),
                        IOUtils.toString(body, StandardCharsets.UTF_8));
                }
            } catch (EOFException e) {
                // Ignore, this is expected when request is closed by the client.
            }
        } else {
            ChatCompletionResult response = this.process(request);
            List<ChatCompletionChunkChoice> choices = response.choices().stream()
                .map(choice -> new ChatCompletionChunkChoice(choice.index(), choice.message(), choice.finishReason()))
                .toList();
            ChatCompletionChunk chunk = ChatCompletionChunk.builder()
                .id(response.id())
                .model(request.model())
                .created(response.created())
                .choices(choices)
                .usage(response.usage())
                .build();
            consumer.accept(chunk);
        }
    }

    private RequestError handleError(ObjectMapper objectMapper, int code, String response)
    {
        String errorMessage = response;
        // Try getting the actual error message from the content.
        try {
            errorMessage = objectMapper.readValue(response, OpenAiError.class).getError().getMessage();
        } catch (Exception e) {
            // Ignore.
        }

        LOGGER.error("Got error response from model {} on server {}: {}", this.modelConfiguration.getModel(),
            this.modelConfiguration.getServerName(), response);

        return new RequestError(code, errorMessage);
    }

    private ChatCompletionRequest setModel(ChatCompletionRequest request, boolean stream, boolean supportsUsage)
    {
        ChatCompletionRequest.Builder builder = request.but()
            .model(this.modelConfiguration.getModel())
            .stream(stream);
        if (stream && supportsUsage) {
            // Default usage information to true as it just makes a lot of sense to have it.
            builder.streamOptions(new StreamOptions(true));
        } else {
            // Remove streaming options as they should only be set when stream is set to true.
            builder.streamOptions(null);
        }
        return builder.build();
    }

    @Override
    public ChatCompletionResult process(ChatCompletionRequest request) throws IOException
    {
        ChatCompletionRequest adaptedRequest = setModel(request, false, false);
        HttpResponse<String> httpResponse = this.requestHelper.post(this.getConfig(),
            PATH, adaptedRequest, HttpResponse.BodyHandlers.ofString());
        String body = httpResponse.body();
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        if (httpResponse.statusCode() == 200) {
            try {
                return objectMapper.readValue(body, ChatCompletionResult.class);
            } catch (JacksonException e) {
                throw handleError(objectMapper, 200, body);
            }
        } else {
            throw handleError(objectMapper, httpResponse.statusCode(), body);
        }
    }

    @Override
    public Type getRoleType()
    {
        return ChatRequestFilter.class;
    }
}
