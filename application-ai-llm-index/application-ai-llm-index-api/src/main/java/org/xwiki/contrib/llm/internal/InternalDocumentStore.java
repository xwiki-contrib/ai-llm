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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.utils.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * A {@link DocumentStore} that stores documents in a space below the collection itself.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
@Named("internal")
public class InternalDocumentStore implements DocumentStore
{
    private static final String DOCUMENT_CLASS = Document.XCLASS_SPACE_STRING + "." + Document.XCLASS_NAME;

    private static final String TEMPLATE_DOC = Document.XCLASS_SPACE_STRING + ".DocumentsTemplate";

    @Inject
    private QueryManager queryManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Provider<DefaultDocument> documentProvider;

    @Inject
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @Inject
    private AuthorizationManager authorizationManager;

    private Collection collection;

    private DocumentReference documentUserReference;

    private UserReference userReference;

    @Override
    public void initialize(Collection collection, UserReference userReference) throws IndexException
    {
        this.collection = collection;
        this.userReference = userReference;

        if (userReference != null) {
            this.documentUserReference = this.userReferenceSerializer.serialize(userReference);
        }
    }

    @Override
    public List<String> getDocumentNames(int offset, int limit) throws IndexException
    {
        try {
            String hql =
                """
                    select prop.value from XWikiDocument doc, BaseObject obj, StringProperty prop,
                    StringProperty collectionProp where doc.fullName=obj.name and obj.className='%s'
                    and doc.fullName <> '%s' and obj.id = prop.id.id and prop.id.name = 'id'
                    and obj.id = collectionProp.id.id and collectionProp.id.name = 'collection'
                    and collectionProp.value = :collection
                    """
                    .formatted(DOCUMENT_CLASS, TEMPLATE_DOC);

            Query query = this.queryManager.createQuery(hql, Query.HQL);
            query.setWiki(this.collection.getDocumentReference().getWikiReference().getName());
            query.bindValue("collection", this.collection.getID());
            if (limit > -1) {
                query.setLimit(limit);
            }
            query.setOffset(offset);
            List<String> results = query.execute();
            if (isUserSet()) {
                results = results.stream()
                    .filter(documentName -> {
                        DocumentReference documentReference = getDocumentReference(documentName);
                        return this.authorizationManager.hasAccess(Right.VIEW, this.documentUserReference,
                            documentReference);
                    })
                    .toList();
            }
            return results;
        } catch (QueryException e) {
            throw new IndexException("Failed to get documents from collection " + this.collection.getID(), e);
        }
    }

    private boolean isUserSet()
    {
        return this.userReference != null;
    }

    @Override
    public Document getDocument(String name) throws IndexException, AccessDeniedException
    {
        XWikiContext context = this.contextProvider.get();
        DocumentReference documentReference = getDocumentReference(name);
        if (isUserSet()) {
            this.authorizationManager.checkAccess(Right.VIEW, this.documentUserReference, documentReference);
        }

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
            throw new IndexException("Failed to get document [" + name + "]", e);
        }
    }

    @Override
    public Document createDocument(String name) throws IndexException, AccessDeniedException
    {
        if (StringUtils.isBlank(name)) {
            throw new IndexException("Document ID cannot be blank");
        }
        XWikiContext context = this.contextProvider.get();
        DocumentReference documentReference = getDocumentReference(name);
        if (isUserSet()) {
            this.authorizationManager.checkAccess(Right.EDIT, this.documentUserReference, documentReference);
        }
        try {
            XWikiDocument xwikiDoc = context.getWiki().getDocument(documentReference, context);
            DefaultDocument document = this.documentProvider.get();
            document.initialize(xwikiDoc);
            document.setID(name);
            document.setTitle(name);
            document.setCollection(this.collection.getID());
            return document;
        } catch (XWikiException e) {
            throw new IndexException("Failed to create document [" + name + "]", e);
        }
    }

    @Override
    public void saveDocument(Document document) throws IndexException, AccessDeniedException
    {
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument xWikiDocument = document.getXWikiDocument();

            if (isUserSet()) {
                this.authorizationManager.checkAccess(Right.EDIT, this.documentUserReference,
                    xWikiDocument.getDocumentReference());
                // Check saving. This could modify the document.
                context.getWiki().checkSavingDocument(context.getUserReference(), xWikiDocument, context);
            }

            context.getWiki().saveDocument(xWikiDocument, context);
        } catch (XWikiException e) {
            throw new IndexException(String.format("Failed to save document with id [%s]: ", document.getID()), e);
        }

    }

    @Override
    public void deleteDocument(Document document) throws IndexException, AccessDeniedException
    {
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument xWikiDocument = document.getXWikiDocument();
            if (isUserSet()) {
                this.authorizationManager.checkAccess(Right.DELETE, this.documentUserReference,
                    xWikiDocument.getDocumentReference());

                context.getWiki().checkDeletingDocument(context.getUserReference(), xWikiDocument, context);
            }

            context.getWiki().deleteDocument(xWikiDocument, context);
        } catch (XWikiException e) {
            throw new IndexException(String.format("Failed to delete document with id [%s]: ", document.getID()), e);
        }
    }

    private DocumentReference getDocumentReference(String id)
    {
        SpaceReference lastSpaceReference = this.collection.getDocumentReference().getLastSpaceReference();
        SpaceReference documentsSpaceReference = new SpaceReference("Documents", lastSpaceReference);
        return new DocumentReference(DigestUtils.sha256Hex(id), documentsSpaceReference);
    }

}
