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

import javax.inject.Singleton;

import org.apache.poi.util.SystemOutLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.stability.Unstable;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.api.User;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.BaseStringProperty;
import com.xpn.xwiki.user.api.XWikiUser;
import com.xpn.xwiki.web.Utils;

@Component
@Unstable
@Singleton
public class DefaultGPTAPIConfigProvider implements GPTAPIConfigProvider {

    protected Logger logger = LoggerFactory.getLogger(DefaultGPTAPIConfigProvider.class);

    public DefaultGPTAPIConfigProvider() {
        super();
    }

    @Override
    public Map<String, GPTAPIConfig> getConfigObjects() throws GPTAPIException {
        Map<String, GPTAPIConfig> configProperties = new HashMap<>();
        try {
            Execution execution = Utils.getComponent(Execution.class);
            XWikiContext context = (XWikiContext) execution.getContext().getProperty("xwikicontext");
            com.xpn.xwiki.XWiki xwiki = context.getWiki();

            // Get the user using the Extension in the actual context.
            DocumentReference username = context.getUserReference();
            User xwikiUser = xwiki.getUser(username, context);

            // Retrieve the LLM Configuration Objects 
            XWikiDocument doc = xwiki.getDocument("AI.Code.AIConfig", context);
            List<BaseObject> configObjects = doc.getObjects("AI.Code.AIConfigClass");

            // Build the Java configurationObject with a Map.
            for (BaseObject configObject : configObjects) {
                Map<String, Object> configObjMap = new HashMap<>();
                if (configObject == null)
                    continue;
                Collection<BaseProperty> fields = configObject.getFieldList();
                for (BaseProperty field : fields) {
                    configObjMap.put(field.getName(), field.getValue());
                }
                GPTAPIConfig res = new GPTAPIConfig(configObjMap);
                String[] allowedGroupTab = res.getAllowedGroup().split(",");
                logger.info("Allowed Group to use this configuration: [{}]", allowedGroupTab.toString());
                // Test for every group allowed if the user is part of these group.
                for (String group : allowedGroupTab) {
                    if (xwikiUser.isUserInGroup(group)) {
                        logger.info("User is part of one of the valid group.");
                        configProperties.put(res.getName(), res);
                        break;
                    } else
                        continue;
                }
            }
            if (configProperties.isEmpty())
                throw new Exception(
                        "Final Config Map is empty. Check that the user using the extension remain in a group that is allowed in the LLM configuration.");
            return configProperties;
        } catch (Exception e) {
            logger.error("Error trying to access the config :", e);
            System.err.println("Error trying to access the config :" + e);
            return configProperties;
        }

    }

}
