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

import java.util.Map;

/**
 * A class representing an LLM AI extension confifuration as a java object.
 * @version $Id$
 */
public class ChatClientConfig 
{
    private String name;
    private String url;

    /**
     * Take a map representation of a ChatClientConfig object as a parameter and build a
     * ChatClientConfig object from it.
     * @param properties A map representation of a configuration object.
     */
    public ChatClientConfig(Map<String, Object> properties)
    {
        this.name = (String) properties.get("Name");
        this.url = (String) properties.get("url");
    }

    /**
     * Default constructor. every values are null except {@link ChatClientConfig#name},
     * wich is set to "default".
     */
    public ChatClientConfig()
    {
        this.name = "default";
    }

    /**
     * @return The name of the ChatClientConfig as a String.
     */
    public String getName()
    {
        return name;
    }

    /**
     * @return The URL of the ChatClientConfig as a String.
     */
    public String getURL()
    {
        return url;
    }

    /**
     * @return A String representation of the ChatClientConfig object.
     */
    @Override
    public String toString()
    {
        String res = "Name : " + name;
        res += " URL : " + url;
        return res;
    }
}
