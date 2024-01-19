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


import org.apache.commons.codec.digest.DigestUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;

import java.util.List;


import javax.inject.Inject;

import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
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
import com.xpn.xwiki.objects.BaseObject;
import javax.inject.Provider;
import org.slf4j.Logger;
/**
 * Implementation of a {@code Collection} component.
 *
 * @version $Id$
 */
@Component(roles = DefaultCollection.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class DefaultCollection implements Collection
{
    private static final String EMBEDDINGMODEL_FIELDNAME = "embeddingModel";
    private static final String CHUNKING_METHOD_FIELDNAME = "chunkingMethod";
    private static final String CHUNKING_MAX_SIZE_FIELDNAME = "chunkingMaxSize";
    private static final String CHUNKING_OVERLAP_OFFSET_FIELDNAME = "chunkingOverlapOffset";
    private static final String QUERY_GROUPS_FIELDNAME = "queryGroups";
    private static final String EDIT_GROUPS_FIELDNAME = "editGroups";
    private static final String ADMIN_GROUPS_FIELDNAME = "adminGroups";
    private static final String RIGHTS_CHECK_METHOD_FIELDNAME = "rightsCheckMethod";
    private static final String RIGHTS_CHECK_METHOD_PARAMETER_FIELDNAME = "rightsCheckMethodParam";
    private static final String DOCUMENT_SPACE_FIELDNAME = "documentSpaces";

    private XWikiDocument initialDocument;

    private XWikiDocument xwikidocument;

    private BaseObject object;

    @Inject
    private QueryManager queryManager;

    @Inject
    private Provider<DefaultDocument> documentProvider;

    @Inject 
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    /**
     * Initialize the collection.
     *  
     * @param xwikidocument the XWiki document
     */
    public void initialize(XWikiDocument xwikidocument)
    {
        this.xwikidocument = xwikidocument;
        this.initialDocument = xwikidocument;
        this.object = xwikidocument.getXObject(XCLASS_REFERENCE);
    }

    private BaseObject getEditableObject() throws IndexException
    {
        ensureDocumentIsClone();

        if (this.object == null)
        {
            XWikiContext context = this.contextProvider.get();
            try {
                this.object = this.xwikidocument.newXObject(XCLASS_REFERENCE, context);
            } catch (XWikiException e) {
                throw new IndexException(String.format("Error initializing collection for document [%s].",
                    this.xwikidocument.getDocumentReference()), e);
            }
        }

        return this.object;
    }

    private void ensureDocumentIsClone()
    {
        if (this.initialDocument == this.xwikidocument) {
            this.xwikidocument = this.initialDocument.clone();
            this.object = this.xwikidocument.getXObject(XCLASS_REFERENCE);
        }
    }

    @Override
    public String getName()
    {
        return this.xwikidocument.getTitle();
    }

    @Override
    public String getEmbeddingModel()
    {
        return this.object != null ? this.object.getStringValue(EMBEDDINGMODEL_FIELDNAME) : "";
    }
    
    @Override
    public String getChunkingMethod()
    {
        return this.object != null ? this.object.getStringValue(CHUNKING_METHOD_FIELDNAME) : "";
    }
    
    @Override
    public int getChunkingMaxSize()
    {
        return this.object != null ? this.object.getIntValue(CHUNKING_MAX_SIZE_FIELDNAME) : 0;
    }
    
    @Override
    public int getChunkingOverlapOffset()
    {
        return this.object != null ? this.object.getIntValue(CHUNKING_OVERLAP_OFFSET_FIELDNAME) : 0;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public List<String> getDocumentSpaces()
    {
        return this.object != null ? this.object.getListValue(DOCUMENT_SPACE_FIELDNAME) : List.of();
    }
    
    @Override
    public String getQueryGroups()
    {
        return this.object != null ? this.object.getLargeStringValue(QUERY_GROUPS_FIELDNAME) : "";
    }
    
    @Override
    public String getEditGroups()
    {
        return this.object != null ? this.object.getLargeStringValue(EDIT_GROUPS_FIELDNAME) : "";
    }
    
    @Override
    public String getAdminGroups()
    {
        return this.object != null ? this.object.getLargeStringValue(ADMIN_GROUPS_FIELDNAME) : "";
    }   
    
    @Override
    public String getRightsCheckMethod()
    {
        return this.object != null ? this.object.getStringValue(RIGHTS_CHECK_METHOD_FIELDNAME) : "";
    }
    
    @Override
    public String getRightsCheckMethodParam()
    {
        return this.object != null ? this.object.getStringValue(RIGHTS_CHECK_METHOD_PARAMETER_FIELDNAME) : "";
    }
    
    @Override
    public void setName(String name)
    {
        ensureDocumentIsClone();

        this.xwikidocument.setTitle(name);
    }
    
    @Override
    public void setEmbeddingModel(String embeddingModel) throws IndexException
    {
        if (embeddingModel != null) {
            getEditableObject().setStringValue(EMBEDDINGMODEL_FIELDNAME, embeddingModel);
        }
    }
    
    @Override
    public void setChunkingMethod(String chunkingMethod) throws IndexException
    {
        if (chunkingMethod != null) {
            getEditableObject().setStringValue(CHUNKING_METHOD_FIELDNAME, chunkingMethod);
        }
    }
    
    @Override
    public void setChunkingMaxSize(int chunkingMaxSize) throws IndexException
    {
        getEditableObject().setIntValue(CHUNKING_MAX_SIZE_FIELDNAME, chunkingMaxSize);
    }
    
    @Override
    public void setChunkingOverlapOffset(int chunkingOverlapOffset) throws IndexException
    {
        getEditableObject().setIntValue(CHUNKING_OVERLAP_OFFSET_FIELDNAME, chunkingOverlapOffset);
    }
    
    @Override
    public void setDocumentSpaces(List<String> documentSpaces) throws IndexException
    {
        if (documentSpaces != null) {
            getEditableObject().setStringListValue(DOCUMENT_SPACE_FIELDNAME, documentSpaces);
        }
    }
    
    @Override
    public void setQueryGroups(String queryGroups) throws IndexException
    {
        if (queryGroups != null) {
            getEditableObject().setLargeStringValue(QUERY_GROUPS_FIELDNAME, queryGroups);
        }
    }
    
    @Override
    public void setEditGroups(String editGroups) throws IndexException
    {
        if (editGroups != null) {
            getEditableObject().setLargeStringValue(EDIT_GROUPS_FIELDNAME, editGroups);
        }
    }
    
    @Override
    public void setAdminGroups(String adminGroups) throws IndexException
    {
        if (adminGroups != null) {
            getEditableObject().setLargeStringValue(ADMIN_GROUPS_FIELDNAME, adminGroups);
        }
    }
    
    @Override
    public void setRightsCheckMethod(String rightsCheckMethod) throws IndexException
    {
        if (rightsCheckMethod != null) {
            getEditableObject().setStringValue(RIGHTS_CHECK_METHOD_FIELDNAME, rightsCheckMethod);
        }
    }
    
    @Override
    public void setRightsCheckMethodParam(String rightsCheckMethodParam) throws IndexException
    {
        if (rightsCheckMethodParam != null) {
            getEditableObject().setStringValue(RIGHTS_CHECK_METHOD_PARAMETER_FIELDNAME, rightsCheckMethodParam);
        }
    }
    
    @Override
    public void save() throws IndexException
    {
        try {
            XWikiContext context = this.contextProvider.get();
            context.getWiki().saveDocument(this.xwikidocument, context);
        } catch (XWikiException e) {
            this.logger.error("Error saving collection: [{}]", e.getMessage());
        }
    }
    
    @Override
    public List<String> getDocuments()
    {
        List<String> documents = null;
        String documentClass = Document.XCLASS_SPACE_STRING + "." + Document.XCLASS_NAME;
        String templateDoc = Document.XCLASS_SPACE_STRING + ".CollectionsTemplate";
        String hql = "select prop.value from XWikiDocument doc, BaseObject obj, StringProperty prop "
                    + "where doc.fullName=obj.name and obj.className='" + documentClass
                    + "' and doc.fullName <> '" + templateDoc
                    + "' and obj.id = prop.id.id and prop.id.name = 'id'";
        try {
            Query query = queryManager.createQuery(hql, Query.HQL);
            documents = query.execute();
            return documents;
        } catch (QueryException e) {
            this.logger.error("Failed retrieving document list, [{}]", e.getMessage());
        }
        return documents;
    }
    
    @Override
    public Document getDocument(String documentId) throws IndexException
    {
        XWikiContext context = contextProvider.get();
        DocumentReference documentReference = getDocumentReference(documentId);
        try {
            XWikiDocument xwikiDoc = context.getWiki().getDocument(documentReference, context);
            if (!xwikiDoc.isNew()) {
                DefaultDocument document = this.documentProvider.get();
                document.initialize(xwikiDoc);
                return document;
            } else {
                return null;
            }
        } catch (XWikiException e) {
            throw new IndexException("Failed to get document [" + documentId + "]", e);
        }
    }
    
    @Override
    public Document newDocument(String documentId) throws IndexException
    {
        if (documentId == null) {
            throw new IndexException("Document ID cannot be null");
        }
        XWikiContext context = contextProvider.get();
        DocumentReference documentReference = getDocumentReference(documentId);
        try {
            XWikiDocument xwikiDoc = context.getWiki().getDocument(documentReference, context);
            DefaultDocument document = this.documentProvider.get();
            document.initialize(xwikiDoc);
            document.setID(documentId);
            document.setTitle(documentId);
            document.setCollection(this.getName());
            return document;
        } catch (XWikiException e) {
            throw new IndexException("Failed to create document [" + documentId + "]", e);
        }
    }
    
    @Override
    public void removeDocument(String documentId,
                                 boolean removeFromVectorDB,
                                 boolean removeFromStorage) throws IndexException
    {
        try {
            Document document = this.getDocument(documentId);
            if (removeFromVectorDB) {
                removeDocumentFromVectorDB(document);
            }
            if (removeFromStorage) {
                removeDocumentFromStorage(document);
            }
        } catch (Exception e) {
            logger.warn("Failed to remove document with id [{}]: [{}]", documentId, e.getMessage());
        }
    }

    private void removeDocumentFromVectorDB(Document document)
    {
        try {
            SolrConnector.deleteChunksByDocId(document.getID());
        } catch (Exception e) {
            logger.warn("Failed to remove document [{}] from vector database: [{}]",
                                 document, e.getMessage());
        }
    }

    private void removeDocumentFromStorage(Document document)
    {
        try {
            XWikiContext context = this.contextProvider.get();
            context.getWiki().deleteDocument(document.getXWikiDocument(), context);
        } catch (XWikiException e) {
            logger.warn("Failed to remove document [{}] from storage: [{}]",
                                 document, e.getMessage());
        }
    }
    
    DocumentReference getDocumentReference(String id)
    {
        SpaceReference lastSpaceReference = this.xwikidocument.getDocumentReference().getLastSpaceReference();
        SpaceReference documentReference = new SpaceReference("Documents", lastSpaceReference);
        return new DocumentReference(DigestUtils.sha256Hex(id), documentReference);
    }

    XWikiDocument getCollectionDocument()
    {
        return this.xwikidocument;
    }
}

