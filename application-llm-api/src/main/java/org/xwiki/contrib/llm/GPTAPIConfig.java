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

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.api.XWiki;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.BaseStringProperty;
import com.xpn.xwiki.web.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.context.Execution;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.print.Doc;

import org.xwiki.contrib.llm.GPTAPIConfigObj;

public class GPTAPIConfig {
    protected Logger logger = LoggerFactory.getLogger(GPTAPIConfig.class);

    GPTAPIConfig() {
        super();
    }

    public Map<String, GPTAPIConfigObj> getConfigObjects() {
        Map<String, GPTAPIConfigObj> configProperties = new HashMap<>();
        try {
            Execution execution = Utils.getComponent(Execution.class);
            XWikiContext context = (XWikiContext) execution.getContext().getProperty("xwikicontext");
            com.xpn.xwiki.XWiki xwiki = context.getWiki();

            XWikiDocument doc = xwiki.getDocument("AI.Code.AIConfig", context);
            List<BaseObject> configObjects = doc.getObjects("AI.Code.AIConfigClass");
            int i = 0;
            for (BaseObject configObject : configObjects) {
                Map<String, Object> configObjMap = new HashMap<>();
                if (configObject == null)
                    continue;
                Collection<BaseStringProperty> fields = configObject.getFieldList();
                for (BaseStringProperty field : fields) {
                    logger.info("Test : " + field);
                    configObjMap.put(field.getName(), field.getValue());
                }
                GPTAPIConfigObj res = new GPTAPIConfigObj(configObjMap);
                configProperties.put("config_"+res.getName().toLowerCase(), res);
                i++;
            }
            if (configProperties.isEmpty())
                throw new Exception("Final Map is empty");
            return configProperties;
        } catch (Exception e) {
            logger.error("Error trying to access the config :", e);
            System.err.println("Error trying to access the config :" + e);
            return configProperties;
        }

    }
}