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

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.model.document.DocumentAuthors;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.AuthorizationException;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.DocumentAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.security.authorization.requiredrights.DocumentRequiredRight;
import org.xwiki.security.authorization.requiredrights.DocumentRequiredRights;
import org.xwiki.security.authorization.requiredrights.DocumentRequiredRightsManager;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Rights check and attribution of the pages written by the stores and collections of this extension. A write made for
 * the current user from a script without programming right must also be allowed to the script's author, who becomes
 * the effective author of the saved page, like the platform's document API; setting the platform's
 * {@code security.script.save.checkAuthor} property to false switches the script-author rule off.
 *
 * @version $Id$
 * @since 0.10.1
 */
@Component(roles = WriteAttribution.class)
@Singleton
public class WriteAttribution
{
    private static final String CHECK_AUTHOR_PROPERTY = "security.script.save.checkAuthor";

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private ContextualAuthorizationManager contextualAuthorizationManager;

    @Inject
    private DocumentAuthorizationManager documentAuthorizationManager;

    @Inject
    private DocumentRequiredRightsManager documentRequiredRightsManager;

    @Inject
    private ConfigurationSource configuration;

    @Inject
    @Named("document")
    private UserReferenceResolver<DocumentReference> documentUserReferenceResolver;

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * Check that the user may make a write and, when the write is made for the current user from a script without
     * programming right and the platform's author check is enabled, that the script's author may make it too. A
     * script that dropped its permissions, or whose author is unknown, writes as guest, like the platform's document
     * API.
     *
     * @param right the right the write needs
     * @param page the page written
     * @param user the user the write is made for, as given to the store or collection
     * @param actor the document reference of that user
     * @return the effective author of the write: the actor, or the script's author when the rule applies
     *     ({@code null} is guest)
     * @throws AccessDeniedException when the actor or the script's author may not make the write
     */
    public DocumentReference checkAccess(Right right, DocumentReference page, UserReference user,
        DocumentReference actor) throws AccessDeniedException
    {
        this.authorizationManager.checkAccess(right, actor, page);
        if (user != CurrentUserReference.INSTANCE || !this.configuration.getProperty(CHECK_AUTHOR_PROPERTY, true)
            || this.contextualAuthorizationManager.hasAccess(Right.PROGRAM)) {
            return actor;
        }
        XWikiContext context = this.contextProvider.get();
        DocumentReference author = context.hasDroppedPermissions() ? null : context.getAuthorReference();
        if (author != null && author.equals(actor)) {
            return actor;
        }
        this.authorizationManager.checkAccess(right, author, page);
        return author;
    }

    /**
     * Prepare a page for saving. The original metadata author is the acting user; the effective metadata author, the
     * content author and, for a new page, the creator are the effective author. The content author is set on every
     * save: the saved page is the whole document the acting user submits, and the document's dirty flags are not
     * relied on. When the effective author is the script's author, the page is capped to the required rights the
     * script's author holds on the running script, like the platform's document API.
     *
     * @param document the page that is saved
     * @param actor the user the write is made for
     * @param effectiveAuthor the user the page is attributed to, as returned by
     *     {@link #checkAccess(Right, DocumentReference, UserReference, DocumentReference)}
     * @throws AuthorizationException when the required rights cannot be loaded or the script's author lacks a right
     *     the page requires
     */
    public void prepareSave(XWikiDocument document, DocumentReference actor, DocumentReference effectiveAuthor)
        throws AuthorizationException
    {
        DocumentAuthors authors = document.getAuthors();
        authors.setOriginalMetadataAuthor(this.documentUserReferenceResolver.resolve(actor));
        UserReference effective = this.documentUserReferenceResolver.resolve(effectiveAuthor);
        authors.setEffectiveMetadataAuthor(effective);
        authors.setContentAuthor(effective);
        if (document.isNew()) {
            authors.setCreator(effective);
        }

        if (!Objects.equals(actor, effectiveAuthor)) {
            checkRequiredRights(document, effectiveAuthor);
        }
    }

    /**
     * Cap the page to the required rights the author holds on the running script: when the script enforces required
     * rights and its author has no programming right on it, the page enforces required rights too and may require
     * only rights the author holds on the script.
     */
    private void checkRequiredRights(XWikiDocument document, DocumentReference author) throws AuthorizationException
    {
        XWikiDocument secureDocument = this.contextProvider.get().getSecureDocument();
        if (secureDocument == null) {
            return;
        }
        DocumentReference secureReference = secureDocument.getDocumentReference();
        if (!getRequiredRights(secureReference).enforce()
            || this.documentAuthorizationManager.hasAccess(Right.PROGRAM, null, author, secureReference)) {
            return;
        }

        document.setEnforceRequiredRights(true);
        // The required rights of the page are read from the stored page: a script without programming right cannot
        // reach the in-memory page, so the stored rights are the ones the page can have.
        for (DocumentRequiredRight requiredRight : getRequiredRights(document.getDocumentReference()).rights()) {
            this.documentAuthorizationManager.checkAccess(requiredRight.right(), requiredRight.scope(), author,
                secureReference);
        }
    }

    private DocumentRequiredRights getRequiredRights(DocumentReference reference) throws AuthorizationException
    {
        return this.documentRequiredRightsManager.getRequiredRights(reference).orElse(DocumentRequiredRights.EMPTY);
    }
}
