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
import org.junit.Before;
import org.junit.Test;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.GPTAPI;
import org.xwiki.query.QueryManager;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.rendering.block.XDOM;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import org.xwiki.test.annotation.ComponentList;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.xwiki.test.jmock.AbstractMockingComponentTestCase;
import org.xwiki.test.jmock.annotation.MockingRequirement;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.contrib.llm.internal.DefaultGPTAPI;
import org.jmock.Expectations;
import org.slf4j.Logger;

import static org.junit.Assert.*;

@MockingRequirement(value = DefaultGPTAPI.class, exceptions = { Logger.class })
public class GPTAPITest extends AbstractMockingComponentTestCase<GPTAPI> {
    @Test
    public void callChatGPT() throws Exception
    {
        Map<String,Object> test = new HashMap<String,Object>();
        test.put("model","gpt-4");
        test.put("modelType","openai");
        test.put("text","hello");
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

          this.getMockery().checking(new Expectations() {
            {
            }
        });

        String res = gptApi.getLLMChatCompletion(test,"API_KEY");
        System.out.println("res : " + res);
        assertTrue(res.length() > 10);

    }


    @Test
    public void testValidJSONData() throws Exception{
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

        this.getMockery().checking(new Expectations() {
            {
            }
        });

        // Valid data to send
        Map<String, Object> data = new HashMap<>();
        data.put("model", "gpt-4");
        data.put("modelType", "openai");
        data.put("prompt","answer with a single word : test");
        data.put("text", "this is a test");

        String res = gptApi.getLLMChatCompletion(data, "API_KEY");

        // Set of manipulation to get the content with JSON method.
        // If it goes through, the JSON received is valid, and contain a message which is not null.
        JSONObject jsonRes = new JSONObject(res);
        JSONArray choices = jsonRes.getJSONArray("choices");
        JSONObject index0 =choices.getJSONObject(0);
        JSONObject message = index0.getJSONObject("message");
        String content = message.getString("content");

        System.out.println(content);
        assertNotNull(content);

    }

    @Test
    public void testInvalidInputData() throws Exception{
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

        this.getMockery().checking(new Expectations() {
            {
            }
        });

        // Invalid data (missing key)
        Map<String, Object> data = new HashMap<>();
        data.put("model", "gpt-4");
        data.put("text", "hello");

        // call method
        String res = gptApi.getLLMChatCompletion(data, "API_KEY");
        System.out.println(res);
        assertEquals("Error processing request: org.xwiki.contrib.llm.GPTAPIException: Invalid input data",res);

        // Invalid data (wrong data)
        Map<String, Object> data2 = new HashMap<>();
        data2.put("model","gpt-4");
        data2.put("modelType", "openA");
        data2.put("text", "hello");

        String res2 = gptApi.getLLMChatCompletion(data2, "API_KEY");
        System.out.println( "res2 : "+ res2);
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
        assertEquals(expected,res2);

    }

    @Test
    public void testInvalidAPIKey() throws Exception{
        DefaultGPTAPI gptApi = (DefaultGPTAPI) getMockedComponent();

        this.getMockery().checking(new Expectations() {
            {
            }
        });

        // Valid data to send
        Map<String, Object> data = new HashMap<>();
        data.put("model", "gpt-4");
        data.put("modelType", "openai");
        data.put("prompt","answer with a single word : test");
        data.put("text", "this is a test");

        String res = gptApi.getLLMChatCompletion(data, "not_a_key");
        System.out.println(res);
        String expected = "Error processing request: org.xwiki.contrib.llm.GPTAPIException: Connection to requested server failed: HTTP/1.1 401 Unauthorized: ";
        assertEquals(expected, res);
    }
}
