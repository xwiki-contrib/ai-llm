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
package org.xwiki.contrib.llm.rest;

import org.xwiki.contrib.llm.Document;
import org.xwiki.stability.Unstable;

/**
 * Document representation for the REST API.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
public class JSONDocument
{
    private String id;

    private String title;

    private String language;

    private String url;

    private String collection;

    private String mimetype;

    private String content;

    /**
     * Construct a document from a {@link Document}.
     *
     * @param document the document to construct from
     */
    public JSONDocument(Document document)
    {
        this.id = document.getID();
        this.title = document.getTitle();
        this.language = document.getLanguage();
        this.url = document.getURL();
        this.collection = document.getCollection();
        this.mimetype = document.getMimetype();
        this.content = document.getContent();
    }

    /**
     * Applies the non-null properties of this document to a {@link Document}.
     *
     * @param document the document to apply to
     */
    public void applyTo(Document document)
    {
        if (this.title != null) {
            document.setTitle(this.title);
        }
        if (this.language != null) {
            document.setLanguage(this.language);
        }
        if (this.url != null) {
            document.setURL(this.url);
        }
        if (this.mimetype != null) {
            document.setMimetype(this.mimetype);
        }
        if (this.content != null) {
            document.setContent(this.content);
        }
    }

    /**
     * @return the id of the document
     */
    public String getId()
    {
        return this.id;
    }

    /**
     * @param id the id of the document
     */
    public void setId(String id)
    {
        this.id = id;
    }

    /**
     * @return the title of the document
     */
    public String getTitle()
    {
        return this.title;
    }

    /**
     * @param title the title of the document
     */
    public void setTitle(String title)
    {
        this.title = title;
    }

    /**
     * @return the language of the document
     */
    public String getLanguage()
    {
        return this.language;
    }

    /**
     * @param language the language of the document
     */
    public void setLanguage(String language)
    {
        this.language = language;
    }

    /**
     * @return the URL of the document
     */
    public String getUrl()
    {
        return this.url;
    }

    /**
     * @param url the URL of the document
     */
    public void setUrl(String url)
    {
        this.url = url;
    }

    /**
     * @return the collection of the document
     */
    public String getCollection()
    {
        return this.collection;
    }

    /**
     * @param collection the collection of the document
     */
    public void setCollection(String collection)
    {
        this.collection = collection;
    }

    /**
     * @return the mimetype of the document
     */
    public String getMimetype()
    {
        return this.mimetype;
    }

    /**
     * @param mimetype the mimetype of the document
     */
    public void setMimetype(String mimetype)
    {
        this.mimetype = mimetype;
    }

    /**
     * @return the content of the document
     */
    public String getContent()
    {
        return this.content;
    }

    /**
     * @param content the content of the document
     */
    public void setContent(String content)
    {
        this.content = content;
    }
}
