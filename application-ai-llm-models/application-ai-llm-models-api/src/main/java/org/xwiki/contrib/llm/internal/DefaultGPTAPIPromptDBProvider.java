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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIPrompt;
import org.xwiki.contrib.llm.GPTAPIPromptDBProvider;
import org.xwiki.query.QueryManager;
import org.xwiki.stability.Unstable;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import org.xwiki.query.Query;

/**
 * Default implementation of {@link GPTAPIPromptDBProvider}.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Unstable
@Singleton
public class DefaultGPTAPIPromptDBProvider implements GPTAPIPromptDBProvider 
{

    protected Logger logger = LoggerFactory.getLogger(DefaultGPTAPIPromptDBProvider.class);

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Provider<QueryManager> queryManagerProvider;

    private String titleKey = "title1";
    private String postWikiRefExpr = ":";
    private String pageNameKey = "pageName";

    /**
     * Default constructor.
     */
    public DefaultGPTAPIPromptDBProvider()
    {
        super();
    }

    @Override
    public GPTAPIPrompt getPrompt(String promptPage, String currentWiki)
    {
        GPTAPIPrompt res = new GPTAPIPrompt();
        try {
            XWikiContext context = contextProvider.get();
            com.xpn.xwiki.XWiki xwiki = context.getWiki();

            XWikiDocument promptDoc = xwiki.getDocument(currentWiki + postWikiRefExpr + promptPage, context);
            if (promptDoc != null) {
                BaseObject object = promptDoc.getObject(currentWiki + ":AI.PromptDB.Code.PromptDBClass");
                if (object != null) {
                    Map<String, Object> dbObjMap = new HashMap<>();
                    Collection<BaseProperty> fields = object.getFieldList();
                    for (BaseProperty field : fields) {
                        dbObjMap.put(field.getName(), field.getValue());
                    }
                    dbObjMap.put(titleKey, promptDoc.getTitle());
                    if (!dbObjMap.isEmpty()) {
                        dbObjMap.put(pageNameKey, promptPage);
                        res = new GPTAPIPrompt(dbObjMap);
                        return res;
                    }
                }

            }
            return res;

        } catch (Exception e) {
            logger.error("Error trying to access the get the prompt :", e);
            return null;
        }
    }

    @Override
    public Map<String, GPTAPIPrompt> getPrompts(String currentWiki)
    {
        Map<String, GPTAPIPrompt> promptDBMap = new HashMap<>();
        try {
            XWikiContext context = contextProvider.get();
            com.xpn.xwiki.XWiki xwiki = context.getWiki();
            QueryManager queryManager = queryManagerProvider.get();
            // HQL query to select the full documents names
            String hql = "select doc.fullName from XWikiDocument as doc, BaseObject as obj where obj.name=doc.fullName"
                    + " and obj.className='AI.PromptDB.Code.PromptDBClass'";

            Query query = queryManager.createQuery(hql, Query.HQL);
            query.setWiki(currentWiki);
            query.setLimit(0);
            // The query will return a list of document names.
            List<String> documentNames = query.execute();
            // get rid of this doc since it is a template, it cause crash.
            documentNames.remove("AI.PromptDB.Code.PromptDBTemplate");
            // Check if the query returned an empty result
            if (documentNames.isEmpty()) {
                throw new Exception("The Query for prompt object returned an empty result.");
            }

            // Iterate over all documents that contain an object of the class
            // 'AI.PromptDB.Code.PromptDBClass'
            for (String documentName : documentNames) {
                XWikiDocument doc = xwiki.getDocument(currentWiki + postWikiRefExpr + documentName, context);
                if (doc != null) {
                    // Get the objects of the class 'AI.PromptDB.Code.PromptDBClass' from the
                    // current document
                    BaseObject object = doc.getObject("AI.PromptDB.Code.PromptDBClass");
                    if (object != null) {
                        Map<String, Object> dbObjMap = new HashMap<>();
                        Collection<BaseProperty> fields = object.getFieldList();
                        for (BaseProperty field : fields) {
                            dbObjMap.put(field.getName(), field.getValue());
                        }
                        dbObjMap.put(titleKey, doc.getTitle());
                        if (!dbObjMap.isEmpty()) {
                            dbObjMap.put(pageNameKey, documentName);
                            GPTAPIPrompt res = new GPTAPIPrompt(dbObjMap);
                            promptDBMap.put(res.getName().toLowerCase(), res);
                        }
                    }
                }
            }
            return promptDBMap;

        } catch (Exception e) {
            logger.error("Error trying to access the prompt database :", e);
            return promptDBMap;
        }

    }

}
