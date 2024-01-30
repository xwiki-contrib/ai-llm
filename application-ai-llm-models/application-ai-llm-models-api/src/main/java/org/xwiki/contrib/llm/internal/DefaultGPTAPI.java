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

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.GPTAPI;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.contrib.llm.GPTAPIPrompt;
import org.xwiki.contrib.llm.GPTAPIPromptDBProvider;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.stability.Unstable;
import org.xwiki.user.CurrentUserReference;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.api.User;

/**
 * Default implementation of the {@link GPTAPI} interface.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Unstable
@Singleton
public class DefaultGPTAPI implements GPTAPI 
{
    private static final String ERROR_OCCURRED = "An error occurred: ";

    private static final String DEFAULT = "default";

    private static final String CURRENT_WIKI = "currentWiki";

    private static final String TEMPERATURE = "temperature";

    private static final String PROMPT = "prompt";

    @Inject
    private Logger logger;

    @Inject
    private GPTAPIPromptDBProvider dbProvider;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private ChatModelManager chatModelManager;

    @Override
    public String getPrompt(Map<String, Object> data) throws GPTAPIException
    {
        GPTAPIPrompt promptObj = new GPTAPIPrompt();
        try {
            promptObj = dbProvider.getPrompt(data.get(PROMPT).toString(), data.get(CURRENT_WIKI).toString());
        } catch (Exception e) {
            logger.error("Exception in the REST getPrompt method : ", e);
        }
        try {
            return convertPromptToJSONObject(promptObj).toString();
        } catch (Exception e) {
            logger.error(ERROR_OCCURRED, e);
            return null;
        }
    }

    @Override
    public String getPrompts(Map<String, Object> data) throws GPTAPIException
    {
        Map<String, GPTAPIPrompt> dbMap;
        try {
            dbMap = dbProvider.getPrompts(data.get(CURRENT_WIKI).toString());
        } catch (Exception e) {
            logger.error("Exception in the REST getPromptDB method : ", e);
            dbMap = new HashMap<>();
        }
        JSONArray finalResponse = new JSONArray();
        try {
            for (Map.Entry<String, GPTAPIPrompt> entryDB : dbMap.entrySet()) {
                GPTAPIPrompt promptObj = entryDB.getValue();
                if (!entryDB.getKey().isEmpty()) {
                    finalResponse.put(convertPromptToJSONObject(promptObj));
                }
            }
            return finalResponse.toString();
        } catch (Exception e) {
            logger.error(ERROR_OCCURRED, e);
            return null;
        }
        
    }

    private static JSONObject convertPromptToJSONObject(GPTAPIPrompt promptObj)
    {
        JSONObject jsonEntry = new JSONObject();
        jsonEntry.put("name", promptObj.getName());
        jsonEntry.put(PROMPT, promptObj.getPrompt());
        jsonEntry.put("userPrompt", promptObj.getUserPrompt());
        jsonEntry.put("description", promptObj.getDescription());
        jsonEntry.put("active", promptObj.getIsActive());
        jsonEntry.put(DEFAULT, promptObj.getIsDefault());
        jsonEntry.put(TEMPERATURE, promptObj.getTemperature());
        jsonEntry.put("pageName", promptObj.getXWikiPageName());
        return jsonEntry;
    }

    @Override
    public Boolean isUserAdmin(String currentWiki) throws GPTAPIException
    {
        XWikiContext context = contextProvider.get();
        String mainWiki = context.getWikiId();
        com.xpn.xwiki.XWiki xwiki = context.getWiki();
        context.setWikiId(currentWiki);

        // Get the user using the Extension in the actual context.
        DocumentReference username = context.getUserReference();
        logger.info("user in isUserAdmin: " + username.getName());
        logger.info("user wiki : " + username.getWikiReference().getName());
        User xwikiUser = xwiki.getUser(username, context);
        Boolean res = xwikiUser.hasWikiAdminRights();
        context.setWikiId(mainWiki);
        return res;
    }

    @Override
    public Boolean checkAllowance(Map<String, Object> data) throws GPTAPIException
    {
        String wiki = (String) data.get(CURRENT_WIKI);
        return !this.chatModelManager.getModels(CurrentUserReference.INSTANCE, wiki).isEmpty();
    }
}
