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
import java.net.http.HttpResponse;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.RequestError;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiError;
import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.embedding.Embedding;
import com.theokanning.openai.embedding.EmbeddingRequest;

/**
 * Implementation of {@link GPTAPIServer} that uses the OpenAI API. This component is meant to be instantiated and
 * registered by the {@link GPTAPIServerWikiObjectComponentBuilder}.
 *
 * @version $Id$
 * @since 0.5
 */
@Component(roles = GPTAPIServerWikiComponent.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
@Named("openai")
public class OpenAIGPTAPIServer extends AbstractGPTAPIServer
{
    @Inject
    private RequestHelper requestHelper;

    @Override
    public List<double[]> embed(String model, List<String> texts) throws RequestError
    {
        EmbeddingRequest request = new EmbeddingRequest(model, texts, null);

        try {
            HttpResponse<InputStream> httpResponse =
                this.requestHelper.post(this.config, "embeddings", request, HttpResponse.BodyHandlers.ofInputStream());
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            if (httpResponse.statusCode() != 200) {
                OpenAiError error = objectMapper.readValue(httpResponse.body(), OpenAiError.class);
                throw new RequestError(httpResponse.statusCode(), error.error.getMessage());
            }

            OpenAiResponse<Embedding> openAiResponse = readEmbeddingResponse(objectMapper, httpResponse);

            if (openAiResponse.data != null) {
                return openAiResponse.data.stream()
                    .map(Embedding::getEmbedding)
                    .map(list -> list.stream().mapToDouble(Double::doubleValue).toArray())
                    .toList();
            } else {
                throw new IOException("Response data is null");
            }
        } catch (RequestError e) {
            // Don't let the next catch clause catch this more specific exception.
            throw e;
        } catch (IOException e) {
            throw new RequestError(500, e.getMessage());
        }
    }

    private static OpenAiResponse<Embedding> readEmbeddingResponse(ObjectMapper objectMapper,
        HttpResponse<InputStream> httpResponse) throws IOException
    {
        try {
            return objectMapper.readValue(httpResponse.body(),
                new TypeReference<OpenAiResponse<Embedding>>()
                {
                });
        } catch (IOException e) {
            // If parsing the response failed, it is possible that in fact, it was an error response.
            // Try reading the error response and throw an exception with the error.
            OpenAiError error = objectMapper.readValue(httpResponse.body(), OpenAiError.class);
            throw new IOException(error.error.getMessage());
        }
    }
}
