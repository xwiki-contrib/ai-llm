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

import javax.inject.Inject;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.security.authorization.AuthorizationException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;

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
    private UserReferenceResolver<CurrentUserReference> currentUserReferenceUserReferenceResolver;

    @Override
    public DocumentStore getDocumentStore() throws IndexException
    {
        return getDocumentStoreInternal(CurrentUserReference.INSTANCE);
    }

    @Override
    public void save() throws IndexException
    {
        XWikiContext context = this.contextProvider.get();
        try {
            XWikiDocument collectionDocument = this.xWikiDocumentWrapper.getClonedXWikiDocument();
            this.contextualAuthorizationManager.checkAccess(Right.EDIT, collectionDocument.getDocumentReference());
            UserReference userReference =
                this.currentUserReferenceUserReferenceResolver.resolve(CurrentUserReference.INSTANCE);
            collectionDocument.getAuthors().setOriginalMetadataAuthor(userReference);
            collectionDocument.getAuthors().setEffectiveMetadataAuthor(userReference);
            if (collectionDocument.isNew()) {
                collectionDocument.getAuthors().setCreator(userReference);
            }
            context.getWiki().checkSavingDocument(context.getUserReference(), collectionDocument, context);
            context.getWiki().saveDocument(collectionDocument, context);
        } catch (XWikiException | AuthorizationException e) {
            throw new IndexException(
                String.format("Access denied for saving collection [%s]", super.getID()), e);
        }
    }
}
