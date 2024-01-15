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
import java.util.List;
import java.util.Map;

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
    private static final String PARENT_COLLECTION = "collection";
    private static final String LANG_KEY = "language";
    private static final String URL_KEY = "URL";
    private static final String MIMETYPE_KEY = "mimetype";
    private static final String CONTENT_KEY = "content";


    @Inject 
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Inject
    private Utils utils;

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;

    private XWikiDocument xwikidocument;
    private BaseObject object;
    
    /**
     * Initializes the Document with empty fields.
     * @param xwikidocument the id of the document.
     */
    public void initialize(XWikiDocument xwikidocument)
    {
        this.xwikidocument = xwikidocument;
        this.object = xwikidocument.getXObject(getObjectReference());
        if (this.object == null)
        {
            XWikiContext context = contextProvider.get();
            try {
                this.object = xwikidocument.newXObject(getObjectReference(), context);
            } catch (XWikiException e) {
                e.printStackTrace();
            }
        }
    }
    
    @Override
    public String getID()
    {
        return this.object.getStringValue(ID_KEY);
    }

    @Override
    public String getTitle()
    {
        return this.xwikidocument.getTitle();
    }
    
    @Override
    public String getCollection()
    {
        return object.getStringValue(PARENT_COLLECTION);
    }

    @Override
    public String getLanguage()
    {
        return object.getStringValue(LANG_KEY);
    }

    @Override
    public String getURL()
    {
        return this.object.getStringValue(URL_KEY);
    }

    @Override
    public String getMimetype()
    {
        return this.object.getStringValue(MIMETYPE_KEY);
    }

    @Override
    public String getContent()
    {
        return this.xwikidocument.getContent();
    }

    @Override
    public void setID(String id)
    {
        this.object.setStringValue(ID_KEY, id);
    }

    @Override
    public void setTitle(String title)
    {
        this.xwikidocument.setTitle(title);
    }
    
    @Override
    public void setCollection(String collection)
    {
        this.object.setStringValue(PARENT_COLLECTION, collection);
    }

    @Override
    public void setLanguage(String language)
    {
        this.object.setStringValue(LANG_KEY, language);
    }

    @Override
    public void setURL(String url)
    {
        this.object.setStringValue(URL_KEY, url);
    }

    @Override
    public void setMimetype(String mimetype)
    {
        this.object.setStringValue(MIMETYPE_KEY, mimetype);
    }

    @Override
    public void setContent(String content)
    {
        this.object.setLargeStringValue(CONTENT_KEY, content);
    }

    @Override
    public boolean save()
    {
        try {
            XWikiContext context = this.contextProvider.get();
            context.getWiki().saveDocument(this.xwikidocument, context);
            return true;
        } catch (XWikiException e) {
            logger.error("Error saving document: {}", e.getMessage());
            e.printStackTrace();
            return false;
        }
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
