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
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.Chunk;
import org.xwiki.contrib.llm.ChunkingUtils;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.IndexException;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

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
    /**
     * The name of the "id" property.
     */
    public static final String ID_KEY = "id";

    /**
     * The name of the "collection" property.
     */
    public static final String PARENT_COLLECTION = "collection";

    /**
     * The name of the language property.
     */
    public static final String LANG_KEY = "language";

    /**
     * The name of the URL property.
     */
    public static final String URL_KEY = "URL";

    /**
     * The name of the MIME type property.
     */
    public static final String MIMETYPE_KEY = "mimetype";

    @Inject 
    protected Provider<XWikiContext> contextProvider;

    protected XWikiDocumentWrapper xwikiDocumentWrapper;

    @Inject
    private ChunkingUtils chunkingUtils;

    /**
     * Initializes the Document with empty fields.
     * @param xwikidocument the id of the document.
     */
    public void initialize(XWikiDocument xwikidocument)
    {
        this.xwikiDocumentWrapper = new XWikiDocumentWrapper(xwikidocument, XCLASS_REFERENCE, this.contextProvider);
    }

    @Override
    public XWikiDocument getXWikiDocument() throws IndexException
    {
        return this.xwikiDocumentWrapper.getClonedXWikiDocument();
    }

    @Override
    public String getID()
    {
        return this.xwikiDocumentWrapper.getStringValue(ID_KEY);
    }

    @Override
    public String getTitle()
    {
        return this.xwikiDocumentWrapper.getTitle();
    }
    
    @Override
    public String getCollection()
    {
        return this.xwikiDocumentWrapper.getStringValue(PARENT_COLLECTION);
    }

    @Override
    public String getLanguage()
    {
        return this.xwikiDocumentWrapper.getStringValue(LANG_KEY);
    }

    @Override
    public String getURL()
    {
        return this.xwikiDocumentWrapper.getStringValue(URL_KEY);
    }

    @Override
    public String getMimetype()
    {
        return this.xwikiDocumentWrapper.getStringValue(MIMETYPE_KEY);
    }

    @Override
    public String getContent()
    {
        return this.xwikiDocumentWrapper.getContent();
    }

    @Override
    public void setID(String id) throws IndexException
    {
        this.xwikiDocumentWrapper.setStringValue(ID_KEY, id);
    }

    @Override
    public void setTitle(String title) throws IndexException
    {
        this.xwikiDocumentWrapper.setTitle(title);
    }
    
    @Override
    public void setCollection(String collection) throws IndexException
    {
        this.xwikiDocumentWrapper.setStringValue(PARENT_COLLECTION, collection);
    }

    @Override
    public void setLanguage(String language) throws IndexException
    {
        this.xwikiDocumentWrapper.setStringValue(LANG_KEY, language);
    }

    @Override
    public void setURL(String url) throws IndexException
    {
        this.xwikiDocumentWrapper.setStringValue(URL_KEY, url);
    }

    @Override
    public void setMimetype(String mimetype) throws IndexException
    {
        this.xwikiDocumentWrapper.setStringValue(MIMETYPE_KEY, mimetype);
    }

    @Override
    public void setContent(String content) throws IndexException
    {
        this.xwikiDocumentWrapper.setContent(content);
    }

    @Override
    public List<Chunk> chunkDocument()
    {
        Map<Integer, Chunk> chunks = chunkingUtils.chunkDocument(this);
        if (chunks == null) {
            return List.of();
        } else {
            return new ArrayList<>(chunks.values());
        }
    }
}
