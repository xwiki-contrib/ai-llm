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
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

import org.xwiki.component.annotation.Component;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Provider;

import org.apache.solr.client.solrj.SolrServerException;
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
    protected Provider<XWikiContext> contextProvider;

    @Inject
    private QueryManager queryManager;

    @Inject
    private Provider<DefaultCollection> collectionProvider;

    @Inject
    private SolrConnector solrConnector;

    @Override
    public DefaultCollection createCollection(String id) throws IndexException
    {
        XWikiContext context = this.contextProvider.get();
        DocumentReference documentReference = getDocumentReference(id);
        try {
            XWikiDocument xdocument = context.getWiki().getDocument(documentReference, context);
            if (!xdocument.isNew()) {
                throw new IndexException(String.format("Failed to create collection [%s], "
                                                     + "an xwiki document with the same reference [%s] already exists.",
                                                        id, documentReference));
            }

            DefaultCollection newCollection = collectionProvider.get();
            newCollection.initialize(xdocument);
            newCollection.setID(id);
            newCollection.setTitle(id);
            return newCollection;
        } catch (XWikiException e) {
            throw new IndexException(String.format("Failed to create collection [%s]", id), e);
        }
    }

    @Override
    public List<String> getCollections() throws IndexException
    {
        List<String> collections = null;
        String templateDoc = Collection.XCLASS_SPACE_STRING + ".CollectionsTemplate";
        String hql = "select stringprop.value "
                    +    "from XWikiDocument doc, BaseObject obj, StringProperty stringprop "
                    +    "where doc.fullName=obj.name "
                    +    "and obj.className='" + Collection.XCLASS_FULLNAME + "' "
                    +    "and obj.id=stringprop.id.id "
                    +    "and stringprop.id.name='id' "
                    +    "and doc.fullName <> '" + templateDoc + "'";
        try {
            Query query = queryManager.createQuery(hql, Query.HQL);
            collections = query.execute();
            return collections;
        } catch (QueryException e) {
            throw new IndexException("Failed retrieving collection list:", e);
        }
    }
    
    @Override
    public DefaultCollection getCollection(String id) throws IndexException
    {
        XWikiContext context = contextProvider.get();
        try {
            DocumentReference documentReference = getDocumentReference(id);
            XWikiDocument xwikiDoc = context.getWiki().getDocument(documentReference, context);
            if (!xwikiDoc.isNew()) {
                DefaultCollection collection = this.collectionProvider.get();
                collection.initialize(xwikiDoc);
                return collection;
            } else {
                return null;
            }
        } catch (XWikiException e) {
            throw new IndexException(String.format("Failed to get collection with name [%s]:", id), e);
        }
    }

    @Override
    public void deleteCollection(String id, boolean deleteDocuments) throws IndexException
    {
        try {
            Collection collection = getCollection(id);
            if (deleteDocuments) {
                for (String docID : collection.getDocuments()) {
                    collection.removeDocument(docID, 
                        true,
                        true);
                }
            }
            XWikiContext context = contextProvider.get();
            DocumentReference documentReference = getDocumentReference(id);
            XWikiDocument xdocument = context.getWiki().getDocument(documentReference, context);
            context.getWiki().deleteDocument(xdocument, context);
        } catch (Exception e) {
            throw new IndexException(String.format("Failed to delete collection [%s]", id), e);
        }
    }

    @Override
    public DocumentReference getDocumentReference(String id)
    {
        String wikiId = this.contextProvider.get().getWikiId();
        return new DocumentReference("WebHome",
            new SpaceReference(id, new SpaceReference(wikiId, Collection.DEFAULT_COLLECTION_SPACE)));
    }

    @Override
    public void clearIndexCore() throws IndexException
    {
        try {
            solrConnector.clearIndexCore();
        } catch (SolrServerException e) {
            throw new IndexException("Failed to clear the index core", e);
        } 
    }

    @Override
    public List<String> similaritySearch(String textQuery) throws IndexException
    {
        try {
            return solrConnector.similaritySearch(textQuery);
        } catch (SolrServerException e) {
            throw new IndexException("Failed to perform similarity search", e);
        }
    }

}
