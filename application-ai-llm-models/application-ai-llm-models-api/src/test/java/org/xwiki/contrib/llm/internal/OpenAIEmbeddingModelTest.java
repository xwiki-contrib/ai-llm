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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ComponentTest
@ComponentList({ RequestHelper.class })
class OpenAIEmbeddingModelTest
{

    private static final String INPUT = "XWiki is awesome";

    private static final String TOKEN = "token";

    private static final String URL = "https://api.openai.com/v1/";

    private static final String MODEL = "text-embedding-ada-002";

    /**
     * Example response taken from
     * <a href="https://platform.openai.com/docs/api-reference/embeddings/create">the OpenAI documentation</a>.
     */
    private static final String EMBEDDING_RESPONSE = "{\n"
        + "  \"object\": \"list\",\n"
        + "  \"data\": [\n"
        + "    {\n"
        + "      \"object\": \"embedding\",\n"
        + "      \"embedding\": [\n"
        + "        0.0023064255,\n"
        + "        -0.009327292,\n"
        + "        -0.0028842222\n"
        + "      ],\n"
        + "      \"index\": 0\n"
        + "    }\n"
        + "  ],\n"
        + "  \"model\": \"text-embedding-ada-002\",\n"
        + "  \"usage\": {\n"
        + "    \"prompt_tokens\": 8,\n"
        + "    \"total_tokens\": 8\n"
        + "  }\n"
        + "}\n";

    @MockComponent
    private HttpClientFactory httpClientFactory;

    @Mock
    private CloseableHttpClient httpClient;

    @Mock
    private ClassicHttpResponse httpResponse;

    @Mock
    private GPTAPIConfig config;

    @InjectMockComponents
    private OpenAIEmbeddingModel openAIEmbeddingModel;

    @BeforeEach
    void setUp() throws IOException
    {
        when(this.httpClientFactory.createHttpClient()).thenReturn(this.httpClient);
        when(this.httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
            .thenAnswer(invocation -> {
                HttpClientResponseHandler<?> handler = invocation.getArgument(1);
                return handler.handleResponse(this.httpResponse);
            });
        when(this.config.getToken()).thenReturn(TOKEN);
        when(this.config.getURL()).thenReturn(URL);
    }

    @Test
    void embed() throws IOException, RequestError, URISyntaxException
    {
        when(this.httpResponse.getCode()).thenReturn(200);
        try (HttpEntity entity = mock(HttpEntity.class)) {
            when(this.httpResponse.getEntity()).thenReturn(entity);
            when(entity.getContent()).thenReturn(IOUtils.toInputStream(EMBEDDING_RESPONSE, StandardCharsets.UTF_8));

            this.openAIEmbeddingModel.initialize(MODEL, this.config);
            double[] embedding = this.openAIEmbeddingModel.embed(INPUT);
            assertEquals(3, embedding.length);
            assertEquals(0.0023064255, embedding[0]);
            assertEquals(-0.009327292, embedding[1]);
            assertEquals(-0.0028842222, embedding[2]);

            // Capture the POST request
            ArgumentCaptor<HttpPost> requestCaptor = ArgumentCaptor.forClass(HttpPost.class);
            verify(this.httpClient).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));
            HttpPost request = requestCaptor.getValue();
            assertEquals(URL + "embeddings", request.getUri().toString());
            assertEquals("Bearer " + TOKEN, request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue());
            assertEquals(ContentType.APPLICATION_JSON.toString(),
                request.getFirstHeader(HttpHeaders.ACCEPT).getValue());
            assertEquals(ContentType.APPLICATION_JSON.toString(),
                request.getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue());
            try (InputStream content = request.getEntity().getContent()) {
                assertEquals("{\"model\":\"text-embedding-ada-002\",\"input\":[\"XWiki is awesome\"]}",
                    IOUtils.toString(content, StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void embedWithError() throws IOException
    {
        when(this.httpResponse.getCode()).thenReturn(400);
        try (HttpEntity entity = mock(HttpEntity.class)) {
            when(this.httpResponse.getEntity()).thenReturn(entity);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));

            this.openAIEmbeddingModel.initialize(MODEL, this.config);
            RequestError exception = assertThrows(
                RequestError.class,
                () -> this.openAIEmbeddingModel.embed(INPUT)
            );
            assertEquals("500: Response code is 400", exception.getMessage());
        }
    }
}
