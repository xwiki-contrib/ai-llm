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
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.AuthorizationException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Wrapper for a collection to check rights for the current user.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = CurrentUserCollection.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class CurrentUserCollection extends DefaultCollection
{
    @Inject
    private ContextualAuthorizationManager contextualAuthorizationManager;

    @Inject
    private Provider<CurrentUserDocument> currentUserDocumentProvider;

    @Override
    public List<String> getDocuments()
    {
        return super.getDocuments().stream()
            .filter(documentId -> {
                DocumentReference documentReference = super.getDocumentReference(documentId);
                return this.contextualAuthorizationManager.hasAccess(Right.VIEW, documentReference);
            })
            .collect(Collectors.toList());
    }

    @Override
    public Document newDocument(String documentId) throws IndexException
    {
        DocumentReference documentReference = super.getDocumentReference(documentId);

        try {
            this.contextualAuthorizationManager.checkAccess(Right.EDIT, documentReference);
            CurrentUserDocument result = this.currentUserDocumentProvider.get();
            result.initialize(super.newDocument(documentId).getXWikiDocument());
            return result;
        } catch (AccessDeniedException e) {
            throw new IndexException(String.format("Access denied for creating document [%s]", documentId), e);
        }
    }

    @Override
    public Document getDocument(String documentId) throws IndexException
    {
        DocumentReference documentReference = super.getDocumentReference(documentId);

        try {
            this.contextualAuthorizationManager.checkAccess(Right.VIEW, documentReference);
            CurrentUserDocument result = this.currentUserDocumentProvider.get();
            result.initialize(super.getDocument(documentId).getXWikiDocument());
            return result;
        } catch (AccessDeniedException e) {
            throw new IndexException(String.format("Access denied to document [%s]", documentId), e);
        }
    }

    @Override
    public void removeDocument(String documentId, boolean removeFromVectorDB, boolean removeFromStorage)
        throws IndexException
    {
        DocumentReference documentReference = super.getDocumentReference(documentId);
        XWikiContext context = this.contextProvider.get();

        try {
            this.contextualAuthorizationManager.checkAccess(Right.DELETE, documentReference);
            XWikiDocument document = context.getWiki().getDocument(documentReference, context);
            // Ensure we're not modifying the potentially shared document instance.
            context.getWiki().checkDeletingDocument(context.getUserReference(), document.clone(), context);
            super.removeDocument(documentId, removeFromVectorDB, removeFromStorage);
        } catch (XWikiException | AccessDeniedException e) {
            throw new IndexException(String.format("Access denied for deleting document [%s]", documentId), e);
        }
    }

    @Override
    public void save() throws IndexException
    {
        XWikiContext context = this.contextProvider.get();
        try {
            XWikiDocument collectionDocument = super.getCollectionDocument();
            this.contextualAuthorizationManager.checkAccess(Right.EDIT, collectionDocument.getDocumentReference());
            // Ensure we're not modifying the potentially shared document instance.
            context.getWiki().checkSavingDocument(context.getUserReference(), collectionDocument.clone(), context);
        } catch (XWikiException | AuthorizationException e) {
            throw new IndexException(
                String.format("Access denied for saving collection [%s]", super.getName()), e);
        }
    }
}
