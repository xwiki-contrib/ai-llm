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
import java.net.http.HttpResponse;
import java.util.List;

import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.EmbeddingModel;
import org.xwiki.contrib.llm.EmbeddingModelDescriptor;
import org.xwiki.contrib.llm.RequestError;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiError;
import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.embedding.Embedding;
import com.theokanning.openai.embedding.EmbeddingRequest;

/**
 * Implementation of {@link EmbeddingModel} that uses the OpenAI API.
 *
 * @version $Id$
 * @since 0.3
 */
public class OpenAIEmbeddingModel extends AbstractModel implements EmbeddingModel
{
    private final RequestHelper requestHelper;

    /**
     * Constructor.
     *
     * @param config the model configuration
     * @param componentManager the component manager
     * @throws ComponentLookupException if a component cannot be found
     */
    public OpenAIEmbeddingModel(ModelConfiguration config, ComponentManager componentManager)
        throws ComponentLookupException
    {
        super(config, componentManager);
        this.requestHelper = componentManager.getInstance(RequestHelper.class);
    }

    @Override
    public double[] embed(String text) throws RequestError
    {
        return embed(List.of(text)).get(0);
    }

    @Override
    public List<double[]> embed(List<String> texts) throws RequestError
    {
        EmbeddingRequest request = new EmbeddingRequest(this.modelConfiguration.getModel(), texts, null);

        try {
            HttpResponse<InputStream> httpResponse =
                this.requestHelper.post(getConfig(), "embeddings", request, HttpResponse.BodyHandlers.ofInputStream());
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

    @Override
    public EmbeddingModelDescriptor getDescriptor()
    {
        return new EmbeddingModelDescriptor(getRoleHint(), this.modelConfiguration.getName(),
            this.modelConfiguration.getDimensions());
    }

    @Override
    public Type getRoleType()
    {
        return EmbeddingModel.class;
    }
}
