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
package org.xwiki.contrib.llm;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GPTAPIPrompt}.
 *
 * @version $Id$
 */
class GPTAPIPromptTest
{
    private static final String TEMPERATURE = "shortText1";

    private static final String ACTIVE = "boolean1";

    private static final String DEFAULT = "default";

    private static Map<String, Object> completeMap()
    {
        Map<String, Object> properties = new HashMap<>();
        properties.put("title1", "Summarize");
        properties.put("sysPrompt", "You summarize texts.");
        properties.put("userPrompt", "Summarize this: {{text}}");
        properties.put("longText1", "Summarizes a text.");
        properties.put(ACTIVE, 1);
        properties.put(DEFAULT, 1);
        properties.put(TEMPERATURE, "0.7");
        properties.put("pageName", "AI.PromptDB.Summarize");
        return properties;
    }

    @Test
    void completeMapIsReadBackThroughTheGetters()
    {
        GPTAPIPrompt prompt = new GPTAPIPrompt(completeMap());

        assertEquals("Summarize", prompt.getName());
        assertEquals("You summarize texts.", prompt.getPrompt());
        assertEquals("Summarize this: {{text}}", prompt.getUserPrompt());
        assertEquals("Summarizes a text.", prompt.getDescription());
        assertTrue(prompt.getIsActive());
        assertTrue(prompt.getIsDefault());
        assertEquals(0.7f, prompt.getTemperature());
        assertEquals("AI.PromptDB.Summarize", prompt.getXWikiPageName());
    }

    @Test
    void missingTemperatureLeavesTheTemperatureUnset()
    {
        Map<String, Object> properties = completeMap();
        properties.remove(TEMPERATURE);

        GPTAPIPrompt prompt = new GPTAPIPrompt(properties);

        assertNull(prompt.getTemperature());
        // The properties after the temperature are still read.
        assertEquals("AI.PromptDB.Summarize", prompt.getXWikiPageName());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "abc" })
    void blankOrNonNumericTemperatureLeavesTheTemperatureUnset(String temperature)
    {
        Map<String, Object> properties = completeMap();
        properties.put(TEMPERATURE, temperature);

        assertNull(new GPTAPIPrompt(properties).getTemperature());
    }

    @Test
    void temperatureIsTrimmed()
    {
        Map<String, Object> properties = completeMap();
        properties.put(TEMPERATURE, " 0.5 ");

        assertEquals(0.5f, new GPTAPIPrompt(properties).getTemperature());
    }

    @Test
    void missingFlagsReadAsFalse()
    {
        Map<String, Object> properties = completeMap();
        properties.remove(ACTIVE);
        properties.remove(DEFAULT);

        GPTAPIPrompt prompt = new GPTAPIPrompt(properties);

        assertFalse(prompt.getIsActive());
        assertFalse(prompt.getIsDefault());
    }

    @Test
    void flagsOtherThanOneReadAsFalse()
    {
        Map<String, Object> properties = completeMap();
        properties.put(ACTIVE, 0);
        properties.put(DEFAULT, 0);

        GPTAPIPrompt prompt = new GPTAPIPrompt(properties);

        assertFalse(prompt.getIsActive());
        assertFalse(prompt.getIsDefault());
    }
}
