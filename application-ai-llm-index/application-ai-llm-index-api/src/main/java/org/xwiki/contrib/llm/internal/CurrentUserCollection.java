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

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.AuthorizationException;
import org.xwiki.security.authorization.Right;
import org.xwiki.user.CurrentUserReference;

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
    @Override
    public DocumentStore getDocumentStore() throws IndexException
    {
        return getDocumentStoreInternal(CurrentUserReference.INSTANCE);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The saved page is attributed to the current user; when the save is made from a script without programming
     * right, the script's author must also be allowed to edit the page and becomes the effective author, like the
     * platform's document API.</p>
     */
    @Override
    public void save() throws IndexException
    {
        XWikiContext context = this.contextProvider.get();
        try {
            XWikiDocument collectionDocument = this.xWikiDocumentWrapper.getClonedXWikiDocument();
            DocumentReference actor = context.getUserReference();
            DocumentReference effectiveAuthor = this.writeAttribution.checkAccess(Right.EDIT,
                collectionDocument.getDocumentReference(), CurrentUserReference.INSTANCE, actor);
            this.writeAttribution.prepareSave(collectionDocument, actor, effectiveAuthor);
            context.getWiki().checkSavingDocument(effectiveAuthor, collectionDocument, context);
            context.getWiki().saveDocument(collectionDocument, context);
        } catch (AuthorizationException e) {
            throw new IndexException(
                String.format("Access denied for saving collection [%s]", super.getID()), e);
        } catch (XWikiException e) {
            throw new IndexException(String.format("Failed to save collection [%s]", super.getID()), e);
        }
    }
}
