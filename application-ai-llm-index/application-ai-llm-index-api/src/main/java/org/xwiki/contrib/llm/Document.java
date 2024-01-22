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

import org.xwiki.component.annotation.Role;
import org.xwiki.model.reference.LocalDocumentReference;

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
     * The name of the XClass that represents a document.
     */
    String XCLASS_NAME = "DocumentsClass";
    /**
     * The space of the XClass that represents a document.
     */
    String XCLASS_SPACE_STRING = "AI.Documents.Code";

    /**
     * Document reference of the XClass that represents a document.
     */
    LocalDocumentReference XCLASS_REFERENCE = new LocalDocumentReference(List.of("AI", "Documents", "Code"),
        XCLASS_NAME);

    /**
     * The get the XWikiDocument that represents the document.
     * 
     * @return the document's XWikiDocument
     */
    XWikiDocument getXWikiDocument();

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
    void setID(String id) throws IndexException;

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
    void setLanguage(String lang) throws IndexException;

    /**
     * Sets the URL of the document.
     *
     * @param url The new URL
     */
    void setURL(String url) throws IndexException;

    /**
     * Sets the collection of the document.
     *
     * @param collection The new collection
     */
    void setCollection(String collection) throws IndexException;

    /**
     * Sets the mimetype of the document.
     *
     * @param mimetype The new mimetype
     */
    void setMimetype(String mimetype) throws IndexException;

    /**
     * Sets the content of the document.
     * 
     * @param content The new content
     */
    void setContent(String content);

    /**
     * Saves the document.
     *
     * @return true if the document was saved successfully, false otherwise
     */
    boolean save() throws IndexException;

    /**
     * Process the document and chunk it into smaller pieces.
     * 
     * @return a list of chunks of the document
     */
    List<Chunk> chunkDocument();

}
