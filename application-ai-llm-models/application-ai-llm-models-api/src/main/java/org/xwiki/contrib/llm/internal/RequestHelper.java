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

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
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

    @Inject
    private HttpClientFactory httpClientFactory;

    /**
     * Perform a POST request.
     *
     * @param config the configuration that provides the URL and the authentication token
     * @param path the path of the API endpoint
     * @param body the object to send in the body of the request
     * @param responseHandler the callback that handles the response
     * @return the value returned by the response handler
     * @param <T> the type of the body
     * @param <R> the return type
     * @throws IOException if the request fails
     */
    public <T, R> R post(GPTAPIConfig config, String path, T body,
        HttpClientResponseHandler<? extends R> responseHandler) throws IOException
    {
        try (CloseableHttpClient httpClient = this.httpClientFactory.createHttpClient()) {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            HttpPost httpPost = new HttpPost(config.getURL() + path);
            httpPost.setHeader(HttpHeaders.AUTHORIZATION, BEARER + config.getToken());
            httpPost.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON);
            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON);
            httpPost.setEntity(new StringEntity(objectMapper.writeValueAsString(body)));

            return httpClient.execute(httpPost, responseHandler);
        }
    }
}
