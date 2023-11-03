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
 * A class representing a prompt and its properties.
 *
 * @version $Id$
 */
public class GPTAPIPrompt 
{
    private String name;
    private String prompt;
    private String userPrompt;
    private String description;
    private Boolean isActive;
    private Boolean isDefault;
    private Float temperature;
    private String xWikiPageName;
    private String def = "default";

    /**
     * Default constructor. every values are null except {@link GPTAPIPrompt#name},
     * wich is set to "default".
     */
    public GPTAPIPrompt()
    {
        this.name = def;
    }

    /**
     * Take a map representation of a GPTAPIPrompt object as a parameter and build a
     * GPTAPIPrompt object from it.
     * @param dbMap A map representation of the Prompt object to build.
     */
    public GPTAPIPrompt(Map<String, Object> dbMap)
    {
        this.name = (String) dbMap.get("title1");
        this.prompt = (String) dbMap.get("sysPrompt");
        this.userPrompt = (String) dbMap.get("userPrompt");
        this.description = (String) dbMap.get("longText1");
        this.isActive = ((Integer) dbMap.get("boolean1")) == 1;
        this.isDefault = ((Integer) dbMap.get(def)) == 1;
        String tempValue = (String) dbMap.get("shortText1");
        if (!tempValue.equals("")) {
            this.temperature = Float.parseFloat(tempValue);
        }
        this.xWikiPageName = (String) dbMap.get("pageName");
    }

    /**
     * @return The name of the GPTAPIPrompt object as a String.
     */
    public String getName()
    {
        return name;
    }

    /**
     * @return The prompt (systemPrompt) of the GPTAPIPrompt object as a String.
     */
    public String getPrompt()
    {
        return prompt;
    }

    /**
     * @return The user prompt of the GPTAPIPrompt object as a String.
     */
    public String getUserPrompt()
    {
        return userPrompt;
    }

    /**
     * @return The description of the GPTAPIPrompt object as a String.
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * @return true if the prompt is active, else false.
     */
    public Boolean getIsActive()
    {
        return isActive;
    }

    /**
     * @return true if the prompt is the default prompt, else false.
     */
    public Boolean getIsDefault()
    {
        return isDefault;
    }

    /**
     * @return The temperature of the GPTAPIPrompt object as a Float.
     */
    public Float getTemperature()
    {
        return temperature;
    }

    /**
     * @return The XWiki page name the GPTAPIPrompt object is from as a String.
     */
    public String getXWikiPageName()
    {
        return xWikiPageName;
    }
}
