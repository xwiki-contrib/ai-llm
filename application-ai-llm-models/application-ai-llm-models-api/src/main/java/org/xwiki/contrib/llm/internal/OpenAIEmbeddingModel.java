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
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.hc.core5.http.HttpEntity;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.EmbeddingModel;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.RequestError;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.embedding.Embedding;
import com.theokanning.openai.embedding.EmbeddingRequest;

/**
 * Implementation of {@link EmbeddingModel} that uses the OpenAI API.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = EmbeddingModel.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class OpenAIEmbeddingModel implements EmbeddingModel
{
    @Inject
    private RequestHelper requestHelper;

    private String id;

    private GPTAPIConfig config;

    /**
     * Initialize the model.
     *
     * @param id the id of the model
     * @param config the configuration object for this model
     */
    public void initialize(String id, GPTAPIConfig config)
    {
        this.id = id;
        this.config = config;
    }

    @Override
    public double[] embed(String text) throws RequestError
    {
        return embed(List.of(text)).get(0);
    }

    @Override
    public List<double[]> embed(List<String> texts) throws RequestError
    {
        EmbeddingRequest request = new EmbeddingRequest(this.id, texts, null);

        try {
            return this.requestHelper.post(this.config, "/embeddings", request, response -> {
                if (response.getCode() != 200) {
                    throw new IOException("Response code is " + response.getCode());
                }

                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    throw new IOException("No response body");
                }

                ObjectMapper objectMapper = new ObjectMapper();
                OpenAiResponse<Embedding> openAiResponse = objectMapper.readValue(entity.getContent(),
                    new TypeReference<OpenAiResponse<Embedding>>() { });

                if (openAiResponse.data != null) {
                    return openAiResponse.data.stream()
                        .map(Embedding::getEmbedding)
                        .map(list -> list.stream().mapToDouble(Double::doubleValue).toArray())
                        .collect(Collectors.toList());
                } else {
                    throw new IOException("Response data is null");
                }
            });
        } catch (IOException e) {
            throw new RequestError(500, e.getMessage());
        }
    }
}
