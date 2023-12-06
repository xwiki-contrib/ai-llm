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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.user.CurrentUserReference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.model.Model;
import com.xpn.xwiki.XWikiContext;

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

    private static final String BEARER = "Bearer ";

    private static final String MODEL_SEPARATOR = "/";

    private static final String MODEL_CONFIG_SEPARATOR = ", ";

    @Inject
    private GPTAPIConfigProvider configProvider;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    private Provider<OpenAIChatModel> chatModelProvider;

    @Override
    public ChatModel getModel(String name) throws GPTAPIException
    {
        XWikiContext xcontext = xcontextProvider.get();
        Map<String, GPTAPIConfig> configMap =
            configProvider.getConfigObjects(xcontext.getWikiId(), CurrentUserReference.INSTANCE);
        String[] parts = StringUtils.split(name, MODEL_SEPARATOR, 2);
        if (parts.length != 2 || StringUtils.isBlank(parts[0])) {
            throw new GPTAPIException("Invalid model name [" + name + "]");
        }

        GPTAPIConfig config = configMap.get(parts[0]);
        if (config == null) {
            throw new GPTAPIException("No config found for [" + parts[0] + "]");
        }

        // Only check if the model is configured if the config has models configured, otherwise assume it is valid.
        if (StringUtils.isNotBlank(config.getConfigModels())) {
            // If the config has models configured, check if the requested model is configured.
            List<String> models = Arrays.asList(StringUtils.split(config.getConfigModels(), MODEL_CONFIG_SEPARATOR));
            if (!models.contains(parts[1])) {
                throw new GPTAPIException("Model [" + parts[1] + "] is not configured for [" + parts[0] + "]");
            }
        }

        OpenAIChatModel chatModel = chatModelProvider.get();
        chatModel.initialize(config, parts[1]);
        return chatModel;
    }

    @Override
    public List<String> getModels() throws GPTAPIException
    {
        XWikiContext xcontext = xcontextProvider.get();
        Map<String, GPTAPIConfig> configMap =
            configProvider.getConfigObjects(xcontext.getWikiId(), CurrentUserReference.INSTANCE);
        List<String> result = new ArrayList<>();

        for (Map.Entry<String, GPTAPIConfig> entry : configMap.entrySet()) {
            String models = entry.getValue().getConfigModels();
            if (StringUtils.isBlank(models)) {
                // Make an API request to get the models if the config doesn't have any and thus all models shall be
                // exposed.
                result.addAll(requestModels(entry.getValue()).stream()
                    .map(model -> entry.getKey() + MODEL_SEPARATOR + model)
                    .collect(Collectors.toList()));
            } else {
                // Use the configured models without making an API request.
                // Split model string into an array.
                result.addAll(Arrays.stream(StringUtils.split(models, MODEL_CONFIG_SEPARATOR))
                    .map(name -> entry.getKey() + MODEL_SEPARATOR + name)
                    .collect(Collectors.toList()));
            }
        }

        return result;
    }

    private List<String> requestModels(GPTAPIConfig config) throws GPTAPIException
    {
        try (CloseableHttpClient httpClient = HttpClients.createSystem()) {
            HttpGet httpGet = new HttpGet(config.getURL() + "/models");
            httpGet.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON);
            httpGet.setHeader(HttpHeaders.AUTHORIZATION, BEARER + config.getToken());

            return httpClient.execute(httpGet, response -> {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    InputStream inputStream = entity.getContent();
                    ObjectMapper objectMapper = new ObjectMapper();
                    OpenAiResponse<Model> modelOpenAiResponse =
                        objectMapper.readValue(inputStream, new TypeReference<OpenAiResponse<Model>>() { });
                    return modelOpenAiResponse.getData().stream()
                        .map(Model::getId)
                        .collect(Collectors.toList());
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
