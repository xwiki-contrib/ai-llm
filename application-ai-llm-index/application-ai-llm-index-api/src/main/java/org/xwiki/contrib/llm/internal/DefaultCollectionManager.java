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

import java.util.List;

import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

import org.xwiki.component.annotation.Component;

import javax.inject.Inject;
import javax.inject.Named;


import javax.inject.Singleton;
import javax.inject.Provider;

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
    @Inject
    private Logger logger;

    @Inject
    private QueryManager queryManager;

    @Inject
    private Provider<DefaultCollection> collectionProvider;

    @Inject 
    private Provider<XWikiContext> contextProvider;

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;
   
    @Override
    public Collection createCollection(String name)
    {
        if (this.getCollections().contains(name)) {
            // Handle existing collection case
            return null;
        } else {
            XWikiContext context = this.contextProvider.get();
            String fullName = buildCollectionFullname(name);
            DocumentReference documentReference = getDocumentReference(fullName);
            try {
                XWikiDocument xdocument = context.getWiki().getDocument(documentReference, context);
                DefaultCollection newCollection = collectionProvider.get();
                newCollection.initialize(xdocument);
                newCollection.setName(name);
                return newCollection;
            } catch (XWikiException e) {
                this.logger.error("Error while creating collection", e);
                e.printStackTrace();
            }
            return null;
        }

    }

    private DocumentReference getDocumentReference(String fullName)
    {
        XWikiContext context = this.contextProvider.get();
        EntityReference entityReference = this.explicitStringSpaceRefResolver.resolve(fullName, context);
        return context.getWiki().getDocumentReference(entityReference, context);
    }

    @Override
    public List<String> getCollections()
    {
        List<String> collections = null;
        String templateDoc = Collection.XCLASS_SPACE_STRING + ".CollectionsTemplate";
        String hql = "select doc.title from XWikiDocument doc, BaseObject obj "
                    + "where doc.fullName=obj.name and obj.className='" + Collection.XCLASS_FULLNAME + "' "
                    + "and doc.fullName <> '" + templateDoc + "'";
        try {
            Query query = queryManager.createQuery(hql, Query.HQL);
            collections = query.execute();
            return collections;
        } catch (QueryException e) {
            e.printStackTrace();
        }
        return collections;
    }
    
    @Override
    public Collection getCollection(String name) throws IndexException
    {
        if (getCollections().contains(name)) {
            XWikiContext context = contextProvider.get();
            
            try {
                String fullName = buildCollectionFullname(name);
                XWikiDocument xwikiDoc = context.getWiki().getDocument(fullName, EntityType.DOCUMENT, context);
                if (!xwikiDoc.isNew()) {
                    DefaultCollection collection = this.collectionProvider.get();
                    collection.initialize(xwikiDoc);
                    return collection;
                } else {
                    return null;
                }
            } catch (XWikiException e) {
                throw new IndexException("Failed to get collection " + name, e);
            }
        } else {
            return null;
        }
    }

    @Override
    public boolean deleteCollection(String name)
    {
        if (getCollections().contains(name)) {
            XWikiContext context = contextProvider.get();
            String fullName = buildCollectionFullname(name);
            DocumentReference documentReference = getDocumentReference(fullName);
            try {
                XWikiDocument xdocument = context.getWiki().getDocument(documentReference, context);
                context.getWiki().deleteDocument(xdocument, context);
                return true;
            } catch (XWikiException e) {
                this.logger.error("Error while deleting collection", e);
                e.printStackTrace();
                return false;
            }
        } else {
            return false;
        }
    }

    private String buildCollectionFullname(String name)
    {
        return Collection.DEFAULT_COLLECTION_SPACE + "." + name + Collection.DEFAULT_COLLECTION_SUFFIX;
    }

    @Override
    public boolean clearIndexCore()
    {
        try {
            SolrConnector.clearIndexCore();
            return true;
        } catch (Exception e) {
            logger.error("Failed to clear index core", e);
            return false;
        } 
    }

}
