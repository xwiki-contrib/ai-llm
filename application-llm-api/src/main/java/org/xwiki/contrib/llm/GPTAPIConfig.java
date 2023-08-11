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
 * @version $Id.*$
 */
public class GPTAPIConfig 
{
    private String name;
    private String url;
    private String configModels;
    private String token;
    private boolean canStream;
    private String allowedGroup;

    /**
     * Take a map representation of a GPTAPIConfig object as a parameter and build a
     * GPTAPIConfig object from it.
     * @param properties A map representation of a configuration object.
     */
    public GPTAPIConfig(Map<String, Object> properties) {
        this.name = (String) properties.get("Name");
        this.url = (String) properties.get("url");
        this.configModels = (String) properties.get("Config");
        this.token = (String) properties.get("token");
        if ((Integer) properties.get("Requestmode") == 1) {
            this.canStream = true;
        } else {
            this.canStream = false;
        }
        this.allowedGroup = (String) properties.get("RightLLM");
    }

    /**
     * Default constructor. every values are null except {@link GPTAPIConfig#name},
     * wich is set to "default".
     */
    public GPTAPIConfig() {
        this.name = "default";
    }

    /**
     * @return The name of the GPTAPIConfig as a String.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The URL of the GPTAPIConfig as a String.
     */
    public String getURL() {
        return url;
    }

    /**
     * @return The LLM models of the GPTAPIConfig in one String.
     */
    public String getConfigModels() {
        return configModels;
    }

    /**
     * @return The token of the GPTAPIConfig as a String.
     */
    public String getToken() {
        return token;
    }

    /**
     * @return true if the configuration can use a streaming API, else false.
     */
    public Boolean getCanStream() {
        return canStream;
    }

    /**
     * @return The XWiki group allowed to use this GPTAPIConfig in a String.
     */
    public String getAllowedGroup() {
        return allowedGroup;
    }

    /**
     * @return A String representation of the GPTAPIConfig object.
     */
    @Override
    public String toString() {
        String res = "Name : " + name;
        res += " URL : " + url;
        res += " Config param : " + configModels;
        res += " Token : " + token;
        res += " canStream : " + canStream;
        res += " allowedGroup : " + allowedGroup;
        return res;
    }
}
