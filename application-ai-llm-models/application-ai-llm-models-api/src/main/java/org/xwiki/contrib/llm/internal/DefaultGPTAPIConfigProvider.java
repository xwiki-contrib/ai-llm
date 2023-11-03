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

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.stability.Unstable;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.user.api.XWikiGroupService;

/**
 * Default implementation of {@link GPTAPIConfigProvider}.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Unstable
@Singleton
public class DefaultGPTAPIConfigProvider implements GPTAPIConfigProvider 
{

    protected Logger logger = LoggerFactory.getLogger(DefaultGPTAPIConfigProvider.class);

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * Default constructor.
     */
    public DefaultGPTAPIConfigProvider()
    {
        super();
    }

    @Override
    public Map<String, GPTAPIConfig> getConfigObjects(String currentWiki, String userName) throws GPTAPIException
    {
        try {
            XWikiContext context = contextProvider.get();
            com.xpn.xwiki.XWiki xwiki = context.getWiki();
            XWikiGroupService groupService = xwiki.getGroupService(context);
            // Get the user using the Extension in the actual context.
            logger.info("user name: " + userName);
            Collection<String> userGroups = groupService.getAllGroupsNamesForMember(userName, 0, 0, context);
            if (userGroups.isEmpty()) {
                throw new Exception("User groups not found");
            }
            return getConfigFromDoc(xwiki, context, currentWiki, userGroups);
        } catch (Exception e) {
            logger.error("Error trying to access the config :", e);
            return new HashMap<>();
        }

    }

    /**
     * @param xwiki       The XWiki instance.
     * @param context     The current XWiki context.
     * @param currentWiki The current Wiki id.
     * @param userGroups  The list of groups containing the actual suser.
     * @return A map object of {@link #GPTAPIConfig}
     */
    private Map<String, GPTAPIConfig> getConfigFromDoc(com.xpn.xwiki.XWiki xwiki, XWikiContext context,
            String currentWiki, Collection<String> userGroups)
    {
        // Retrieve the LLM Configuration Objects
        Map<String, GPTAPIConfig> configProperties = new HashMap<>();
        try {
            XWikiDocument doc = xwiki.getDocument(currentWiki + ":AI.Code.AIConfig", context);
            List<BaseObject> configObjects = doc.getObjects(currentWiki + ":AI.Code.AIConfigClass");
            // Build the Java configurationObject with a Map.
            if (configObjects.isEmpty()) {
                throw new Exception("There is no configuration.");
            }
            // Iteration count
            int i = 0;
            // Number of null object.
            int nbNull = 0;
            for (BaseObject configObject : configObjects) {
                i++;
                Map<String, Object> configObjMap = new HashMap<>();
                if (configObject == null) {
                    nbNull++;
                    continue;
                }
                Collection<BaseProperty> fields = configObject.getFieldList();
                for (BaseProperty field : fields) {
                    configObjMap.put(field.getName(), field.getValue());
                }
                GPTAPIConfig res = new GPTAPIConfig(configObjMap);
                String[] allowedGroupTab = res.getAllowedGroup().split(",");
                // Test for every group allowed if the user is part of these group.
                for (String group : allowedGroupTab) {
                    logger.info("group : " + group);
                    if (userGroups.contains(group)) {
                        logger.info("User is part of one of the valid group.");
                        configProperties.put(res.getName(), res);
                        break;
                    }
                }
            }
            if (nbNull == i) {
                GPTAPIConfig nullConfig = new GPTAPIConfig();
                configProperties.put("nullConf", nullConfig);
                throw new Exception("The configurations are empty !");
            }
            return configProperties;
        } catch (Exception e) {
            logger.error("An error occured: ", e);
            return configProperties;
        }
    }
}
