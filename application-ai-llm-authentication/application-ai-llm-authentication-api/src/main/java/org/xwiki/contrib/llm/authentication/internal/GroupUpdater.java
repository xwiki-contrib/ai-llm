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
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Utility component to update groups.
 * <p>This component is mostly copied from the {@code org.xwiki.contrib.oidc.auth.internal.OIDCUserManager} class of the
 * <a href="https://github.com/xwiki-contrib/oidc">OIDC authenticator</a> with some bulletproofing.</p>
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = GroupUpdater.class)
@Singleton
public class GroupUpdater
{
    private static final String XWIKI_GROUP_MEMBERFIELD = "member";

    @Inject
    private Logger logger;

    /**
     * Remove user name from provided XWiki group.
     *
     * @param xwikiUserName the full name of the user.
     * @param groupReference the group.
     * @param context the XWiki context.
     */
    public void removeUserFromXWikiGroup(String xwikiUserName, DocumentReference groupReference,
        XWikiContext context)
    {
        this.logger.debug("Removing user from [{}] ...", groupReference);

        try {
            BaseClass groupClass = context.getWiki().getGroupClass(context);

            // Get the XWiki document holding the objects comprising the group membership list
            XWikiDocument groupDoc = context.getWiki().getDocument(groupReference, context);

            synchronized (groupDoc) {
                XWikiDocument modifiableGroupDoc = groupDoc.clone();
                // Get and remove the specific group membership object for the user
                BaseObject groupObj =
                    modifiableGroupDoc.getXObject(groupClass.getDocumentReference(), XWIKI_GROUP_MEMBERFIELD,
                        xwikiUserName);
                modifiableGroupDoc.removeXObject(groupObj);

                // Save modifications
                context.getWiki().saveDocument(modifiableGroupDoc, context);
            }
        } catch (Exception e) {
            this.logger.error("Failed to remove user [{}] from group [{}]", xwikiUserName, groupReference, e);
        }
    }

    /**
     * Add user name into provided XWiki group.
     *
     * @param xwikiUserName the full name of the user.
     * @param groupReference the reference of the group.
     * @param context the XWiki context.
     */
    public void addUserToXWikiGroup(String xwikiUserName, DocumentReference groupReference, XWikiContext context)
    {
        try {
            BaseClass groupClass = context.getWiki().getGroupClass(context);

            // Get document representing group
            XWikiDocument groupDoc = context.getWiki().getDocument(groupReference, context);

            this.logger.debug("Adding user [{}] to xwiki group [{}]", xwikiUserName, groupReference);

            synchronized (groupDoc) {
                // Make extra sure the group cannot contain duplicate (even if this method is not supposed to be called
                // in this case)
                List<BaseObject> xobjects = groupDoc.getXObjects(groupClass.getDocumentReference());
                if (xobjects != null) {
                    for (BaseObject memberObj : xobjects) {
                        if (memberObj != null) {
                            String existingMember = memberObj.getStringValue(XWIKI_GROUP_MEMBERFIELD);
                            if (existingMember != null && existingMember.equals(xwikiUserName)) {
                                this.logger.warn("User [{}] already exists in group [{}]", xwikiUserName,
                                        groupDoc.getDocumentReference());
                                return;
                            }
                        }
                    }
                }

                // Add a member object to document
                XWikiDocument modifiableGroupDoc = groupDoc.clone();

                if (modifiableGroupDoc.isNew()) {
                    // Add an empty group object to the document to make sure that the group is marked as such.
                    modifiableGroupDoc.createXObject(groupClass.getDocumentReference(), context);
                }
                BaseObject memberObj = modifiableGroupDoc.newXObject(groupClass.getDocumentReference(), context);
                Map<String, String> map = Map.of(XWIKI_GROUP_MEMBERFIELD, xwikiUserName);
                groupClass.fromMap(map, memberObj);

                // Save modifications
                context.getWiki().saveDocument(modifiableGroupDoc, context);
            }

            this.logger.debug("Finished adding user [{}] to xwiki group [{}]", xwikiUserName, groupReference);
        } catch (Exception e) {
            this.logger.error("Failed to add a user [{}] to a group [{}]", xwikiUserName, groupReference, e);
        }
    }
}
