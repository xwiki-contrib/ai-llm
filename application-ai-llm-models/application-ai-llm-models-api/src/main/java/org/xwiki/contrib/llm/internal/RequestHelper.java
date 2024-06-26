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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.FailableConsumer;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIConfig;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Helper component for HTTP requests.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = RequestHelper.class)
@Singleton
public class RequestHelper
{
    private static final String BEARER = "Bearer ";

    private static final String DATA_PREFIX = "data: ";

    private static final String APPLICATION_JSON = "application/json";

    @Inject
    private HttpClientFactory httpClientFactory;

    /**
     * Perform a POST request.
     *
     * @param config the configuration that provides the URL and the authentication token
     * @param path the path of the API endpoint
     * @param body the object to send in the body of the request
     * @param bodyHandler the handler that processes the response body
     * @return the value returned by the body handler
     * @param <T> the type of the body
     * @param <R> the return type
     * @throws IOException if the request fails
     */
    public <T, R> HttpResponse<R> post(GPTAPIConfig config, String path, T body,
        HttpResponse.BodyHandler<R> bodyHandler)
        throws IOException
    {
        HttpClient httpClient = this.httpClientFactory.createHttpClient();
        HttpRequest request = prepareRequest(path, config, body);

        try {
            return httpClient.send(request, bodyHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /**
     * Perform a GET request.
     *
     * @param config the configuration that provides the URL and the authentication token
     * @param path the path of the API endpoint
     * @param bodyHandler the handler that processes the response body
     * @return the value returned by the response handler
     * @param <R> the type of the body
     * @throws IOException if the request fails
     */
    public <R> HttpResponse<R> get(GPTAPIConfig config, String path,
        HttpResponse.BodyHandler<R> bodyHandler) throws IOException
    {
        return post(config, path, null, bodyHandler);
    }

    /**
     * Read a Server-Sent Events (SSE) stream.
     *
     * @param inputStream the input stream to read
     * @param consumer the consumer that processes the data chunks of the stream
     * @throws IOException if the stream cannot be read
     */
    public void readSSEStream(InputStream inputStream, FailableConsumer<String, IOException> consumer)
        throws IOException
    {
        // Use a small buffer to avoid delaying the streaming output too much.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8),
            128)) {
            // Read the input stream line by line and group the lines into chunks.
            String line;
            StringBuilder chunk = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // Call the consumer with the chunk
                    consumer.accept(chunk.toString());
                    chunk.setLength(0);
                } else if (line.startsWith(DATA_PREFIX)) {
                    chunk.append(StringUtils.removeStart(line, DATA_PREFIX)).append('\n');
                }
            }

            // Call the consumer with the last chunk
            if (!chunk.isEmpty()) {
                consumer.accept(chunk.toString());
            }
        }
    }

    private <T> HttpRequest prepareRequest(String path, GPTAPIConfig config, T body) throws IOException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(config.getURL() + path))
            .header("Authorization", BEARER + config.getToken())
            .header("Accept", APPLICATION_JSON)
            .header("Content-Type", APPLICATION_JSON)
            .header("User-Agent", "XWiki AI LLM Application");

        if (body != null) {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            builder = builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        }

        return builder.build();
    }
}
