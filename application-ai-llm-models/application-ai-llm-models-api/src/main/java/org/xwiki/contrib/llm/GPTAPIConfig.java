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
public class GPTAPIConfig 
{
    private String name;
    private String url;
    private String token;
    private boolean canStream;

    /**
     * Take a map representation of a GPTAPIConfig object as a parameter and build a
     * GPTAPIConfig object from it.
     * @param properties A map representation of a configuration object.
     */
    public GPTAPIConfig(Map<String, Object> properties)
    {
        this.name = (String) properties.get("Name");
        this.url = (String) properties.get("url");
        this.token = (String) properties.get("token");
        Integer requestMode = (Integer) properties.get("Requestmode");
        this.canStream = requestMode != null && requestMode == 1;
    }

    /**
     * Default constructor. every values are null except {@link GPTAPIConfig#name},
     * wich is set to "default".
     */
    public GPTAPIConfig()
    {
        this.name = "default";
    }

    /**
     * @return The name of the GPTAPIConfig as a String.
     */
    public String getName()
    {
        return name;
    }

    /**
     * @return The URL of the GPTAPIConfig as a String.
     */
    public String getURL()
    {
        return url;
    }

    /**
     * @return The token of the GPTAPIConfig as a String.
     */
    public String getToken()
    {
        return token;
    }

    /**
     * @return true if the configuration can use a streaming API, else false.
     */
    public Boolean getCanStream()
    {
        return canStream;
    }

    /**
     * @return A String representation of the GPTAPIConfig object.
     */
    @Override
    public String toString()
    {
        String res = "Name : " + name;
        res += " URL : " + url;
        res += " Token : " + token;
        res += " canStream : " + canStream;
        return res;
    }
}
