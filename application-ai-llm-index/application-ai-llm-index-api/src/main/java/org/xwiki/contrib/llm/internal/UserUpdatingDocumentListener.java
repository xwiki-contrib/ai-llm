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
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.bridge.event.AbstractDocumentEvent;
import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
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
 * An event listener that ensures that only admins can create or update collections.
 *
 * @version $Id$
 * @since 0.5
 */
@Component
@Singleton
@Named(UserUpdatingDocumentListener.NAME)
public class UserUpdatingDocumentListener extends AbstractEventListener
{
    /**
     * The name of the event listener.
     */
    public static final String NAME = "org.xwiki.contrib.llm.internal.UserUpdatingDocumentListener";

    @Inject
    private AuthorizationManager authorizationManager;

    /**
     * Default constructor.
     */
    public UserUpdatingDocumentListener()
    {
        // Note that while these two events are internal, they are widely used and can be considered public API, see
        // https://forum.xwiki.org/t/make-old-core-document-events-official-public-api/14447
        super(NAME, List.of(new UserCreatingDocumentEvent(), new UserUpdatingDocumentEvent()));
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        // Only handle documents that have a collection definition. Don't care about the deletion of the collection
        // definition as it is the task of regular rights to prevent editing. This listener only cares about
        // non-admins not being to create or update collections in arbitrary places as collections allow bypassing
        // rights.
        XWikiDocument document = (XWikiDocument) source;
        boolean hasCollection = document.getXObjects(Collection.XCLASS_REFERENCE)
            .stream()
            .anyMatch(Objects::nonNull);

        if (!hasCollection) {
            return;
        }

        DocumentReference userReference = ((UserEvent) event).getUserReference();
        DocumentReference documentReference = ((AbstractDocumentEvent) event).getDocumentReference();

        // Do not perform any further checks if the user is a wiki-level admin.
        if (this.authorizationManager.hasAccess(Right.ADMIN, userReference, documentReference.getWikiReference())) {
            return;
        }

        if (event instanceof DocumentCreatingEvent documentCreatingEvent) {
            documentCreatingEvent.cancel("Only wiki admins can create collections.");
        } else if (event instanceof UserUpdatingDocumentEvent userUpdatingDocumentEvent) {
            XWikiDocument previousDocument = document.getOriginalDocument();

            List<BaseObject> oldObjects =
                previousDocument.getXObjects(Collection.XCLASS_REFERENCE);
            List<BaseObject> newObjects =
                document.getXObjects(Collection.XCLASS_REFERENCE);

            if (!Objects.equals(oldObjects, newObjects)) {
                userUpdatingDocumentEvent.cancel("Only wiki admins can update collection definitions.");
            }
        }
    }
}
