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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.context.Execution;
import org.xwiki.contrib.llm.GPTAPIPrompt;
import org.xwiki.contrib.llm.GPTAPIPromptDBProvider;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.query.QueryManager;
import org.xwiki.stability.Unstable;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.web.Utils;

import org.xwiki.query.Query;

@Component
@Unstable
@Singleton
public class DefaultGPTAPIPromptDBProvider implements GPTAPIPromptDBProvider {

    protected Logger logger = LoggerFactory.getLogger(DefaultGPTAPIPromptDBProvider.class);

    @Inject
    Provider<XWikiContext> contextProvider;

    @Inject
    ComponentManager componentManager;

    public DefaultGPTAPIPromptDBProvider() {
        super();
    }

    @Override
    public GPTAPIPrompt getPrompt(String promptName, String currentWiki) {
        GPTAPIPrompt res = new GPTAPIPrompt();
        try {
            Execution execution = Utils.getComponent(Execution.class);
            XWikiContext context = contextProvider.get();
            com.xpn.xwiki.XWiki xwiki = context.getWiki();
            XWikiDocument promptDoc = xwiki.getDocument(currentWiki + ":" + promptName, context);
            if (promptDoc != null) {
                BaseObject object = promptDoc.getObject(currentWiki + ":AI.PromptDB.Code.PromptDBClass");
                if (object != null) {
                    logger.info("title of the doc : {}", promptDoc.getTitle());
                    logger.info("prompt wanted : {}", promptName);
                    Map<String, Object> dbObjMap = new HashMap<>();
                    Collection<BaseProperty> fields = object.getFieldList();
                    for (BaseProperty field : fields) {
                        logger.info("Field: " + field.getValue());
                        dbObjMap.put(field.getName(), field.getValue());
                    }
                    dbObjMap.put("title1", promptDoc.getTitle());
                    for (Map.Entry<String, Object> entry : dbObjMap.entrySet()) {
                        logger.info(entry.getKey() + ": " + entry.getValue());
                    }
                    if (!dbObjMap.isEmpty()) {
                        res = new GPTAPIPrompt(dbObjMap);
                        if (res.getName() == null || res.getPrompt() == null || res.getIsActive() == null) {
                            logger.info("one of the value in the prompt object is null.");
                        } else
                            return res;
                    }
                }

            }
            return res;

        } catch (Exception e) {
            logger.error("Error trying to access the prompt database :", e);
            return null;
        }
    }

}