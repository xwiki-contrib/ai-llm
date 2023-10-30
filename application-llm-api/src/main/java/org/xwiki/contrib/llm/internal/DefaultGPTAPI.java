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

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

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

@Component
@Unstable
@Singleton
public class DefaultGPTAPI implements GPTAPI 
{
    @Inject
    protected Logger logger;

    @Inject
    protected GPTAPIConfigProvider configProvider;

    @Inject
    protected GPTAPIPromptDBProvider dbProvider;

    @Inject
    Provider<XWikiContext> contextProvider;

    @Override
    public String getLLMChatCompletion(Map<String, Object> data) throws GPTAPIException {
        try {

            PostMethod post = requestBuilder(data);
            // Execute the method.
            HttpClient client = new HttpClient();
            int statusCode = client.executeMethod(post);
            if (statusCode != HttpStatus.SC_OK) {
                logger.error("Method failed: " + post.getStatusLine());
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
            logger.error("Error processing request: " + e);
            JSONObject builder = new JSONObject();
            JSONObject root = new JSONObject();
            root.put("error", "An error occured. " + e.getMessage());
            builder.put("", root);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(builder.toString())
                    .type(MediaType.APPLICATION_JSON).build().toString();
        }
    }

    @Override
    public StreamingOutput getLLMChatCompletionAsStream(Map<String, Object> data) throws GPTAPIException {
        try {
            PostMethod post = requestBuilder(data);
            // Execute the method.
            HttpClient client = new HttpClient();
            int statusCode = client.executeMethod(post);
            if (statusCode != HttpStatus.SC_OK) {
                logger.error("Method failed: " + post.getStatusLine());
                // throw new XWikiRestException(post.getStatusLine().toString() +
                // post.getStatusText(), null);
            }
            InputStream streamResp = post.getResponseBodyAsStream();
            StreamingOutput stream = new StreamingOutput() {
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(streamResp, StandardCharsets.UTF_8));
                String line;

                @Override
                public void write(OutputStream outputStream) throws IOException {
                    logger.info("Writing in output Stream..");
                    OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                    while ((line = reader.readLine()) != null) {
                        // Write each line to the output
                        writer.write(line);
                        writer.flush();
                    }
                }
            };
            return stream;
        } catch (Exception e) {
            logger.error("An error occured: ", e);
            return null;
        }

    }

    @Override
    public PostMethod requestBuilder(Map<String, Object> data) throws GPTAPIException {
        try {
            String modelInfoString = data.get("modelType") + "/" + data.get("model");
            logger.info(modelInfoString);
            String model = "";
            String modelType = "";
            if (modelInfoString.contains("/")) {
                String[] modelInfo = modelInfoString.split("/", 2);
                modelType = modelInfo[0];
                model = modelInfo[1];
                logger.info("model : ", model);
                logger.info("modelType : ", modelType);
            } else {
                model = modelInfoString;
                modelType = "default";
            }
            logger.info("modelType after evaluation :", modelType);
            logger.info("Received text: " + data.get("text"));
            GPTAPIConfig config = getConfig(modelType, (String) data.get("currentWiki"), (String) data.get("userName"));
            if (config.getName() == "default") {
                throw new GPTAPIException(
                        "There is no configuration available for this model, please be sure that your configuration exist and is valid.");
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
            post.setRequestHeader("Content-Type", "application/json");
            post.setRequestHeader("Accept", "application/json");

            if (config.getToken() != "") {
                post.setRequestHeader("Authorization", "Bearer " + config.getToken());
            }

            JSONArray messagesArray = new JSONArray();
            List<Map<String, String>> listObjs = (List<Map<String, String>>) data.get("context");
            for (Map<String, String> map : listObjs) {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    logger.info("Key: " + key + ", Value: " + value);
                    JSONObject contextElement = new JSONObject();
                    contextElement.put("role", key);
                    contextElement.put("content", value);
                    messagesArray.put(contextElement);
                }
            }
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", data.get("prompt").toString());
            messagesArray.put(systemMessage);

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", data.get("text").toString());
            messagesArray.put(userMessage);

            // Construct the JSON input string
            JSONObject jsonInput = new JSONObject();
            jsonInput.put("model", model);
            if (isStreaming)
                jsonInput.put("stream", isStreaming);
            jsonInput.put("temperature", data.get("temperature"));
            jsonInput.put("messages", messagesArray);
            String jsonInputString = jsonInput.toString();
            logger.info("Sending: " + jsonInputString);

            StringRequestEntity requestEntity = new StringRequestEntity(jsonInputString, "application/json", "UTF-8");

            post.setRequestEntity(requestEntity);

            return post;

        } catch (Exception e) {
            logger.error("An error occured: " + e);
            return null;
        }
    }

    @Override
    public String getModels(Map<String, Object> data) throws GPTAPIException {
        Map<String, GPTAPIConfig> configMap;
        try {
            configMap = configProvider.getConfigObjects(data.get("currentWiki").toString(),
                    data.get("userName").toString());
        } catch (GPTAPIException e) {
            logger.error("Error in getModels REST method: ", e);
            configMap = new HashMap<>();
        }
        JSONArray finalResponse = new JSONArray();
        try {
            if (configMap.isEmpty())
                throw new Exception("The configurations object is empty.");
            for (Map.Entry<String, GPTAPIConfig> entry : configMap.entrySet()) {
                try {
                    HttpClient client = new HttpClient();
                    String url = entry.getValue().getURL() + "models";
                    logger.info("calling url : " + url);
                    GetMethod get = new GetMethod(url);
                    get.setRequestHeader("Content-Type", "application/json");
                    get.setRequestHeader("Accept", "application/json");
                    get.setRequestHeader("Authorization", "Bearer " + entry.getValue().getToken());
                    // setting the timeouts
                    get.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 10000);
                    client.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
                    int statusCode = client.executeMethod(get);
                    if (statusCode != HttpStatus.SC_OK) {
                        logger.error("Method failed: " + get.getStatusLine());
                        throw new XWikiRestException(get.getStatusLine().toString() + get.getStatusText(), null);
                    }
                    byte[] responseBody = get.getResponseBody();
                    get.releaseConnection();
                    JSONObject responseBodyJson = new JSONObject(new String(responseBody, StandardCharsets.UTF_8));
                    responseBodyJson.put("prefix", entry.getValue().getName());
                    responseBodyJson.put("filter", entry.getValue().getConfigModels());
                    responseBodyJson.put("canStream", entry.getValue().getCanStream());
                    finalResponse.put(responseBodyJson);
                } catch (Exception e) {
                    logger.error("An error occured on one of the requested URI: ", e);
                }
            }
            return finalResponse.toString();
        } catch (Exception e) {
            logger.error("An error occured: ", e);
            return null;
        }
    }

    @Override
    public GPTAPIConfig getConfig(String id, String currentWiki, String userName) throws GPTAPIException {
        try {
            Map<String, GPTAPIConfig> configMap = configProvider.getConfigObjects(currentWiki, userName);
            GPTAPIConfig res = configMap.get(id);
            if (res == null) {
                throw new Exception(
                        "There is no configuration available for this model, please be sure that your configuration exist and is valid.");
            }
            return res;
        } catch (Exception e) {
            logger.error("Error trying to get specific configuration parameters: ", e);
            return new GPTAPIConfig();
        }
    }

    @Override
    public String getPrompt(Map<String, Object> data) throws GPTAPIException {
        GPTAPIPrompt promptObj = new GPTAPIPrompt();
        try {
            promptObj = dbProvider.getPrompt(data.get("prompt").toString(), data.get("currentWiki").toString());
        } catch (Exception e) {
            logger.error("Exception in the REST getPrompt method : ", e);
        }
        JSONObject jsonEntry = new JSONObject();
        try {
            jsonEntry.put("name", promptObj.getName());
            jsonEntry.put("prompt", promptObj.getPrompt());
            jsonEntry.put("userPrompt", promptObj.getUserPrompt());
            jsonEntry.put("description", promptObj.getDescription());
            jsonEntry.put("active", promptObj.getIsActive());
            jsonEntry.put("default", promptObj.getIsDefault());
            jsonEntry.put("temperature", promptObj.getTemperature());
            jsonEntry.put("pageName", promptObj.getXWikiPageName());
            return jsonEntry.toString();
        } catch (Exception e){
            logger.error("An error occured: ", e);
            return null;
        }
    }

    @Override
    public String getPrompts(Map<String, Object> data) throws GPTAPIException{
        Map<String, GPTAPIPrompt> dbMap;
        try {
            dbMap = dbProvider.getPrompts(data.get("currentWiki").toString());
        } catch (Exception e) {
            logger.error("Exception in the REST getPromptDB method : ", e);
            dbMap = new HashMap<>();
        }
        JSONArray finalResponse = new JSONArray();
        try {
            for (Map.Entry<String, GPTAPIPrompt> entryDB : dbMap.entrySet()) {
                GPTAPIPrompt promptObj = entryDB.getValue();
                if (entryDB.getKey().isEmpty() == false) {
                    JSONObject jsonEntry = new JSONObject();
                    jsonEntry.put("name", promptObj.getName());
                    jsonEntry.put("prompt", promptObj.getPrompt());
                    jsonEntry.put("userPrompt", promptObj.getUserPrompt());
                    jsonEntry.put("description", promptObj.getDescription());
                    jsonEntry.put("active", promptObj.getIsActive());
                    jsonEntry.put("default", promptObj.getIsDefault());
                    jsonEntry.put("temperature", promptObj.getTemperature());
                    jsonEntry.put("pageName", promptObj.getXWikiPageName());
                    finalResponse.put(jsonEntry);
                }
            }
            return finalResponse.toString();
        } catch (Exception e){
            logger.error("An error occured: ", e);
            return null;
        }
        
    }

    @Override
    public Boolean isUserAdmin(String currentWiki) throws GPTAPIException {
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
    public Boolean checkAllowance(Map<String, Object> data) throws GPTAPIException {
        Map<String, GPTAPIConfig> configMap;
        try {
            configMap = configProvider.getConfigObjects((String) data.get("currentWiki"),
                    (String) data.get("userName"));
            if (configMap.isEmpty())
                throw new GPTAPIException(
                        "The Configuration Map is empty. That mean the user has no right to access those configuration.");
        } catch (GPTAPIException e) {
            logger.error("An error occured:", e);
            configMap = new HashMap<>();
            return false;
        }
        return true;
    }
}
