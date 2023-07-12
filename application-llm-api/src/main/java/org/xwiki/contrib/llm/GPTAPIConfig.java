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

public class GPTAPIConfig {
    private String name;
    private String url;
    private String configModels;
    private String token;
    private String modelsURL;

    public GPTAPIConfig(Map<String, Object> properties) {
        this.name = (String) properties.get("Name");
        this.url = (String) properties.get("url");
        this.configModels = (String) properties.get("Config");
        this.token = (String) properties.get("token");
        this.modelsURL = (String) properties.get("modelurl");
    }

    public GPTAPIConfig() {
    }

    public String getName() {
        return name;
    }

    public String getURL() {
        return url;
    }

    public String getConfigModels() {
        return configModels;
    }

    public String getToken() {
        return token;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setURL(String url) {
        this.url = url;
    }

    public void setConfigJSON(String configModels) {
        this.configModels = configModels;
    }

    public void setToken(String token) {
        this.token = token;
    }

    @Override
    public String toString() {
        String res = "Name : " + name + "\n";
        res += "URL : " + url + "\n";
        res += "Config param : " + configModels + "\n";
        res += "Token : " + token + "\n";
        res += "modelsURL : " + modelsURL + "\n";
        return res;
    }
}