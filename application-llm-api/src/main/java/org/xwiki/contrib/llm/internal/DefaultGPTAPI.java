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
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.web.Utils;

import liquibase.pro.packaged.is;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.contrib.llm.GPTAPI;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.stability.Unstable;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
@Unstable
@Singleton
public class DefaultGPTAPI implements GPTAPI {
    @Inject
    protected Logger logger;

    @Inject
    GPTAPIConfigProvider configProvider;

    @Override
    public String getLLMChatCompletion(Map<String, Object> data, String openAIKey) throws GPTAPIException {
        try {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                logger.info("key: " + entry.getKey() + "; value: " + entry.getValue());
            }
            if (data.get("text") == null || data.get("modelType") == null || data.get("model") == null
                    || data.get("prompt") == null) {
                logger.info("Invalid error data");
                throw new GPTAPIException("Invalid input data");
            }
            logger.info("Received text: " + data.get("text"));
            logger.info("Received modelType: " + data.get("modelType"));
            logger.info("Received model: " + data.get("model"));
            logger.info("Received mode: " + data.get("stream"));

            boolean isStreaming = (Objects.equals(data.get("stream").toString(), "streamMode"));
            // Create an instance of HttpClient.
            HttpClient client = new HttpClient();

            String url = "https://llmapi.ai.devxwiki.com/v1/chat/completions";

            // Create a method instance.
            if (data.get("modelType").equals("openai")) {
                url = "https://api.openai.com/v1/chat/completions";
            }
            logger.info("Calling url: " + url);
            PostMethod post = new PostMethod(url);

            // Set headers
            post.setRequestHeader("Content-Type", "application/json");
            post.setRequestHeader("Accept", "application/json");

            if (data.get("modelType").equals("openai")) {
                post.setRequestHeader("Authorization", "Bearer " + openAIKey);
            }

            // Construct the messages array
            JSONArray messagesArray = new JSONArray();
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
            jsonInput.put("model", data.get("model"));
            jsonInput.put("stream", isStreaming);
            jsonInput.put("messages", messagesArray);

            String jsonInputString = jsonInput.toString();
            // JSONObject builder = new JSONObject(jsonInputString);
            logger.info("Sending: " + jsonInputString);

            StringRequestEntity requestEntity = new StringRequestEntity(
                    jsonInputString,
                    "application/json",
                    "UTF-8");
            post.setRequestEntity(requestEntity);

            // Execute the method.
            int statusCode = client.executeMethod(post);
            if (!isStreaming) {
                // Read the response body.
                byte[] responseBody = post.getResponseBody();
                if (statusCode != HttpStatus.SC_OK) {
                    logger.error("Method failed: " + post.getStatusLine());
                    JSONObject resErr = new JSONObject(new String(responseBody));
                    JSONObject resErrMsg = (JSONObject) resErr.get("error");
                    throw new GPTAPIException("Connection to requested server failed: " + post.getStatusLine() + ": "
                            + resErrMsg.getString("message"));
                }

                // Deal with the response.
                // Use caution: ensure correct character encoding and is not binary data
                logger.info("response body" + new String(responseBody));

                // Return the response as a JSON string
                return new String(responseBody);
            } else {
                // Read the response body.
                InputStream responseBody = post.getResponseBodyAsStream();
                StreamingOutput stream = new StreamingOutput() {
                    final BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody, StandardCharsets.UTF_8));
                    String line;

                    @Override
                    public void write(OutputStream outputStream) throws IOException {
                        logger.info("Writing in output Stream..");
                        OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                        while ((line = reader.readLine()) != null) {
                            // Write each line to the output
                            logger.info("stream response line: " + line);
                            writer.write(line);
                            writer.flush();
                        }
                    }
                };
                return stream.toString();
            }
        } catch (Exception e) {
            logger.error("Error processing request: " + e);
            return "Error processing request: " + e;

        }
    }

    @Override
    public String getModels(String token) throws GPTAPIException {
        try {
            // Create an instance of HttpClient.
            HttpClient client = new HttpClient();

            String url = "";
            String finalResponseBody = "";
            for (int i = 0; i < 2; i++) {
                if (i == 0)
                    url = "https://api.openai.com/v1/models";
                else if (i == 1) {
                    url = "https://llmapi.ai.devxwiki.com/v1/models";
                }
                // Create a method instance.
                GetMethod get = new GetMethod(url);
                // Set headers
                get.setRequestHeader("Content-Type", "application/json");
                get.setRequestHeader("Accept", "application/json");
                // Provide your token here if required
                get.setRequestHeader("Authorization", "Bearer " + token);

                // Execute the method.
                int statusCode = client.executeMethod(get);
                if (statusCode != HttpStatus.SC_OK) {
                    logger.error("Method failed: " + get.getStatusLine());
                    throw new XWikiRestException(get.getStatusLine().toString() + get.getStatusText(), null);
                }

                // Read the response body.
                byte[] responseBody = get.getResponseBody();
                get.releaseConnection();
                // Deal with the response.
                // Use caution: ensure correct character encoding and is not binary data
                logger.info("response body" + new String(responseBody));
                String responseBodyString = new String(responseBody);
                finalResponseBody += responseBodyString;
            }
            // Return the response as a JSON string
            return finalResponseBody;

        } catch (Exception e) {
            logger.error("Error processing request: " + e);
            JSONObject builder = new JSONObject();
            builder.put("", e.getMessage());
            return builder.toString();
        }
    }

    @Override
    public GPTAPIConfig getConfig(String id) throws GPTAPIException {
        try {
            Map<String, GPTAPIConfig> configMap = configProvider.getConfigObjects();
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
    public Map<String, GPTAPIConfig> getConfigs() throws GPTAPIException {
        try{
            Map<String, GPTAPIConfig> configMap = configProvider.getConfigObjects();
            return configMap;
        } catch(Exception e){
            logger.error("Error trying to get the configurations java object: ", e);
            return null;
        }
    }
}