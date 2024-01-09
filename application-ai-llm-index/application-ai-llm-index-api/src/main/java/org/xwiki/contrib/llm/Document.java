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
package org.xwiki.contrib.llm;

import java.util.List;
import java.util.Optional;

import org.xwiki.component.annotation.Role;

import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Represents a document within the WAISE collection in the AI-LLM indexing system.
 *
 * @version $Id$
 */
@Role
public interface Document 
{
    /**
     * Retrieves the unique identifier of the document.
     *
     * @return the document's ID
     */
    String getID();

    /**
     * Retrieves the title of the document.
     *
     * @return the document's title
     */
    String getTitle();

    /**
     * Retrieves the language of the document.
     *
     * @return the document's language
     */
    String getLanguage();

    /**
     * Retrieves the URL of the document.
     * 
     * @return the document's URL
     */
    String getURL();

    /**
     * Retrieves the collection of the document.
     *
     * @return the document's collection
     */
    String getCollection();

    /**
     * Retrieves the mimetype of the document.
     *
     * @return the document's mimetype
     */
    String getMimetype();

    /**
     * Retrieves the content of the document.
     *
     * @return the document's content
     */
    String getContent();

    /**
     * Sets the id of the document.
     *
     * @param id The new id
     */
    void setID(String id);

    /**
     * Sets the title of the document.
     *
     * @param title The new title
     */
    void setTitle(String title);

    /**
     * Sets the language of the document.
     *
     * @param lang The new language
     */
    void setLanguage(String lang);

    /**
     * Sets the URL of the document.
     *
     * @param url The new URL
     */
    void setURL(String url);

    /**
     * Sets the collection of the document.
     *
     * @param collection The new collection
     */
    void setCollection(String collection);

    /**
     * Sets the mimetype of the document.
     *
     * @param mimetype The new mimetype
     */
    void setMimetype(String mimetype);

    /**
     * Sets the content of the document.
     * 
     * @param content The new content
     */
    void setContent(String content);

    /**
     * Process the document and chunk it into smaller pieces.
     * 
     * @return a list of chunks of the document
     */
    List<Chunk> chunkDocument();
    
    /**
     * Retrieves the properties of the document.
     *
     * @return the document's properties
     */
    java.util.Map<String, String> getProperties();

    /**
     * Retrieves a specific property of the document.
     * 
     * @param key The property key
     * @return The property value if present
     */
    Optional<String> getProperty(String key);

    /**
     * Sets the properteis of a document based on the properties of the specified XWiki document's object.
     * 
     * @param xwikiDocument
     * @return The updated document.
     */
    Document toDocument(XWikiDocument xwikiDocument);

    /**
     * Sets the properties of a XWiki document's object based on the properties of the document.
     * 
     * @param xwikiDocument The XWiki document to update.
     * @return The updated XWiki document.
     */
    XWikiDocument toXWikiDocument(XWikiDocument xwikiDocument);

}
