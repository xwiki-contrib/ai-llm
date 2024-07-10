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

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link OpenAIGPTAPIServer}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({ RequestHelper.class })
class OpenAIGPTAPIServerTest
{

    private static final String INPUT = "XWiki is awesome";

    private static final String TOKEN = "token";

    private static final String URL = "https://api.openai.com/v1/";

    private static final String MODEL = "text-embedding-ada-002";

    /**
     * Example response taken from
     * <a href="https://platform.openai.com/docs/api-reference/embeddings/create">the OpenAI documentation</a>.
     */
    private static final String EMBEDDING_RESPONSE = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [
                    0.0023064255,
                    -0.009327292,
                    -0.0028842222
                  ],
                  "index": 0
                }
              ],
              "model": "text-embedding-ada-002",
              "usage": {
                "prompt_tokens": 8,
                "total_tokens": 8
              }
            }
            """;

    private static final String APPLICATION_JSON = "application/json";

    @MockComponent
    private HttpClientFactory httpClientFactory;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<InputStream> httpResponse;

    @Mock
    private GPTAPIConfig config;

    @InjectMockComponents
    private OpenAIGPTAPIServer server;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.httpClientFactory.createHttpClient()).thenReturn(this.httpClient);
        when(this.httpClient.<InputStream>send(any(HttpRequest.class), any())).thenReturn(this.httpResponse);
        when(this.config.getToken()).thenReturn(TOKEN);
        when(this.config.getURL()).thenReturn(URL);
        this.server.initialize(this.config, mock(), mock());
    }

    @Test
    void embed() throws Exception
    {
        when(this.httpResponse.statusCode()).thenReturn(200);
        when(this.httpResponse.body()).thenReturn(IOUtils.toInputStream(EMBEDDING_RESPONSE, StandardCharsets.UTF_8));

        double[] embedding = this.server.embed(MODEL, List.of(INPUT)).get(0);
        assertEquals(3, embedding.length);
        assertEquals(0.0023064255, embedding[0]);
        assertEquals(-0.009327292, embedding[1]);
        assertEquals(-0.0028842222, embedding[2]);

        // Capture the POST request
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(this.httpClient).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getValue();
        assertEquals(URL + "embeddings", request.uri().toString());
        HttpHeaders headers = request.headers();
        assertEquals("Bearer " + TOKEN, headers.firstValue("Authorization").orElseThrow());
        assertEquals(APPLICATION_JSON, headers.firstValue("Accept").orElseThrow());
        assertEquals(APPLICATION_JSON, headers.firstValue("Content-Type").orElseThrow());

        Flow.Subscriber<ByteBuffer> bufferSubscriber = mock();
        doAnswer(invocation -> {
            Flow.Subscription subscription = invocation.getArgument(0);
            subscription.request(Long.MAX_VALUE);
            return null;
        }).when(bufferSubscriber).onSubscribe(any());
        request.bodyPublisher().orElseThrow().subscribe(bufferSubscriber);
        ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(bufferSubscriber).onNext(bufferCaptor.capture());
        ByteBuffer buffer = bufferCaptor.getValue();

        assertEquals("{\"model\":\"text-embedding-ada-002\",\"input\":[\"XWiki is awesome\"]}",
            StandardCharsets.UTF_8.decode(buffer).toString());
    }

    @Test
    void embedWithError() throws Exception
    {
        when(this.httpResponse.statusCode()).thenReturn(400);
        when(this.httpResponse.body()).thenReturn(IOUtils.toInputStream(
            "{\"error\": {\"message\": \"Invalid request\", \"code\": 400}}", StandardCharsets.UTF_8));

        RequestError exception = assertThrows(RequestError.class, () -> this.server.embed(MODEL, List.of(INPUT)));
        assertEquals("400: Invalid request", exception.getMessage());
    }
}
