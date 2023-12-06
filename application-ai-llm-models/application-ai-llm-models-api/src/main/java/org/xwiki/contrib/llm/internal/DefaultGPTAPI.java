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

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.api.User;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPI;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.contrib.llm.GPTAPIPrompt;
import org.xwiki.contrib.llm.GPTAPIPromptDBProvider;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.stability.Unstable;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.UserReference;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Default implementation of the {@link GPTAPI} interface.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Unstable
@Singleton
public class DefaultGPTAPI implements GPTAPI 
{
    private static final String ERROR_OCCURRED = "An error occurred: ";

    private static final String MODEL = "model";

    private static final String MODEL_TYPE = "modelType";

    private static final String DEFAULT = "default";

    private static final String JSON_CONTENT_TYPE = "application/json";

    private static final String METHOD_FAILED = "Method failed: {}";

    private static final String CURRENT_WIKI = "currentWiki";

    private static final String MODEL_CONFIGURATION_NOT_FOUND_ERROR =
        "There is no configuration available for this model, please be sure that your configuration exist "
            + "and is valid.";

    private static final String CONTENT_TYPE = "Content-Type";

    private static final String ACCEPT = "Accept";

    private static final String AUTHORIZATION = "Authorization";

    private static final String BEARER = "Bearer ";

    private static final String TEMPERATURE = "temperature";

    private static final String ROLE = "role";

    private static final String CONTENT = "content";

    private static final String PROMPT = "prompt";

    @Inject
    private Logger logger;

    @Inject
    private GPTAPIConfigProvider configProvider;

    @Inject
    private GPTAPIPromptDBProvider dbProvider;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public String getLLMChatCompletion(Map<String, Object> data) throws GPTAPIException
    {
        try {

            PostMethod post = requestBuilder(data);
            // Execute the method.
            HttpClient client = new HttpClient();
            int statusCode = client.executeMethod(post);
            if (statusCode != HttpStatus.SC_OK) {
                logger.error(METHOD_FAILED, post.getStatusLine());
                // throw new XWikiRestException(post.getStatusLine().toString() +
                // post.getStatusText(), null);
            }
            // Read the response body.
            byte[] responseBody = post.getResponseBody();

            // Deal with the response.
            // Use caution: ensure correct character encoding and is not binary data
            logger.info("response body" + new String(responseBody));

            // Return the response as a JSON string
            return new String(responseBody);

        } catch (Exception e) {
            logger.error("Error processing request: ", e);
            JSONObject builder = new JSONObject();
            JSONObject root = new JSONObject();
            root.put("error", "An error occured. " + e.getMessage());
            builder.put("", root);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(builder.toString())
                    .type(MediaType.APPLICATION_JSON).build().toString();
        }
    }

    @Override
    public StreamingOutput getLLMChatCompletionAsStream(Map<String, Object> data) throws GPTAPIException
    {
        try {
            PostMethod post = requestBuilder(data);
            // Execute the method.
            HttpClient client = new HttpClient();
            int statusCode = client.executeMethod(post);
            if (statusCode != HttpStatus.SC_OK) {
                logger.error(METHOD_FAILED, post.getStatusLine());
                // throw new XWikiRestException(post.getStatusLine().toString() +
                // post.getStatusText(), null);
            }
            InputStream streamResp = post.getResponseBodyAsStream();
            return new StreamingOutput() {
                private final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(streamResp, StandardCharsets.UTF_8));

                @Override
                public void write(OutputStream outputStream) throws IOException
                {
                    logger.info("Writing in output Stream..");
                    OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Write each line to the output
                        writer.write(line);
                        writer.flush();
                    }
                }
            };
        } catch (Exception e) {
            logger.error(ERROR_OCCURRED, e);
            return null;
        }

    }

    @Override
    public PostMethod requestBuilder(Map<String, Object> data) throws GPTAPIException
    {
        try {
            String model = data.get(MODEL) != null ? (String) data.get(MODEL) : "";
            String modelType = data.get(MODEL_TYPE) != null ? (String) data.get(MODEL_TYPE) : DEFAULT;
            GPTAPIConfig config = getConfig(modelType, (String) data.get(CURRENT_WIKI), CurrentUserReference.INSTANCE);
            if (Objects.equals(config.getName(), DEFAULT)) {
                throw new GPTAPIException(MODEL_CONFIGURATION_NOT_FOUND_ERROR);
            }
            boolean isStreaming = config.getCanStream();
            logger.info("is streaming : " + isStreaming);
            logger.info("config : " + modelType);

            // Create an instance of HttpClient.

            String url = config.getURL() + "chat/completions";
            // Create a method instance.
            logger.info("Calling url: " + url);
            PostMethod post = new PostMethod(url);

            // Set headers
            post.setRequestHeader(CONTENT_TYPE, JSON_CONTENT_TYPE);
            post.setRequestHeader(ACCEPT, JSON_CONTENT_TYPE);

            if (StringUtils.isNotBlank(config.getToken())) {
                post.setRequestHeader(AUTHORIZATION, BEARER + config.getToken());
            }

            // Construct the JSON input string
            JSONObject jsonInput = new JSONObject();
            jsonInput.put(MODEL, model);
            if (isStreaming) {
                jsonInput.put("stream", isStreaming);
            }
            jsonInput.put(TEMPERATURE, data.get(TEMPERATURE));
            jsonInput.put("messages", buildMessagesJSONArray(data));
            String jsonInputString = jsonInput.toString();
            logger.info("Sending: " + jsonInputString);

            StringRequestEntity requestEntity = new StringRequestEntity(jsonInputString, JSON_CONTENT_TYPE, "UTF-8");

            post.setRequestEntity(requestEntity);

            return post;

        } catch (Exception e) {
            logger.error(ERROR_OCCURRED, e);
            return null;
        }
    }

    private static JSONArray buildMessagesJSONArray(Map<String, Object> data)
    {
        JSONArray messagesArray = new JSONArray();
        for (Map<String, String> map : (List<Map<String, String>>) data.get("context")) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                JSONObject contextElement = new JSONObject();
                contextElement.put(ROLE, entry.getKey());
                contextElement.put(CONTENT, entry.getValue());
                messagesArray.put(contextElement);
            }
        }
        JSONObject systemMessage = new JSONObject();
        systemMessage.put(ROLE, "system");
        systemMessage.put(CONTENT, data.get(PROMPT).toString());
        messagesArray.put(systemMessage);

        JSONObject userMessage = new JSONObject();
        userMessage.put(ROLE, "user");
        userMessage.put(CONTENT, data.get("text").toString());
        messagesArray.put(userMessage);
        return messagesArray;
    }

    @Override
    public String getModels(Map<String, Object> data) throws GPTAPIException
    {
        Map<String, GPTAPIConfig> configMap;
        try {
            configMap = configProvider.getConfigObjects(data.get(CURRENT_WIKI).toString(),
                CurrentUserReference.INSTANCE);
        } catch (GPTAPIException e) {
            logger.error("Error in getModels REST method: ", e);
            configMap = Collections.emptyMap();
        }
        JSONArray finalResponse = new JSONArray();
        for (Map.Entry<String, GPTAPIConfig> entry : configMap.entrySet()) {
            String models = entry.getValue().getConfigModels();
            JSONObject responseBodyJson;
            if (StringUtils.isBlank(models)) {
                // Make an API request to get the models if the config doesn't have any and thus all models shall be
                // exposed.
                responseBodyJson = requestModels(entry.getValue());
            } else {
                // Use the configured models without making an API request.
                // Split models string into an array.
                String[] modelsArray = StringUtils.split(models, ", ");
                // Create a list of models where each model is a map with an id property.
                JSONArray modelsList = new JSONArray();
                for (String model : modelsArray) {
                    JSONObject modelObj = new JSONObject();
                    modelObj.put("id", model);
                    modelsList.put(modelObj);
                }

                responseBodyJson = new JSONObject();
                responseBodyJson.put("data", modelsList);
            }
            if (responseBodyJson != null) {
                responseBodyJson.put("prefix", entry.getValue().getName());
                responseBodyJson.put("filter", models);
                responseBodyJson.put("canStream", entry.getValue().getCanStream());
                finalResponse.put(responseBodyJson);
            }
        }
        return finalResponse.toString();
    }

    private JSONObject requestModels(GPTAPIConfig config)
    {
        JSONObject result = null;

        try {
            HttpClient client = new HttpClient();
            String url = config.getURL() + "models";
            logger.info("calling url : " + url);
            GetMethod get = new GetMethod(url);
            get.setRequestHeader(CONTENT_TYPE, JSON_CONTENT_TYPE);
            get.setRequestHeader(ACCEPT, JSON_CONTENT_TYPE);
            get.setRequestHeader(AUTHORIZATION, BEARER + config.getToken());
            // setting the timeouts
            get.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 10000);
            client.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
            int statusCode = client.executeMethod(get);
            if (statusCode != HttpStatus.SC_OK) {
                logger.error(METHOD_FAILED, get.getStatusLine());
                throw new XWikiRestException(get.getStatusLine().toString() + get.getStatusText(), null);
            }
            byte[] responseBody = get.getResponseBody();
            get.releaseConnection();
            result = new JSONObject(new String(responseBody, StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.error("An error occured on one of the requested URI: ", e);
        }
        return result;
    }

    @Override
    public GPTAPIConfig getConfig(String id, String currentWiki, UserReference userReference) throws GPTAPIException
    {
        try {
            Map<String, GPTAPIConfig> configMap = configProvider.getConfigObjects(currentWiki, userReference);
            GPTAPIConfig res = configMap.get(id);
            if (res == null) {
                throw new Exception(
                    MODEL_CONFIGURATION_NOT_FOUND_ERROR);
            }
            return res;
        } catch (Exception e) {
            logger.error("Error trying to get specific configuration parameters: ", e);
            return new GPTAPIConfig();
        }
    }

    @Override
    public String getPrompt(Map<String, Object> data) throws GPTAPIException
    {
        GPTAPIPrompt promptObj = new GPTAPIPrompt();
        try {
            promptObj = dbProvider.getPrompt(data.get(PROMPT).toString(), data.get(CURRENT_WIKI).toString());
        } catch (Exception e) {
            logger.error("Exception in the REST getPrompt method : ", e);
        }
        try {
            return convertPromptToJSONObject(promptObj).toString();
        } catch (Exception e) {
            logger.error(ERROR_OCCURRED, e);
            return null;
        }
    }

    @Override
    public String getPrompts(Map<String, Object> data) throws GPTAPIException
    {
        Map<String, GPTAPIPrompt> dbMap;
        try {
            dbMap = dbProvider.getPrompts(data.get(CURRENT_WIKI).toString());
        } catch (Exception e) {
            logger.error("Exception in the REST getPromptDB method : ", e);
            dbMap = new HashMap<>();
        }
        JSONArray finalResponse = new JSONArray();
        try {
            for (Map.Entry<String, GPTAPIPrompt> entryDB : dbMap.entrySet()) {
                GPTAPIPrompt promptObj = entryDB.getValue();
                if (!entryDB.getKey().isEmpty()) {
                    finalResponse.put(convertPromptToJSONObject(promptObj));
                }
            }
            return finalResponse.toString();
        } catch (Exception e) {
            logger.error(ERROR_OCCURRED, e);
            return null;
        }
        
    }

    private static JSONObject convertPromptToJSONObject(GPTAPIPrompt promptObj)
    {
        JSONObject jsonEntry = new JSONObject();
        jsonEntry.put("name", promptObj.getName());
        jsonEntry.put(PROMPT, promptObj.getPrompt());
        jsonEntry.put("userPrompt", promptObj.getUserPrompt());
        jsonEntry.put("description", promptObj.getDescription());
        jsonEntry.put("active", promptObj.getIsActive());
        jsonEntry.put(DEFAULT, promptObj.getIsDefault());
        jsonEntry.put(TEMPERATURE, promptObj.getTemperature());
        jsonEntry.put("pageName", promptObj.getXWikiPageName());
        return jsonEntry;
    }

    @Override
    public Boolean isUserAdmin(String currentWiki) throws GPTAPIException
    {
        XWikiContext context = contextProvider.get();
        String mainWiki = context.getWikiId();
        com.xpn.xwiki.XWiki xwiki = context.getWiki();
        context.setWikiId(currentWiki);

        // Get the user using the Extension in the actual context.
        DocumentReference username = context.getUserReference();
        logger.info("user in isUserAdmin: " + username.getName());
        logger.info("user wiki : " + username.getWikiReference().getName());
        User xwikiUser = xwiki.getUser(username, context);
        Boolean res = xwikiUser.hasWikiAdminRights();
        context.setWikiId(mainWiki);
        return res;
    }

    @Override
    public Boolean checkAllowance(Map<String, Object> data) throws GPTAPIException
    {
        Map<String, GPTAPIConfig> configMap =
            configProvider.getConfigObjects((String) data.get(CURRENT_WIKI), CurrentUserReference.INSTANCE);
        return !configMap.isEmpty();
    }
}
