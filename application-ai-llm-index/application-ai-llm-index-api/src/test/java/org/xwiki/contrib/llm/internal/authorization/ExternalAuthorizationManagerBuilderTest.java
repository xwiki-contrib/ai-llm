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
package org.xwiki.contrib.llm.internal.authorization;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import org.apache.tools.ant.filters.StringInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.authorization.ExternalAuthorizationConfiguration;
import org.xwiki.contrib.llm.internal.HttpClientFactory;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.objects.BaseObject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link ExternalAuthorizationManagerBuilder}.
 *
 * @version $Id$
 */
@ComponentTest
class ExternalAuthorizationManagerBuilderTest
{
    private static final String TEST_URL = "https://www.example.com";

    private static final String URL_FIELD = "url";

    private static final String APPLICATION_JSON = "application/json";

    @InjectMockComponents
    private ExternalAuthorizationManagerBuilder builder;

    @MockComponent
    private HttpClientFactory httpClientFactory;

    @MockComponent
    private ExternalAuthorizationRequestBuilder externalAuthorizationRequestBuilder;

    @Test
    void build() throws IndexException, IOException, InterruptedException
    {
        BaseObject baseObject = mockBaseObject();
        AuthorizationManager authorizationManager = this.builder.build(baseObject);

        Map<String, Boolean> expected = Map.of("document1", true, "document2", false);
        String testUser = "testUser";
        when(this.externalAuthorizationRequestBuilder.build(expected.keySet()))
            .thenReturn(new ExternalAuthorizationRequest(expected.keySet(), testUser, null, null, null));

        HttpClient mockClient = mock();
        when(this.httpClientFactory.createHttpClient()).thenReturn(mockClient);
        HttpResponse<InputStream> mockResponse = mock();
        doReturn(mockResponse).when(mockClient).send(any(), any());
        when(mockResponse.statusCode()).thenReturn(200);

        String response = "{\"document1\": true, \"document2\": false}";
        when(mockResponse.body()).thenReturn(new StringInputStream(response));

        Map<String, Boolean> actual = authorizationManager.canView(expected.keySet());
        assertEquals(expected, actual);

        // Capture the sent HTTP request
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).send(captor.capture(), eq(HttpResponse.BodyHandlers.ofInputStream()));
        HttpRequest request = captor.getValue();
        assertEquals(TEST_URL, request.uri().toString());
        assertEquals(APPLICATION_JSON, request.headers().firstValue("Accept").orElse(null));
        assertEquals(APPLICATION_JSON, request.headers().firstValue("Content-Type").orElse(null));
        assertEquals("XWiki AI LLM Application", request.headers().firstValue("User-Agent").orElse(null));

        // Get the request content
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
        // The document ids can be in any order.
        List<String> expectedResponseJSON = List.of(
            "{\"document_ids\":[\"document1\",\"document2\"],\"xwiki_username\":\"%s\"}".formatted(testUser),
            "{\"document_ids\":[\"document2\",\"document1\"],\"xwiki_username\":\"%s\"}".formatted(testUser)
        );
        assertThat(StandardCharsets.UTF_8.decode(buffer).toString(), either(equalTo(expectedResponseJSON.get(0)))
                .or(equalTo(expectedResponseJSON.get(1))));
    }

    @Test
    void getConfigurationType()
    {
        assertEquals(ExternalAuthorizationConfiguration.class, this.builder.getConfigurationType());
    }

    @Test
    void getConfiguration()
    {
        BaseObject baseObject = mockBaseObject();
        Object object = this.builder.getConfiguration(baseObject);
        verify(baseObject).getStringValue(URL_FIELD);
        assertInstanceOf(ExternalAuthorizationConfiguration.class, object);
        assertEquals(TEST_URL, ((ExternalAuthorizationConfiguration) object).url());
    }

    @Test
    void setConfiguration()
    {
        ExternalAuthorizationConfiguration configuration = new ExternalAuthorizationConfiguration(TEST_URL);
        BaseObject baseObject = mock();
        this.builder.setConfiguration(baseObject, configuration);
        verify(baseObject).setStringValue(URL_FIELD, TEST_URL);

    }

    private static BaseObject mockBaseObject()
    {
        BaseObject baseObject = mock();
        when(baseObject.getStringValue(URL_FIELD)).thenReturn(ExternalAuthorizationManagerBuilderTest.TEST_URL);
        return baseObject;
    }
}
