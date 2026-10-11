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
package org.xwiki.contrib.llm.mcp.internal.server;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.user.GuestUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * The authorship side of the MCP configuration page: who last saved it, whether that user is trusted to
 * configure the wiki, and how a write records the acting administrator as the page's author. The trust rule
 * is the one {@link MCPServerConfiguration} applies on every read: the user who last saved the page (its
 * effective metadata author) must have admin right on the wiki the page configures.
 *
 * @version $Id$
 * @since 0.10.1
 */
@Component(roles = MCPConfigPageAuthorship.class)
@Singleton
public class MCPConfigPageAuthorship
{
    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @Inject
    @Named("document")
    private UserReferenceResolver<DocumentReference> userReferenceResolver;

    @Inject
    private Logger logger;

    /**
     * @param configDoc the existing configuration page
     * @return the user who last saved the page (its effective metadata author), or {@code null} when the page
     *     records no author or the guest user
     * @since 0.10.1
     */
    public DocumentReference authorOf(XWikiDocument configDoc)
    {
        UserReference authorReference = configDoc.getAuthors().getEffectiveMetadataAuthor();
        // A missing author is never serialized: the "document" serializer maps a missing user to the current
        // user, which must never stand in for the page's author.
        if (authorReference == null || authorReference == GuestUserReference.INSTANCE) {
            return null;
        }
        return this.userReferenceSerializer.serialize(authorReference);
    }

    /**
     * Decides whether a configuration page saved by the given user is trusted: the user must have admin right
     * on the wiki. A page without a recorded author is not trusted.
     *
     * @param author the user who last saved the page, or {@code null} when the page records none
     * @param wikiId the wiki the page configures
     * @return whether the page's values may be applied
     * @since 0.10.1
     */
    public boolean isTrusted(DocumentReference author, String wikiId)
    {
        if (author == null) {
            return false;
        }
        boolean trusted = this.authorizationManager.hasAccess(Right.ADMIN, author, new WikiReference(wikiId));
        if (!trusted) {
            this.logger.debug("The MCP configuration page of wiki [{}] is not trusted: its author [{}] has no admin "
                + "right on the wiki", wikiId, author);
        }
        return trusted;
    }

    /**
     * Records the acting user as the author and creator of the configuration page, so that the page is trusted
     * after the save. The creator is set with every write, not only when the page is created: the creator right
     * implies delete on a page, so ownership of the configuration page passes to the acting administrator with
     * every write.
     *
     * @param doc the configuration page about to be saved
     * @param context the current context, carrying the acting user
     * @since 0.10.1
     */
    public void stamp(XWikiDocument doc, XWikiContext context)
    {
        UserReference user = actingUser(context);
        doc.getAuthors().setOriginalMetadataAuthor(user);
        doc.getAuthors().setEffectiveMetadataAuthor(user);
        doc.getAuthors().setCreator(user);
    }

    /**
     * Records the acting user as the author, creator and content author of a configuration page that replaces
     * an untrusted one. The content author is set as well because the page's content is the extension's own
     * (or empty), never the untrusted author's: nothing of the replaced page remains to attribute to them.
     *
     * @param doc the replacement configuration page about to be saved
     * @param context the current context, carrying the acting user
     * @since 0.10.1
     */
    public void takeOwnership(XWikiDocument doc, XWikiContext context)
    {
        stamp(doc, context);
        doc.getAuthors().setContentAuthor(actingUser(context));
    }

    private UserReference actingUser(XWikiContext context)
    {
        return this.userReferenceResolver.resolve(context.getUserReference());
    }
}
