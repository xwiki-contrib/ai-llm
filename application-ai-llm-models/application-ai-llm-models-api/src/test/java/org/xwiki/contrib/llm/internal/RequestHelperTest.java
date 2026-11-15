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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RequestHelper}.
 *
 * @version $Id$
 */
@ComponentTest
class RequestHelperTest
{
    @InjectMockComponents
    private RequestHelper requestHelper;

    @MockComponent
    private HttpClientFactory httpClientFactory;

    @Mock
    private HttpClient httpClient;

    @BeforeEach
    void setUp()
    {
        when(this.httpClientFactory.createHttpClient()).thenReturn(this.httpClient);
    }

    private HttpRequest sentRequest() throws Exception
    {
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(this.httpClient).send(requestCaptor.capture(), any());
        return requestCaptor.getValue();
    }

    private void postChatCompletion(String urlPrefix) throws Exception
    {
        GPTAPIConfig config = new GPTAPIConfig(Map.of("Name", "Test server", "url", urlPrefix));
        this.requestHelper.post(config, "chat/completions", Map.of("model", "m"),
            HttpResponse.BodyHandlers.discarding());
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://llm.example.com/v1/", "https://llm.example.com/v1" })
    void endpointPathIsBelowTheURLPrefixWithOrWithoutFinalSlash(String urlPrefix) throws Exception
    {
        postChatCompletion(urlPrefix);

        assertEquals("https://llm.example.com/v1/chat/completions", sentRequest().uri().toString());
    }

    @Test
    void endpointPathIsBelowAURLPrefixWithoutPath() throws Exception
    {
        postChatCompletion("https://llm.example.com");

        assertEquals("https://llm.example.com/chat/completions", sentRequest().uri().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " " })
    void missingURLPrefixIsReported(String urlPrefix)
    {
        IOException exception = assertThrows(IOException.class, () -> postChatCompletion(urlPrefix));

        assertEquals("The AI server [Test server] has no URL prefix; set it in the AI server configuration.", exception.getMessage());
        verifyNoInteractions(this.httpClient);
    }
}
