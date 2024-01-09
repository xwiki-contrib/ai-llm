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

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.Chunk;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.Utils;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.slf4j.Logger;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * Implementation of a {@code Document} component.
 *
 * @version $Id$
 */
@Component(roles = DefaultDocument.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
/**
 * DefaultDocument implements the Document interface to provide access to an XWikiDocument and related data. It allows
 * retrieving metadata like the document id, title, language, URL, MIME type, and content. The class handles
 * initializing itself with an XWikiDocument and XWikiContext. It provides implementations of Document methods to expose
 * document properties and content.
 */
public class DefaultDocument implements Document
{
    
    private static final String ID_KEY = "id";
    private static final String TITLE_KEY = "title";
    private static final String PARENT_COLLECTION = "collection";
    private static final String LANG_KEY = "language";
    private static final String URL_KEY = "URL";
    private static final String MIMETYPE_KEY = "mimetype";
    private static final String CONTENT_KEY = "content";
    private static final String XCLASS_NAME = "KiDocumentsClass";
    private static final String XCLASS_SPACE_STRING = "AILLMApp.KiDocuments.Code";

    @Inject 
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Inject
    private Utils utils;

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;

    private String id;
    private String title;
    private String collection;
    private String language;
    private String url;
    private String mimetype;
    private String content;
    
    private XWikiDocument xwikidocument;
    
    /**
     * Initializes the Document with empty fields.
     * @param id the id of the document.
     */
    public void initialize(String id)
    {
        this.id = id;
        this.title = "";
        this.collection = "";
        this.language = "";
        this.url = "";
        this.mimetype = "";
        this.content = "";
    }

    @Override
    public Map<String, String> getProperties()
    {
        Map<String, String> properties = new HashMap<>();
        properties.put(ID_KEY, id);
        properties.put(TITLE_KEY, title);
        properties.put(PARENT_COLLECTION, collection);
        properties.put(LANG_KEY, language);
        properties.put(URL_KEY, url);
        properties.put(MIMETYPE_KEY, mimetype);
        properties.put(CONTENT_KEY, content);
        return properties;
    }

    
    /**
     * Gets the property value based on the key.
     *
     * @param key the property key
     * @return an Optional containing the property value if present
     */
    public Optional<String> getProperty(String key)
    {
        Optional<String> property;
        switch (key) {
            case ID_KEY:
                property = Optional.ofNullable(id);
                break;
            case TITLE_KEY:
                property = Optional.ofNullable(title);
                break;
            case PARENT_COLLECTION:
                property = Optional.ofNullable(collection);
                break;
            case LANG_KEY:
                property = Optional.ofNullable(language);
                break;
            case URL_KEY:
                property = Optional.ofNullable(url);
                break;
            case MIMETYPE_KEY:
                property = Optional.ofNullable(mimetype);
                break;
            case CONTENT_KEY:
                property = Optional.ofNullable(content);
                break;
            default:
                property = Optional.empty();
                break;
        }
        return property;
    }
    @Override
    public String getID()
    {
        return id;
    }

    @Override
    public String getTitle()
    {
        return title;
    }
    
    @Override
    public String getCollection()
    {
        return collection;
    }

    @Override
    public String getLanguage()
    {
        return language;
    }

    @Override
    public String getURL()
    {
        return url;
    }

    @Override
    public String getMimetype()
    {
        return mimetype;
    }

    @Override
    public String getContent()
    {
        return content;
    }

    @Override
    public void setID(String id)
    {
        this.id = id;
    }

    @Override
    public void setTitle(String title)
    {
        this.title = title;
    }
    
    @Override
    public void setCollection(String collection)
    {
        this.collection = collection;
    }

    @Override
    public void setLanguage(String language)
    {
        this.language = language;
    }

    @Override
    public void setURL(String url)
    {
        this.url = url;
    }

    @Override
    public void setMimetype(String mimetype)
    {
        this.mimetype = mimetype;
    }

    @Override
    public void setContent(String content)
    {
        this.content = content;
    }

    @Override
    public Document toDocument(XWikiDocument document)
    {
        this.xwikidocument = document;
        EntityReference documentReference = this.xwikidocument.getDocumentReference();
        EntityReference objectEntityReference = new EntityReference(
            XCLASS_SPACE_STRING + "." + XCLASS_NAME,
            EntityType.OBJECT,
            documentReference
        );
        BaseObject object = this.xwikidocument.getXObject(objectEntityReference);
        this.title = this.xwikidocument.getTitle();
        this.content = this.xwikidocument.getContent();
        pullXObjectValues(object);

        return this;
    }
    
    // Pull collection properties from the XObject
    private void pullXObjectValues(BaseObject object)
    {
        this.id = object.getStringValue(ID_KEY);
        this.collection = object.getStringValue(PARENT_COLLECTION);
        this.language = object.getStringValue(LANG_KEY);
        this.url = object.getStringValue(URL_KEY);
        this.mimetype = object.getStringValue(MIMETYPE_KEY);
        
    }
    
    @Override
    public XWikiDocument toXWikiDocument(XWikiDocument inputdocument)
    {
        this.xwikidocument = inputdocument;
        XWikiContext context = this.contextProvider.get();

        //update an existing or new XWikiDocument with the document properties
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
        object.setStringValue(ID_KEY, this.id);
        object.setStringValue(TITLE_KEY, this.title);
        object.setStringValue(PARENT_COLLECTION, this.collection);
        object.setStringValue(LANG_KEY, this.language);
        object.setStringValue(URL_KEY, this.url);
        object.setStringValue(MIMETYPE_KEY, this.mimetype);
        object.setLargeStringValue(CONTENT_KEY, this.content);
        return object;
    }

    @Override
    public List<Chunk> chunkDocument()
    {
        Map<Integer, Chunk> chunks = utils.chunkDocument(this);
        if (chunks == null) {
            return new ArrayList<>();
        } else {
            return new ArrayList<>(chunks.values());
        }
    }
    
}
