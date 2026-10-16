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
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.IndexTaskConsumer;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.AuthorizationException;
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
@Named(InternalDocumentStore.NAME)
public class InternalDocumentStore implements DocumentStore
{
    /**
     * The name of this document store.
     */
    public static final String NAME = "internal";

    private static final String DOCUMENT_CLASS = Document.XCLASS_SPACE_STRING + "." + Document.XCLASS_NAME;

    private static final String DOCUMENTS_SPACE_NAME = "Documents";

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Provider<DefaultDocument> documentProvider;

    @Inject
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private WriteAttribution writeAttribution;

    private Collection collection;

    private DocumentReference documentUserReference;

    private UserReference userReference;

    private boolean initialized;

    /**
     * {@inheritDoc}
     *
     * <p>This method may be called once.</p>
     */
    @Override
    public void initialize(Collection collection, UserReference userReference) throws IndexException
    {
        if (this.initialized) {
            throw new IllegalStateException("This document store is already initialized.");
        }

        this.collection = collection;
        this.userReference = userReference;

        if (userReference != null) {
            this.documentUserReference = this.userReferenceSerializer.serialize(userReference);
        }
        this.initialized = true;
    }

    @Override
    public List<String> getDocumentNames(int offset, int limit) throws IndexException
    {
        try {
            // Only the pages of the Documents space of the collection are its documents: the space binding keeps a
            // documents object placed on any other page, of this or another collection, out of the listing.
            String hql =
                """
                    select prop.value from XWikiDocument doc, BaseObject obj, StringProperty prop,
                    StringProperty collectionProp where doc.fullName=obj.name and obj.className='%s'
                    and doc.space = :space and obj.id = prop.id.id and prop.id.name = 'id'
                    and obj.id = collectionProp.id.id and collectionProp.id.name = 'collection'
                    and collectionProp.value = :collection
                    """
                    .formatted(DOCUMENT_CLASS);

            Query query = this.queryManager.createQuery(hql, Query.HQL);
            query.setWiki(this.collection.getDocumentReference().getWikiReference().getName());
            query.bindValue("space", this.localEntityReferenceSerializer.serialize(
                getDocumentsSpaceReference(this.collection.getDocumentReference().getLastSpaceReference())));
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

    @Override
    public List<DocumentReference> getDocumentReferences(int offset, int limit) throws IndexException
    {
        return getDocumentNames(offset, limit).stream()
            .map(this::getDocumentReference)
            .toList();
    }

    @Override
    public Optional<String> getTaskConsumerHint()
    {
        return Optional.of(IndexTaskConsumer.NAME);
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
            this.writeAttribution.checkAccess(Right.EDIT, documentReference, this.userReference,
                this.documentUserReference);
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
        XWikiContext context = this.contextProvider.get();
        XWikiDocument xWikiDocument = getPage(document);
        try {
            DocumentReference actor = getActor(context);
            DocumentReference effectiveAuthor = actor;
            if (isUserSet()) {
                effectiveAuthor = this.writeAttribution.checkAccess(Right.EDIT, xWikiDocument.getDocumentReference(),
                    this.userReference, this.documentUserReference);
            }
            // A save with neither a user nor a context user, which only background code makes, keeps the authors of
            // the page.
            if (isUserSet() || actor != null) {
                this.writeAttribution.prepareSave(xWikiDocument, actor, effectiveAuthor);
            }
            if (isUserSet()) {
                // Check saving. This could modify the document.
                context.getWiki().checkSavingDocument(effectiveAuthor, xWikiDocument, context);
            }

            context.getWiki().saveDocument(xWikiDocument, context);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (XWikiException | AuthorizationException e) {
            throw new IndexException(String.format("Failed to save document with id [%s]: ", document.getID()), e);
        }
    }

    @Override
    public void deleteDocument(Document document) throws IndexException, AccessDeniedException
    {
        XWikiContext context = this.contextProvider.get();
        XWikiDocument xWikiDocument = getPage(document);
        try {
            if (isUserSet()) {
                DocumentReference effectiveAuthor = this.writeAttribution.checkAccess(Right.DELETE,
                    xWikiDocument.getDocumentReference(), this.userReference, this.documentUserReference);
                context.getWiki().checkDeletingDocument(effectiveAuthor, xWikiDocument, context);
            }

            context.getWiki().deleteDocument(xWikiDocument, context);
        } catch (XWikiException e) {
            throw new IndexException(String.format("Failed to delete document with id [%s]: ", document.getID()), e);
        }
    }

    /**
     * @return the user the store acts for: the user it was initialized with, otherwise the context user
     */
    private DocumentReference getActor(XWikiContext context)
    {
        return isUserSet() ? this.documentUserReference : context.getUserReference();
    }

    /**
     * @return the page of the document, which the store writes
     * @throws IndexException if the document is not one of this store or the page is not the page of the document
     */
    private XWikiDocument getPage(Document document) throws IndexException
    {
        if (!(document instanceof DefaultDocument defaultDocument)) {
            throw new IndexException(
                String.format("The document [%s] is not managed by this document store", document.getID()));
        }
        XWikiDocument xWikiDocument = defaultDocument.getClonedXWikiDocument();
        checkDocumentReference(document, xWikiDocument);
        return xWikiDocument;
    }

    /**
     * Check that the page is the page of the document in this store, so that the store only ever saves or deletes
     * the documents of its collection.
     */
    private void checkDocumentReference(Document document, XWikiDocument xWikiDocument) throws IndexException
    {
        DocumentReference expected = getDocumentReference(document.getID());
        if (!expected.equals(xWikiDocument.getDocumentReference())) {
            throw new IndexException(String.format("The page [%s] is not the document [%s] of collection [%s]",
                xWikiDocument.getDocumentReference(), document.getID(), this.collection.getID()));
        }
    }

    /**
     * Build the reference of the page that stores a document of a collection in this store. A document with the id
     * {@code documentId} of the collection {@code collectionId} is stored in the page
     * {@code <wikiId>:AI.Collections.<collectionId>.Documents.<sha256Hex(documentId)>}. Only that page is the
     * document of the collection; a documents object on any other page does not belong to this store.
     *
     * @param wikiId the id of the wiki of the collection
     * @param collectionId the id of the collection
     * @param documentId the id of the document
     * @return the reference of the page that stores the document
     * @since 0.11
     */
    public static DocumentReference getDocumentReference(String wikiId, String collectionId, String documentId)
    {
        SpaceReference collectionSpaceReference =
            new SpaceReference(collectionId, new SpaceReference(wikiId, Collection.DEFAULT_COLLECTION_SPACE));
        return getDocumentReference(collectionSpaceReference, documentId);
    }

    private static DocumentReference getDocumentReference(SpaceReference collectionSpaceReference, String documentId)
    {
        return new DocumentReference(DigestUtils.sha256Hex(documentId),
            getDocumentsSpaceReference(collectionSpaceReference));
    }

    /**
     * Build the reference of the space that stores the documents of a collection in this store: the
     * {@code Documents} space below the page of the collection, see
     * {@link #getDocumentReference(String, String, String)}.
     *
     * @param wikiId the id of the wiki of the collection
     * @param collectionId the id of the collection
     * @return the reference of the space that stores the documents of the collection
     * @since 0.11
     */
    public static SpaceReference getDocumentsSpaceReference(String wikiId, String collectionId)
    {
        return getDocumentsSpaceReference(
            new SpaceReference(collectionId, new SpaceReference(wikiId, Collection.DEFAULT_COLLECTION_SPACE)));
    }

    private static SpaceReference getDocumentsSpaceReference(SpaceReference collectionSpaceReference)
    {
        return new SpaceReference(DOCUMENTS_SPACE_NAME, collectionSpaceReference);
    }

    private DocumentReference getDocumentReference(String id)
    {
        return getDocumentReference(this.collection.getDocumentReference().getLastSpaceReference(), id);
    }
}
