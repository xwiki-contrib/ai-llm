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
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.wiki.WikiComponentException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.user.GuestUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.doc.XWikiDocument;

/**
 * The trust rule the wiki component builders apply to a configuration page: the user who last saved the page (its
 * effective metadata author) must have admin right on the wiki the page belongs to. A page without a recorded
 * author is never trusted.
 *
 * @version $Id$
 * @since 0.10.1
 */
@Component(roles = ConfigurationPageAuthorChecker.class)
@Singleton
public class ConfigurationPageAuthorChecker
{
    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    /**
     * @param document the configuration page the component is built from
     * @param reference the reference of the object the component is built from, for the error message
     * @return the user who last saved the page, who has admin right on the wiki
     * @throws WikiComponentException when the page records no author or its author has no admin right on the wiki
     */
    public DocumentReference requireAdminAuthor(XWikiDocument document, ObjectReference reference)
        throws WikiComponentException
    {
        UserReference author = document.getAuthors().getEffectiveMetadataAuthor();
        // A missing author is never serialized: the "document" serializer maps a missing user to the current
        // user, which must never stand in for the page's author.
        if (author == null || author == GuestUserReference.INSTANCE) {
            throw new WikiComponentException(String.format(
                "Failed to build component for object [%s], the page has no author.", reference));
        }
        DocumentReference authorReference = this.userReferenceSerializer.serialize(author);
        if (!this.authorizationManager.hasAccess(Right.ADMIN, authorReference,
            document.getDocumentReference().getWikiReference())) {
            throw new WikiComponentException(String.format(
                "Failed to build component for object [%s], user [%s] does not have admin rights on the wiki.",
                reference, authorReference));
        }
        return authorReference;
    }
}
