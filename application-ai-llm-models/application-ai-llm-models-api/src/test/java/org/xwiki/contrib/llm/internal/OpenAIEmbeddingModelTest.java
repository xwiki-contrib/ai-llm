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
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Flow;

import javax.inject.Named;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;
import org.xwiki.user.UserReferenceSerializer;
import org.xwiki.user.group.GroupManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

    private static final String SERVER_NAME = "LocalAI";

    private static final String WIKI_NAME = "wiki";

    private static final DocumentReference DOCUMENT_REFERENCE = new DocumentReference(WIKI_NAME, "space", "modelPage");

    private static final ObjectReference OBJECT_REFERENCE = new ObjectReference("AI.Models.Code.ModelsClass[0]",
        DOCUMENT_REFERENCE);

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

    private static final String APPLICATION_JSON = "application/json";

    @MockComponent
    private HttpClientFactory httpClientFactory;

    @MockComponent
    private GPTAPIConfigProvider configProvider;

    @MockComponent
    private GroupManager groupManager;

    @MockComponent
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<InputStream> httpResponse;

    @Mock
    private GPTAPIConfig config;

    @BeforeEach
    void setUp() throws IOException, GPTAPIException, InterruptedException
    {
        when(this.httpClientFactory.createHttpClient()).thenReturn(this.httpClient);
        when(this.httpClient.<InputStream>send(any(HttpRequest.class), any())).thenReturn(this.httpResponse);
        when(this.config.getToken()).thenReturn(TOKEN);
        when(this.config.getURL()).thenReturn(URL);

        when(this.configProvider.getConfigObjects(WIKI_NAME)).thenReturn(Map.of(SERVER_NAME, this.config));
    }

    @Test
    void embed() throws IOException, RequestError, URISyntaxException, ComponentLookupException, InterruptedException
    {
        when(this.httpResponse.statusCode()).thenReturn(200);
        when(this.httpResponse.body()).thenReturn(IOUtils.toInputStream(EMBEDDING_RESPONSE, StandardCharsets.UTF_8));

        ModelConfiguration modelConfiguration = new ModelConfiguration();
        modelConfiguration.setModel(MODEL);
        modelConfiguration.setServerName(SERVER_NAME);
        modelConfiguration.setObjectReference(OBJECT_REFERENCE);

        OpenAIEmbeddingModel openAIEmbeddingModel =
            new OpenAIEmbeddingModel(modelConfiguration, this.componentManager);
        double[] embedding = openAIEmbeddingModel.embed(INPUT);
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
    void embedWithError() throws IOException, ComponentLookupException
    {
        when(this.httpResponse.statusCode()).thenReturn(400);
        when(this.httpResponse.body()).thenReturn(IOUtils.toInputStream(
            "{\"error\": {\"message\": \"Invalid request\", \"code\": 400}}", StandardCharsets.UTF_8));
        ModelConfiguration modelConfiguration = new ModelConfiguration();
        modelConfiguration.setModel(MODEL);
        modelConfiguration.setServerName(SERVER_NAME);
        modelConfiguration.setObjectReference(OBJECT_REFERENCE);

        OpenAIEmbeddingModel openAIEmbeddingModel =
            new OpenAIEmbeddingModel(modelConfiguration, this.componentManager);

        RequestError exception = assertThrows(RequestError.class, () -> openAIEmbeddingModel.embed(INPUT));
        assertEquals("400: Invalid request", exception.getMessage());
    }
}
