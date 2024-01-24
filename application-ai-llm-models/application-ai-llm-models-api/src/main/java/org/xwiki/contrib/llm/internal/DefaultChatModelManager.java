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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpEntity;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelDescriptor;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.user.UserReference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiResponse;

/**
 * Default implementation of {@link ChatModelManager}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Singleton
public class DefaultChatModelManager implements ChatModelManager
{
    private static final String MODEL_SEPARATOR = "/";

    @Inject
    private GPTAPIConfigProvider configProvider;

    @Inject
    private Provider<OpenAIChatModel> chatModelProvider;

    @Inject
    private RequestHelper requestHelper;

    @Override
    public ChatModel getModel(String name, UserReference userReference, String wikiId) throws GPTAPIException
    {
        Map<String, GPTAPIConfig> configMap = this.configProvider.getConfigObjects(wikiId, userReference);
        String[] parts = StringUtils.split(name, MODEL_SEPARATOR, 2);
        if (parts.length != 2 || StringUtils.isBlank(parts[0])) {
            throw new GPTAPIException("Invalid model name [" + name + "]");
        }
    
        GPTAPIConfig config = configMap.get(parts[0]);
        if (config == null) {
            throw new GPTAPIException("No config found for [" + parts[0] + "]");
        }
    
        List<String> models = config.getLanguageModels();
        if (models != null && !models.contains(parts[1])) {
            throw new GPTAPIException("Model [" + parts[1] + "] is not configured for [" + parts[0] + "]");
        }
    
        OpenAIChatModel chatModel = chatModelProvider.get();
        chatModel.initialize(config, parts[1]);
        return chatModel;
    }

    @Override
    public List<ChatModelDescriptor> getModels(UserReference userReference, String wikiId) throws GPTAPIException
    {
        Map<String, GPTAPIConfig> configMap = this.configProvider.getConfigObjects(wikiId, userReference);
        List<ChatModelDescriptor> result = new ArrayList<>();
    
        for (Map.Entry<String, GPTAPIConfig> entry : configMap.entrySet()) {
            List<String> models = entry.getValue().getLanguageModels();
            if (models == null || models.isEmpty()) {
                // Make an API request to get the models if the config doesn't have any
                // and thus all models shall be exposed.
                for (ChatModelDescriptor chatModelDescriptor : requestModels(entry.getValue())) {
                    chatModelDescriptor.setId(entry.getKey() + MODEL_SEPARATOR + chatModelDescriptor.getId());
                    result.add(chatModelDescriptor);
                }
            } else {
                // Use the configured models without making an API request.
                result.addAll(models.stream()
                    .map(name -> new ChatModelDescriptor(entry.getKey() + MODEL_SEPARATOR + name,
                        String.format("%s (%s)", name, entry.getKey()), 0))
                    .collect(Collectors.toList()));
            }
        }
    
        return result;
    }

    private List<ChatModelDescriptor> requestModels(GPTAPIConfig config) throws GPTAPIException
    {
        try {
            return this.requestHelper.get(config, "/models", response -> {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    InputStream inputStream = entity.getContent();
                    ObjectMapper objectMapper = new ObjectMapper();
                    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    OpenAiResponse<ChatModelDescriptor> modelOpenAiResponse = objectMapper
                        .readValue(inputStream, new TypeReference<OpenAiResponse<ChatModelDescriptor>>() { });
                    return modelOpenAiResponse.getData();
                } else {
                    throw new IOException("Response is empty.");
                }
            });
        } catch (Exception e) {
            throw new GPTAPIException("Failed to retrieve models from OpenAI API.", e);
        }
/* Using JAX-RS 2.0 APIs...
        Client client = ClientBuilder.newClient();
        Invocation.Builder models = client.target(config.getURL())
            .path("models")
            .request(MediaType.APPLICATION_JSON)
            .header(AUTHORIZATION, BEARER + config.getToken());

        GenericType<OpenAiResponse<Model>> modelResponse = new GenericType<>()
        {
        };
        // FIXME this is a workaround until we can use JAX-RS 2.0


        try {
            // TODO: change this to use jax-rs 2.0 where we can get a response object and then read different types
            //  of entities depending on the success/error.
            OpenAiResponse<Model> modelOpenAiResponse = models.get(modelResponse);
            return modelOpenAiResponse.getData().stream()
                .map(Model::getId)
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new GPTAPIException("Failed to retrieve models from OpenAI API.", e);
        }
 */
    }

}
