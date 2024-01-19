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
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.AuthorizationException;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Implementation of {@code CollectionManager} that checks the current user's rights.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Singleton
@Named("currentUser")
public class CurrentUserCollectionManager extends DefaultCollectionManager
{
    @Inject
    private ContextualAuthorizationManager contextualAuthorizationManager;

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private Provider<CurrentUserCollection> currentUserCollectionProvider;

    @Override
    public DefaultCollection createCollection(String fullName) throws IndexException
    {
        try {
            this.contextualAuthorizationManager.checkAccess(Right.EDIT, getDocumentReference(fullName));
            DefaultCollection collection = super.createCollection(fullName);
            CurrentUserCollection currentUserCollection = this.currentUserCollectionProvider.get();
            currentUserCollection.initialize(collection.getCollectionDocument());
            return currentUserCollection;
        } catch (AuthorizationException e) {
            throw new IndexException("You do not have the right to create a collection", e);
        }
    }

    @Override
    public List<String> getCollections() throws IndexException
    {
        return super.getCollections().stream()
            .filter(collection ->
                this.contextualAuthorizationManager.hasAccess(Right.VIEW, getDocumentReference(collection)))
            .collect(Collectors.toList());
    }

    @Override
    public DefaultCollection getCollection(String name) throws IndexException
    {
        try {
            this.contextualAuthorizationManager.checkAccess(Right.VIEW, getDocumentReference(name));
            DefaultCollection collection = super.getCollection(name);
            CurrentUserCollection currentUserCollection = this.currentUserCollectionProvider.get();
            currentUserCollection.initialize(collection.getCollectionDocument());
            return currentUserCollection;
        } catch (AuthorizationException e) {
            throw new IndexException("You do not have the right to view this collection", e);
        }
    }

    @Override
    public void deleteCollection(String name, boolean deleteDocuments) throws IndexException
    {
        DocumentReference documentReference = getDocumentReference(name);
        XWikiContext context = this.contextProvider.get();

        try {
            this.contextualAuthorizationManager.checkAccess(Right.DELETE, documentReference);
            XWikiDocument document = context.getWiki().getDocument(documentReference, context);
            context.getWiki().checkDeletingDocument(context.getUserReference(), document, context);
            super.deleteCollection(name, deleteDocuments);
        } catch (XWikiException | AuthorizationException e) {
            throw new IndexException("You do not have the right to delete this collection", e);
        }
    }

    @Override
    public void clearIndexCore() throws IndexException
    {
        try {
            XWikiContext context = this.contextProvider.get();
            this.authorizationManager.checkAccess(Right.ADMIN, context.getUserReference(), context.getWikiReference());
            super.clearIndexCore();
        } catch (AuthorizationException e) {
            throw new IndexException("You do not have the right to clear the index", e);
        }
    }
}
