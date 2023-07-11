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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import org.jmock.Expectations;
import org.junit.Test;
import org.slf4j.Logger;
import org.xwiki.contrib.llm.GPTAPI;
import org.xwiki.test.jmock.AbstractMockingComponentTestCase;
import org.xwiki.test.jmock.annotation.MockingRequirement;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;
import com.xpn.xwiki.objects.BaseObject;

import groovy.transform.Undefined.EXCEPTION;

@MockingRequirement(value = DefaultGPTAPI.class, exceptions = { Logger.class })
public class GPTAPITest extends AbstractMockingComponentTestCase<GPTAPI> {

    String API_KEY = "";

    @Test
    public void callChatGPT() throws Exception {
        Map<String, Object> test = new HashMap<String, Object>();
        test.put("model", "gpt-4");
        test.put("modelType", "openai");
        test.put("text", "hello");
        test.put("prompt", "");
        test.put("stream", "requestMode");
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

        this.getMockery().checking(new Expectations() {
            {
            }
        });

        String res = gptApi.getLLMChatCompletion(test, API_KEY);
        System.out.println("res : " + res);
        assertTrue(res.length() > 10);

    }

    @Test
    public void testValidJSONData() throws Exception {
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

        this.getMockery().checking(new Expectations() {
            {
            }
        });

        // Valid data to send
        Map<String, Object> data = new HashMap<>();
        data.put("model", "gpt-4");
        data.put("modelType", "openai");
        data.put("prompt", "answer with a single word : test");
        data.put("text", "this is a test");
        data.put("stream", "requestMode");

        String res = gptApi.getLLMChatCompletion(data, API_KEY);

        // Set of manipulation to get the content with JSON method.
        // If it goes through, the JSON received is valid, and contain a message which
        // is not null.
        JSONObject jsonRes = new JSONObject(res);
        JSONArray choices = jsonRes.getJSONArray("choices");
        JSONObject index0 = choices.getJSONObject(0);
        JSONObject message = index0.getJSONObject("message");
        String content = message.getString("content");

        System.out.println(content);
        assertNotNull(content);

    }

    @Test
    public void testInvalidInputData() throws Exception {
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

        this.getMockery().checking(new Expectations() {
            {
            }
        });

        // Invalid data (missing key)
        Map<String, Object> data = new HashMap<>();
        data.put("model", "gpt-4");
        data.put("text", "hello");
        data.put("stream", "requestMode");
        // call method
        String res = gptApi.getLLMChatCompletion(data, API_KEY);
        System.out.println(res);
        assertEquals("Error processing request: org.xwiki.contrib.llm.GPTAPIException: Invalid input data", res);

        // Invalid data (wrong data)
        Map<String, Object> data2 = new HashMap<>();
        data2.put("model", "gpt-4");
        data2.put("modelType", "openA");
        data2.put("text", "hello");
        data2.put("prompt", "");
        data2.put("stream", "requestMode");

        String res2 = gptApi.getLLMChatCompletion(data2, API_KEY);
        System.out.println("res2 : " + res2);
        String expected = "Error processing request: org.xwiki.contrib.llm.GPTAPIException: Connection to requested server failed: HTTP/1.1 500 Internal Server Error: could not load model - all backends returned error: 12 errors occurred:\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\t"
                + "* failed loading model\n\n";
        assertEquals(expected, res2);

    }

    @Test
    public void testInvalidAPIKey() throws Exception {
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

        this.getMockery().checking(new Expectations() {
            {
            }
        });

        // Valid data to send
        Map<String, Object> data = new HashMap<>();
        data.put("model", "gpt-4");
        data.put("modelType", "openai");
        data.put("prompt", "answer with a single word : test");
        data.put("text", "this is a test");
        data.put("stream", "requestMode");

        String res = gptApi.getLLMChatCompletion(data, "not_a_key");
        System.out.println(res);
        String expected = "Error processing request: org.xwiki.contrib.llm.GPTAPIException: Connection to requested server failed: HTTP/1.1 401 Unauthorized: Incorrect API key provided: not_a_key. You can find your API key at https://platform.openai.com/account/api-keys.";
        assertEquals(expected, res);
    }

    @Test
    public void testGetModels() throws Exception {
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

        this.getMockery().checking(new Expectations() {
            {
            }
        });

        String res = gptApi.getModels(API_KEY);
        System.out.println(res);
        assertNotNull(res);
        JSONObject jsonRes = new JSONObject(res);
        assertNotNull(jsonRes);
    }
}
