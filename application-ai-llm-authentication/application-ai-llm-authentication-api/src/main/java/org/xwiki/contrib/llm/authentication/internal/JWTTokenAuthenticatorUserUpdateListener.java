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
package org.xwiki.contrib.llm.authentication.internal;

import java.util.List;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.bridge.event.AbstractDocumentEvent;
import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.event.UserCreatingDocumentEvent;
import com.xpn.xwiki.internal.event.UserEvent;
import com.xpn.xwiki.internal.event.UserUpdatingDocumentEvent;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Listener that prevents updates to the issuer/subject of the user object for non-admins.
 * <p>
 * This listeners prevents creating or updating documents with a JWT issuer/subject prefilled. This is to avoid attacks
 * where a user could assign to a profile an issuer/subject.
 * </p>
 * <p>
 * This would allow an attacker to impersonate another user in the context of the AI LLM app where we might use the
 * issuer/subject to identify the user in an external app.
 * Further, if the user identified by the issuer/subject then tries to log in, the user groups of the attacker
 * would be updated and these groups could also grant the user additional rights.
 * </p>
 * <p>
 * This listener simply cancels such edits and doesn't undo the changes to the XObjects as other, similar listeners do.
 * This is because they shouldn't happen under normal circumstances.
 * Further, it seems more obvious to the user to cancel the edit such that the UI can display a proper error
 * instead of silently reverting the change, leading the user to think that saving succeeded while it
 * actually didn't.
 * </p>
 *
 * @since 0.3
 * @version $Id$
 */
@Component
@Singleton
@Named(JWTTokenAuthenticatorUserUpdateListener.NAME)
public class JWTTokenAuthenticatorUserUpdateListener extends AbstractEventListener
{
    /**
     * The name of the listener.
     */
    public static final String NAME = "JWTTokenAuthenticatorUserUpdateListener";

    @Inject
    private AuthorizationManager authorizationManager;

    /**
     * Default constructor.
     */
    public JWTTokenAuthenticatorUserUpdateListener()
    {
        // Note that while these two events are internal, they are widely used and can be considered public API, see
        // https://forum.xwiki.org/t/make-old-core-document-events-official-public-api/14447
        super(NAME, new UserUpdatingDocumentEvent(), new UserCreatingDocumentEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        DocumentReference userReference = ((UserEvent) event).getUserReference();
        DocumentReference documentReference = ((AbstractDocumentEvent) event).getDocumentReference();

        // Do not perform any checks if the user is a wiki-level admin.
        if (this.authorizationManager.hasAccess(Right.ADMIN, userReference, documentReference.getWikiReference())) {
            return;
        }

        XWikiDocument document = (XWikiDocument) source;

        if (event instanceof DocumentCreatingEvent documentCreatingEvent) {
            boolean hasTokens = document.getXObjects(JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE)
                .stream()
                .anyMatch(Objects::nonNull);
            if (hasTokens) {
                documentCreatingEvent.cancel("Only wiki admins can create users with a JWT issuer/subject prefilled.");
            }
        } else if (event instanceof UserUpdatingDocumentEvent userUpdatingDocumentEvent) {
            XWikiDocument previousDocument = document.getOriginalDocument();

            List<BaseObject> oldObjects =
                previousDocument.getXObjects(JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE);
            List<BaseObject> newObjects =
                document.getXObjects(JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE);

            if (!Objects.equals(oldObjects, newObjects)) {
                userUpdatingDocumentEvent.cancel(
                    "Only wiki admins can update the issuer/subject of a user for the JWT token authenticator.");
            }
        }
    }
}
