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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import org.xwiki.component.annotation.Component;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import javax.inject.Singleton;

import org.slf4j.Logger;

/**
 * Implementation of a {@code CollectionManager} component.
 *
 * @version $Id$
 */
@Component
@Singleton
public class DefaultCollectionManager implements CollectionManager
{
    private static final String XCLASS_NAME = "CollectionsClass";
    private static final String XCLASS_SPACE_STRING = "AILLMApp.Collections.Code";

    @Inject
    private Logger logger;
    
    @Inject
    private Provider<DefaultCollection> collectionProvider;

    @Inject 
    private Provider<XWikiContext> contextProvider;

    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;

    private Map<String, DefaultCollection> collections = new HashMap<>();

    
    @Override
    public DefaultCollection createCollection(String name)
    {
        if (this.collections.containsKey(name)) {
            // Handle existing collection case
            this.logger.warn("Collection with name {} already exists", name);
            return null;
        } else {
            DefaultCollection newCollection = collectionProvider.get();
            newCollection.initialize(name);
            this.collections.put(name, newCollection);
            return newCollection;
        }

    }

    @Override
    public boolean pullCollections()
    {
        logger.info("Pulling collections from XWiki...");
        String hql = "select doc.fullName from XWikiDocument doc, BaseObject obj "
                   + "where doc.fullName=obj.name and obj.className='AILLMApp.Collections.Code.CollectionsClass'"
                   + "and doc.fullName <> 'AILLMApp.Collections.Code.CollectionsTemplate'";

        try {
            Query query = queryManager.createQuery(hql, Query.HQL);
            List<String> docNames = query.execute();

            for (String docName : docNames) {
                XWikiContext context = contextProvider.get();
                DocumentReference docRef = documentReferenceResolver.resolve(docName);
                try {
                    XWikiDocument xwikiDoc = context.getWiki().getDocument(docRef, context);
                    EntityReference objectEntityReference = getObjectReference();
                    BaseObject object = xwikiDoc.getXObject(objectEntityReference);
                    String collectionName = object.getStringValue("name");
                    DefaultCollection newCollection = createCollection(collectionName);
                    newCollection.fromXWikiDocument(xwikiDoc);
                } catch (Exception e) {
                    logger.warn("Failed to create collection {}", docName);
                }
            }
            return true;
        } catch (Exception e) {
            // Handle exceptions appropriately
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public List<DefaultCollection> listCollections()
    {
        return new ArrayList<>(collections.values());
    }
    
    @Override
    public DefaultCollection getCollection(String name)
    {
        return collections.get(name);
    }

    @Override
    public boolean deleteCollection(String name)
    {
        if (collections.containsKey(name)) {
            collections.remove(name);
            return true;
        }
        return false;
    }

    //get XObject reference for the collection XClass
    private EntityReference getObjectReference()
    {
        SpaceReference spaceRef = explicitStringSpaceRefResolver.resolve(XCLASS_SPACE_STRING);

        EntityReference collectionClassRef = new EntityReference(XCLASS_NAME,
                                    EntityType.DOCUMENT,
                                    spaceRef
                                );
        return new EntityReference(XCLASS_NAME, EntityType.OBJECT, collectionClassRef);
    }
}
