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

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Implementation of a {@code Collection} component.
 *
 * @version $Id$
 */
@Component(roles = DefaultCollection.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class DefaultCollection implements Collection
{
    private static final String NAME_FIELDNAME = "name";
    private static final String EMBEDDINGMODEL_FIELDNAME = "embeddingModel";
    private static final String CHUNKING_METHOD_FIELDNAME = "chunkingMethod";
    private static final String CHUNKING_MAX_SIZE_FIELDNAME = "chunkingMaxSize";
    private static final String CHUNKING_OVERLAP_OFFSET_FIELDNAME = "chunkingOverlapOffset";
    private static final String PERMISSIONS_FIELDNAME = "permissions";
    private static final String RIGHTS_CHECK_METHOD_FIELDNAME = "rightsCheckMethod";
    private static final String RIGHTS_CHECK_METHOD_PARAMETER_FIELDNAME = "rightsCheckMethodParam";
    private static final String DOCUMENT_SPACE_FIELDNAME = "documentSpace";
    private static final String XCLASS_NAME = "CollectionsClass";
    private static final String XCLASS_SPACE_STRING = "AILLMApp.Collections.Code";

    private String name;
    private String embeddingModel;
    private String chunkingMethod;
    private String chunkingMaxSize;
    private String chunkingOverlapOffset;
    private String permissions;
    private String rightsCheckMethod;
    private String rightsCheckMethodParameter;
    private String documentSpace;
    private Map<String, Document> documents;
    private XWikiDocument xwikidocument;

    @Inject
    private Provider<DefaultDocument> documentProvider;

    @Inject 
    private Provider<XWikiContext> contextProvider;
    
    @Inject
    private Logger logger;
    
    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;

    /**
     * Default constructor for DefaultCollection.
     * Initializes the collection name, permissions, embedding model, 
     * and empty document map.
     *
     * @param name The name of the collection.
     */
    public void initialize(String name)
    {
        this.name = name;
        this.permissions = "view";
        this.embeddingModel = "bert";
        this.chunkingMethod = "maxTokens";
        this.chunkingMaxSize = "1000";
        this.chunkingOverlapOffset = "0";
        this.rightsCheckMethod = "jwt";
        this.rightsCheckMethodParameter = "";
        this.documentSpace = "AILLMApp.Collections." + this.name + ".Documents";
        this.documents = new HashMap<>();
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public List<Document> getDocumentList()
    {
        return new ArrayList<>(documents.values());
    }

    @Override
    public Document getDocument(String id)
    {
        return documents.get(id);
    }

    @Override
    public String getPermissions()
    {
        return permissions;
    }

    @Override
    public String getEmbeddingModel()
    {
        return embeddingModel;
    }

    @Override
    public boolean setName(String name)
    {
        if (name != null) {
            this.name = name;
            return true;
        }
        return false;
    }

    @Override
    public boolean setPermissions(String permissions)
    {
        if (permissions != null) {
            this.permissions = permissions;
            return true;
        }
        return false;
    }

    @Override
    public boolean setEmbeddingModel(String embeddingModel)
    {
        if (embeddingModel != null) {
            this.embeddingModel = embeddingModel;
            return true;
        }
        return false;
    }

    @Override
    public boolean removeDocument(String id, boolean deleteDocument)
    {
        if (documents.containsKey(id)) {
            if (deleteDocument) {
                // Implement document deletion logic here if needed
            }
            documents.remove(id);
            return true;
        }
        return false;
    }

    @Override
    public void assignIdToDocument(Document document, String id)
    {
        // check if the document already exists
        documents.put(id, document);
    }

    @Override
    public Document createDocument() throws XWikiException
    {
        String uniqueId = generateUniqueId();
        DefaultDocument newDocument = documentProvider.get();
        newDocument.initialize(uniqueId);
        documents.put(uniqueId, newDocument);
        return newDocument;
    }

    @Override
    public Document createDocument(String id) throws XWikiException
    {
        DefaultDocument newDocument = documentProvider.get();
        newDocument.initialize(id);
        documents.put(id, newDocument);
        return newDocument;
    }

    private String generateUniqueId()
    {
        return "document" + (documents.size() + 1);
    }
    
    @Override
    public Collection fromXWikiDocument(XWikiDocument document)
    {
        this.xwikidocument = document;
        EntityReference documentReference = this.xwikidocument.getDocumentReference();
        EntityReference objectEntityReference = new EntityReference(
            XCLASS_SPACE_STRING + "." + XCLASS_NAME,
            EntityType.OBJECT,
            documentReference
        );
        BaseObject object = this.xwikidocument.getXObject(objectEntityReference);
        pullXObjectValues(object);
        
        return this;
    }
    
    // Pull collection properties from the XObject
    private void pullXObjectValues(BaseObject object)
    {
        this.name = object.getStringValue(NAME_FIELDNAME);
        this.embeddingModel = object.getStringValue(EMBEDDINGMODEL_FIELDNAME);
        this.chunkingMethod = object.getStringValue(CHUNKING_METHOD_FIELDNAME);
        this.chunkingMaxSize = object.getStringValue(CHUNKING_MAX_SIZE_FIELDNAME);
        this.chunkingOverlapOffset = object.getStringValue(CHUNKING_OVERLAP_OFFSET_FIELDNAME);
        this.permissions = object.getStringValue(PERMISSIONS_FIELDNAME);
        this.rightsCheckMethod = object.getStringValue(RIGHTS_CHECK_METHOD_FIELDNAME);
        this.rightsCheckMethodParameter = object.getStringValue(RIGHTS_CHECK_METHOD_PARAMETER_FIELDNAME);
        this.documentSpace = object.getStringValue(DOCUMENT_SPACE_FIELDNAME);
    
    }

    @Override
    public XWikiDocument toXWikiDocument(XWikiDocument inputdocument)
    {
        this.xwikidocument = inputdocument;
        XWikiContext context = this.contextProvider.get();

        //update an existing or new XWikiDocument with the collection properties
        try {
            EntityReference objectEntityReference = getObjectReference();
            BaseObject object = this.xwikidocument.getXObject(objectEntityReference);
            if (object == null) {
                //create new xobject
                object = this.xwikidocument.newXObject(objectEntityReference, context);
                setXObjectValues(object);
            }
        } catch (Exception e) {
            logger.error("Error setting the object: {}", e.getMessage());
            e.printStackTrace();
            return null;
        }

        //save the XWikiDocument
        try {
            context.getWiki().saveDocument(this.xwikidocument, context);
            return this.xwikidocument;
        } catch (XWikiException e) {
            logger.error("Error saving document: {}", e.getMessage());
            e.printStackTrace();
            return null;
        }

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

    //update the XObject with the collection properties
    private BaseObject setXObjectValues(BaseObject object)
    {
        object.setStringValue(NAME_FIELDNAME, this.name);
        object.setStringValue(EMBEDDINGMODEL_FIELDNAME, this.embeddingModel);
        object.setStringValue(CHUNKING_METHOD_FIELDNAME, this.chunkingMethod);
        object.setStringValue(CHUNKING_MAX_SIZE_FIELDNAME, this.chunkingMaxSize);
        object.setStringValue(CHUNKING_OVERLAP_OFFSET_FIELDNAME, this.chunkingOverlapOffset);
        object.setStringValue(PERMISSIONS_FIELDNAME, this.permissions);
        object.setStringValue(RIGHTS_CHECK_METHOD_FIELDNAME, this.rightsCheckMethod);
        object.setStringValue(RIGHTS_CHECK_METHOD_PARAMETER_FIELDNAME, this.rightsCheckMethodParameter);
        object.setStringValue(DOCUMENT_SPACE_FIELDNAME, this.documentSpace);
        return object;
    }

}

