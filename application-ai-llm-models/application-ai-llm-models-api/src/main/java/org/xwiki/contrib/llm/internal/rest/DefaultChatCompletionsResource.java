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
package org.xwiki.contrib.llm.internal.rest;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatMessage;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.ChatRequest;
import org.xwiki.contrib.llm.ChatRequestParameters;
import org.xwiki.contrib.llm.ChatResponse;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.contrib.llm.internal.ChatResponseConverter;
import org.xwiki.contrib.llm.openai.ChatCompletionChunk;
import org.xwiki.contrib.llm.rest.ChatCompletionsResource;
import org.xwiki.rest.XWikiResource;
import org.xwiki.user.CurrentUserReference;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;

/**
 * Default implementation of {@link ChatCompletionsResource}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named("org.xwiki.contrib.llm.internal.rest.DefaultChatCompletionsResource")
@Singleton
public class DefaultChatCompletionsResource extends XWikiResource implements ChatCompletionsResource
{
    private static final String DATA_FORMAT = "data: %s%n%n";

    @Inject
    private ChatModelManager chatModelManager;

    @Inject
    private ChatResponseConverter chatResponseConverter;

    @Override
    public Response getCompletions(String wikiName, ChatCompletionRequest request)
    {
        try {
            ChatModel model =
                this.chatModelManager.getModel(request.getModel(), CurrentUserReference.INSTANCE, wikiName);

            ChatRequest chatRequest = getChatRequest(request);

            if (model.supportsStreaming() && Boolean.TRUE.equals(request.getStream())) {
                return Response.ok((StreamingOutput) output -> {
                    try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                        writeResponseStream(request, model, chatRequest, writer);
                    }
                }, MediaType.SERVER_SENT_EVENTS_TYPE).build();
            } else {
                ChatResponse chatResponse = model.process(chatRequest);
                // Convert to OpenAI format
                ChatCompletionResult openAIChatCompletionResult =
                    this.chatResponseConverter.toOpenAIChatCompletionResult(chatResponse, request.getModel());
                return Response.ok(openAIChatCompletionResult, MediaType.APPLICATION_JSON_TYPE).build();
            }
        } catch (GPTAPIException | RequestError | IOException e) {
            throw new WebApplicationException(e, Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private void writeResponseStream(ChatCompletionRequest request, ChatModel model, ChatRequest chatRequest,
        OutputStreamWriter writer) throws IOException
    {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(Include.NON_NULL);
        try {
            model.processStreaming(chatRequest, chatResponse -> {
                ChatCompletionChunk chunk =
                    this.chatResponseConverter.toOpenAIChatCompletionChunk(chatResponse,
                        request.getModel());
                writer.write(DATA_FORMAT.formatted(objectMapper.writeValueAsString(chunk)));
                writer.flush();
            });
        } catch (RequestError e) {
            writer.write(DATA_FORMAT.formatted(objectMapper.writeValueAsString(e)));
            writer.flush();
        }
    }

    private ChatRequest getChatRequest(ChatCompletionRequest request)
    {
        List<ChatMessage> messages = request.getMessages().stream()
            .map(m -> new ChatMessage(m.getRole(), m.getContent()))
            .toList();
        ChatRequestParameters parameters = new ChatRequestParameters(request.getTemperature());
        return new ChatRequest(messages, parameters);
    }
}
