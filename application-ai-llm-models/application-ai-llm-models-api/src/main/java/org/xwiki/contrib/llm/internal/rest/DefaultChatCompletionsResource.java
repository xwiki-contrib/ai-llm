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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatClientConfigProvider;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.contrib.llm.internal.CORSUtils;
import org.xwiki.contrib.llm.openai.ChatCompletionRequest;
import org.xwiki.contrib.llm.openai.ChatCompletionResult;
import org.xwiki.contrib.llm.rest.ChatCompletionsResource;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.user.CurrentUserReference;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private static final String CORS_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String CORS_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String CORS_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String CORS_METHODS = "POST, OPTIONS";
    private static final String CORS_HEADERS = "Content-Type, Authorization, Origin";

    @Inject
    private ChatClientConfigProvider configProvider;

    @Inject
    private ChatModelManager chatModelManager;

    @Override
    public Response getCompletions(String origin, String wikiName, ChatCompletionRequest request)
    {
        try {
            String allowedOrigin = CORSUtils.matchOrigin(origin, configProvider, wikiName);
            ChatModel model = this.chatModelManager.getModel(request.model(), CurrentUserReference.INSTANCE, wikiName);

            if (model.supportsStreaming() && Boolean.TRUE.equals(request.stream())) {
                return Response.ok((StreamingOutput) output -> {
                    try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                        writeResponseStream(request, model, writer);
                    }
                }, MediaType.SERVER_SENT_EVENTS_TYPE)
                        .header(CORS_ALLOW_ORIGIN, allowedOrigin)
                        .header(CORS_ALLOW_METHODS, CORS_METHODS)
                        .header(CORS_ALLOW_HEADERS, CORS_HEADERS)
                        .header("Access-Control-Expose-Headers", "Cache-Control, Content-Encoding, Content-Type")
                        .header("Cache-Control", "no-cache")
                        .build();
            } else {
                ChatCompletionResult chatResponse = model.process(request);
                // Convert to OpenAI format
                return Response.ok(chatResponse, MediaType.APPLICATION_JSON_TYPE)
                                .header(CORS_ALLOW_ORIGIN, allowedOrigin)
                                .header(CORS_ALLOW_METHODS, CORS_METHODS)
                                .header(CORS_ALLOW_HEADERS, CORS_HEADERS)
                                .build();
            }
        } catch (RequestError e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(e.getOpenAiError())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header(CORS_ALLOW_ORIGIN, origin)
                .header(CORS_ALLOW_METHODS, CORS_METHODS)
                .header(CORS_ALLOW_HEADERS, CORS_HEADERS)
                .build();
        } catch (GPTAPIException | IOException e) {
            throw new WebApplicationException(e, Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Response options(String origin, String wikiName) throws XWikiRestException
    {
        try {
            String allowedOrigin = CORSUtils.matchOrigin(origin, configProvider, wikiName);
            return CORSUtils.addCORSHeaders(allowedOrigin, CORS_METHODS, CORS_HEADERS).build();
        } catch (Exception e) {
            throw new XWikiRestException("Error handling the preflight request.", e);
        }
    }

    private void writeResponseStream(ChatCompletionRequest request, ChatModel model, OutputStreamWriter writer)
        throws IOException
    {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(Include.NON_NULL);
        try {
            model.processStreaming(request, chunk -> {
                writer.write(DATA_FORMAT.formatted(objectMapper.writeValueAsString(chunk)));
                writer.flush();
            });
        } catch (RequestError e) {
            writer.write(DATA_FORMAT.formatted(objectMapper.writeValueAsString(e.getOpenAiError())));
            writer.flush();
        }

        writer.write(DATA_FORMAT.formatted("[DONE]"));
    }
}
