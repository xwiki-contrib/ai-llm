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
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Wrapper for a {@link DefaultDocument} that checks rights for the current user.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = CurrentUserDocument.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class CurrentUserDocument extends DefaultDocument
{
    @Inject
    private ContextualAuthorizationManager authorizationManager;

    @Override
    public void save() throws IndexException
    {
        XWikiContext context = this.contextProvider.get();
        XWikiDocument clonedXWikiDocument = this.xwikiDocumentWrapper.getClonedXWikiDocument();
        try {
            this.authorizationManager.checkAccess(Right.EDIT, clonedXWikiDocument.getDocumentReference());
            // Check saving. This could modify the document.
            context.getWiki().checkSavingDocument(context.getUserReference(), clonedXWikiDocument, context);
        } catch (XWikiException | AccessDeniedException e) {
            throw new IndexException(String.format("Failed to save document [%s]", this.getID()), e);
        }

        super.save();
    }
}
