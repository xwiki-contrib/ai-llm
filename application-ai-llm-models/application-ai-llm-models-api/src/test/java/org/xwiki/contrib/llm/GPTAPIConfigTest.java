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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GPTAPIConfig}.
 *
 * @version $Id$
 */
class GPTAPIConfigTest
{
    private static final String NAME = "My Provider";

    private static final String URL = "https://llm.example.org/v1";

    private static final String TOKEN = "sk-very-secret-token";

    @Test
    void gettersAndToStringExposeEverythingButTheToken()
    {
        Map<String, Object> properties = new HashMap<>();
        properties.put("Name", NAME);
        properties.put("url", URL);
        properties.put("token", TOKEN);
        properties.put("Requestmode", 1);

        GPTAPIConfig config = new GPTAPIConfig(properties);

        assertEquals(NAME, config.getName());
        assertEquals(URL, config.getURL());
        assertEquals(TOKEN, config.getToken());
        assertTrue(config.getCanStream());

        String description = config.toString();
        assertTrue(description.contains(NAME));
        assertTrue(description.contains(URL));
        assertTrue(description.contains("Token : set"));
        assertFalse(description.contains(TOKEN));
    }

    @Test
    void toStringReportsTokenUnsetWithoutToken()
    {
        Map<String, Object> properties = new HashMap<>();
        properties.put("Name", NAME);
        properties.put("url", URL);

        GPTAPIConfig config = new GPTAPIConfig(properties);

        assertFalse(config.getCanStream());
        assertTrue(config.toString().contains("Token : unset"));
    }

    @Test
    void noArgConstructorNamesDefault()
    {
        GPTAPIConfig config = new GPTAPIConfig();

        assertEquals("default", config.getName());
        assertTrue(config.toString().contains("Token : unset"));
    }
}
