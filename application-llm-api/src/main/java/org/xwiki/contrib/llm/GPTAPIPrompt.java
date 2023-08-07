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

import java.util.*;

import org.apache.ecs.xhtml.s;

public class GPTAPIPrompt {
    private String name;
    private String prompt;
    private String userPrompt;
    private String description;
    private Boolean isActive;
    private Boolean isDefault;
    private Float temperature;

    public GPTAPIPrompt(){
        this.name = "default";
    }

    public GPTAPIPrompt(Map<String, Object> dbMap) {
        this.name = (String) dbMap.get("title1");
        this.prompt = (String) dbMap.get("sysPrompt");
        this.userPrompt = (String) dbMap.get("userPrompt");
        this.description = (String) dbMap.get("longText1");
        this.isActive = ((Integer) dbMap.get("boolean1")) == 1;
        this.isDefault = ((Integer) dbMap.get("default")) == 1;
        if ((String) dbMap.get("shortText1") != "")
            this.temperature = Float.parseFloat((String) dbMap.get("shortText1"));
    }

    public String getName() {
        return name;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getUserPrompt(){
        return userPrompt;
    }

    public String getDescription() {
        return description;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public Float getTemperature() {
        return temperature;
    }
}