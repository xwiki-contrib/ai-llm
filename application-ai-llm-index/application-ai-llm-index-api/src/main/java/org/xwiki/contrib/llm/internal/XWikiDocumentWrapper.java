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

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import org.slf4j.Logger;

/**
 * Wrapper around a {@link XWikiDocument} that exposes a single object of a given class. The document is
 * automatically cloned before any modification.
 *
 * @version $Id$
 * @since 0.3
 */
public class XWikiDocumentWrapper
{
    private final Provider<XWikiContext> contextProvider;

    private final XWikiDocument initialDocument;

    private final LocalDocumentReference classReference;

    private XWikiDocument currentDocument;

    private BaseObject object;

    @Inject
    private Logger logger;

    /**
     * Constructor.
     *
     * @param document the document to wrap
     * @param classReference the reference of the class of the object to expose
     * @param contextProvider the provider of the current XWiki context
     */
    public XWikiDocumentWrapper(XWikiDocument document, LocalDocumentReference classReference,
        Provider<XWikiContext> contextProvider)
    {
        this.contextProvider = contextProvider;
        this.initialDocument = document;
        this.currentDocument = document;
        this.classReference = classReference;
        this.object = document.getXObject(classReference);
    }

    /**
     * @return the title of the document
     */
    public String getTitle()
    {
        return this.currentDocument.getTitle();
    }

    /**
     * @param title the title of the document
     */
    public void setTitle(String title) throws IndexException
    {
        ensureDocumentIsCloneAndHasObject();

        this.currentDocument.setTitle(title);
    }

    /**
     * @return the content of the document
     */
    public String getContent()
    {
        List<XWikiAttachment> attachmentList = this.currentDocument.getAttachmentList();
        if (!attachmentList.isEmpty()) {
            try {
                StringBuilder totalContent = new StringBuilder();
                totalContent.append(this.currentDocument.getContent());
                for (XWikiAttachment xWikiAttachment : attachmentList) {
                    Tika tika = new Tika();
                    XWikiContext context = this.contextProvider.get();
                    String attachmentContent = tika.parseToString(xWikiAttachment.getContentInputStream(context));
                    totalContent.append("\n");
                    totalContent.append(attachmentContent);
                }
                return totalContent.toString();
            } catch (IOException | TikaException | XWikiException e) {
                logger.error("Failed to parse attachment content: {}", e.getMessage());
            }
        }
        return this.currentDocument.getContent();
    }

    /**
     * @param content the content of the document
     */
    public void setContent(String content) throws IndexException
    {
        ensureDocumentIsCloneAndHasObject();

        this.currentDocument.setContent(content);
    }

    /**
     * @return the document reference
     */
    public DocumentReference getDocumentReference()
    {
        return this.currentDocument.getDocumentReference();
    }

    /**
     * @param fieldName the name of the field
     * @return the int value of the field
     */
    public int getIntValue(String fieldName)
    {
        return this.object != null ? this.object.getIntValue(fieldName) : 0;
    }

    /**
     * @param fieldName the name of the field
     * @return the value of the field
     */
    public List<String> getListValue(String fieldName)
    {
        if (this.object != null) {
            List<?> listValue = this.object.getListValue(fieldName);
            // Safely convert the list to a list of strings.
            return listValue.stream().map(v -> (String) v).collect(Collectors.toList());
        } else {
            return List.of();
        }
    }

    /**
     * @param fieldName the name of the field
     * @return the large string value of the field
     */
    public String getLargeStringValue(String fieldName)
    {
        return this.object != null ? this.object.getLargeStringValue(fieldName) : "";
    }

    /**
     * @param fieldName the name of the field
     * @param value the value of the field
     */
    public void setIntValue(String fieldName, int value) throws IndexException
    {
        getEditableObject().setIntValue(fieldName, value);
    }

    /**
     * @param fieldName the name of the field
     * @param value the value of the field
     */
    public void setStringListValue(String fieldName, List<String> value) throws IndexException
    {
        getEditableObject().setStringListValue(fieldName, value);
    }

    /**
     * @param fieldName the name of the field
     * @param value the value of the field
     */
    public void setLargeStringValue(String fieldName, String value) throws IndexException
    {
        getEditableObject().setLargeStringValue(fieldName, value);
    }

    /**
     * @param fieldName the name of the field
     * @return the string value of the field
     */
    public String getStringValue(String fieldName)
    {
        return this.object != null ? this.object.getStringValue(fieldName) : "";
    }

    /**
     * @param fieldName the name of the field
     * @param value the value of the field
     */
    public void setStringValue(String fieldName, String value) throws IndexException
    {
        getEditableObject().setStringValue(fieldName, value);
    }

    /**
     * @return the wrapped document
     */
    public XWikiDocument getXWikiDocument()
    {
        return this.currentDocument;
    }

    /**
     * @return the wrapped document
     */
    public XWikiDocument getClonedXWikiDocument() throws IndexException
    {
        ensureDocumentIsCloneAndHasObject();

        return this.currentDocument;
    }

    private BaseObject getEditableObject() throws IndexException
    {
        ensureDocumentIsCloneAndHasObject();

        return this.object;
    }

    private void ensureDocumentIsCloneAndHasObject() throws IndexException
    {
        if (this.initialDocument == this.currentDocument) {
            this.currentDocument = this.initialDocument.clone();
            this.object = this.currentDocument.getXObject(this.classReference);

            if (this.object == null)
            {
                XWikiContext context = this.contextProvider.get();
                try {
                    this.object = this.currentDocument.newXObject(this.classReference, context);
                } catch (XWikiException e) {
                    throw new IndexException(String.format("Error initializing collection for document [%s].",
                        this.currentDocument.getDocumentReference()), e);
                }
            }
        }
    }
}
