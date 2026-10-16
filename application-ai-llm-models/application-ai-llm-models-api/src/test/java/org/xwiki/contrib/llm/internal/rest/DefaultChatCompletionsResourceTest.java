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
package org.xwiki.contrib.llm.internal.rest;

import java.util.Map;

import javax.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.ChatClientConfig;
import org.xwiki.contrib.llm.ChatClientConfigProvider;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.contrib.llm.openai.ChatCompletionRequest;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.CurrentUserReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultChatCompletionsResource}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultChatCompletionsResourceTest
{
    private static final String WIKI = "xwiki";

    private static final String MODEL = "model1";

    private static final String CONFIGURED_ORIGIN = "https://app.example";

    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";

    private static final ChatCompletionRequest REQUEST = ChatCompletionRequest.builder().model(MODEL).build();

    @InjectMockComponents
    private DefaultChatCompletionsResource resource;

    @MockComponent
    private ChatModelManager chatModelManager;

    @MockComponent
    private ChatClientConfigProvider configProvider;

    @Mock
    private ChatClientConfig clientConfig;

    @Mock
    private ChatModel model;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.chatModelManager.getModel(MODEL, CurrentUserReference.INSTANCE, WIKI)).thenReturn(this.model);
        when(this.model.process(REQUEST)).thenThrow(new RequestError(500, "The model is unavailable."));
    }

    @Test
    void errorWithConfiguredOriginEchoesThatOrigin() throws Exception
    {
        configureOrigin(CONFIGURED_ORIGIN);

        Response response = this.resource.getCompletions(CONFIGURED_ORIGIN, WIKI, REQUEST);

        assertEquals(500, response.getStatus());
        assertEquals(CONFIGURED_ORIGIN, response.getHeaderString(ALLOW_ORIGIN));
        assertEquals("POST, OPTIONS", response.getHeaderString(ALLOW_METHODS));
    }

    @Test
    void errorWithUnknownOriginSetsNoAllowOriginHeader() throws Exception
    {
        configureOrigin(CONFIGURED_ORIGIN);

        Response response = this.resource.getCompletions("https://evil.example", WIKI, REQUEST);

        assertEquals(500, response.getStatus());
        // The request origin is not echoed back when it is not a configured chat client.
        assertNull(response.getHeaderString(ALLOW_ORIGIN));
        assertEquals("POST, OPTIONS", response.getHeaderString(ALLOW_METHODS));
    }

    @Test
    void errorWithoutOriginSetsNoAllowOriginHeader()
    {
        Response response = this.resource.getCompletions(null, WIKI, REQUEST);

        assertEquals(500, response.getStatus());
        assertNull(response.getHeaderString(ALLOW_ORIGIN));
        assertEquals("POST, OPTIONS", response.getHeaderString(ALLOW_METHODS));
    }

    private void configureOrigin(String url) throws Exception
    {
        when(this.clientConfig.getURL()).thenReturn(url);
        when(this.configProvider.getConfigObjects(WIKI)).thenReturn(Map.of("client", this.clientConfig));
    }
}
