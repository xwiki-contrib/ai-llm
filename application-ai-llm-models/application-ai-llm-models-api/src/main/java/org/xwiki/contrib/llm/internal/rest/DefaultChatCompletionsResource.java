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

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

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
import org.xwiki.contrib.llm.rest.ChatCompletionsResource;
import org.xwiki.rest.XWikiResource;
import org.xwiki.user.CurrentUserReference;

import com.theokanning.openai.completion.chat.ChatCompletionChoice;
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
    @Inject
    private ChatModelManager chatModelManager;

    @Override
    public Response getCompletions(String wikiName, ChatCompletionRequest request)
    {
        try {
            ChatModel model =
                this.chatModelManager.getModel(request.getModel(), CurrentUserReference.INSTANCE, wikiName);

            List<ChatMessage> messages = request.getMessages().stream()
                .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                .collect(Collectors.toList());
            ChatRequestParameters parameters = new ChatRequestParameters(request.getTemperature());
            ChatRequest chatRequest = new ChatRequest(messages, parameters);

            if (model.supportsStreaming() && Boolean.TRUE.equals(request.getStream())) {
                return Response.ok((StreamingOutput) output -> {
                    OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);

                    model.processStreaming(chatRequest, line -> {
                        writer.write(line);
                        writer.flush();
                    });
                }, MediaType.TEXT_PLAIN).build();
            } else {
                // Convert to OpenAI format
                ChatResponse chatResponse = model.process(chatRequest);
                ChatCompletionResult result = new ChatCompletionResult();
                result.setObject("chat.completion");
                ChatCompletionChoice choice = new ChatCompletionChoice();
                choice.setFinishReason(chatResponse.getFinishReason());
                choice.setIndex(0);
                choice.setMessage(new com.theokanning.openai.completion.chat.ChatMessage(
                                        chatResponse.getMessage().getRole(),
                                        chatResponse.getMessage().getContent()));
                result.setChoices(List.of(choice));
                return Response.ok(result).build();
            }
        } catch (GPTAPIException | RequestError e) {
            throw new WebApplicationException(e, Response.Status.INTERNAL_SERVER_ERROR);
        }
    }
}
