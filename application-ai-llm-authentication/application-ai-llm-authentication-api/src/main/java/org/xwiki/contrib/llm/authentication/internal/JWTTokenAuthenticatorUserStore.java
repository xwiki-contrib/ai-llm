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

import java.text.ParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.collections4.map.AbstractReferenceMap;
import org.apache.commons.collections4.map.ReferenceMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;

import com.nimbusds.jwt.JWTClaimsSet;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Store for JWT token authenticator users.
 *
 * @version $Id: 64076a3d27a3001cc5e2df65c43c2b135b91619c $
 * @since 15.9RC1
 */
@Component(roles = JWTTokenAuthenticatorUserStore.class)
@Singleton
public class JWTTokenAuthenticatorUserStore
{
    @Inject
    private GroupUpdater groupUpdater;

    @Inject
    private Logger logger;

    @Inject
    private QueryManager queryManager;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> currentDocumentReferenceResolver;

    @Inject
    private GroupManager groupManager;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    @Inject
    private JWTTokenAuthenticatorConfiguration configuration;

    /**
     * Locks for each user to avoid concurrent creation. The key is the subject as while it is possible to have multiple
     * users with the same subject, it is dangerous to create them in parallel as they will compete for the same
     * username. A reference map is used to ensure that locks are garbage collected when all updates of that user are
     * done, but as long as any thread holds a reference to the lock, it will be in the map.
     */
    private final Map<String, ReentrantLock> userLocks = Collections.synchronizedMap(new ReferenceMap<>(
        AbstractReferenceMap.ReferenceStrength.HARD, AbstractReferenceMap.ReferenceStrength.WEAK));

    /**
     * @param application the application to get the user for
     * @param subject the subject of the token
     * @return the user document for the given issuer and subject, or null if no such user exists
     */
    public XWikiDocument getUser(AuthorizedApplication application, String subject)
        throws QueryException, XWikiException
    {
        List<String> users = this.queryManager.createQuery(
                "from doc.object(" + JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_FULLNAME + ") as user "
                    + "where user.issuer = :issuer and user.subject = :subject", Query.XWQL)
            .bindValue("issuer", application.issuer())
            .bindValue("subject", subject)
            .execute();

        if (users.size() > 1) {
            this.logger.warn("Multiple users found for issuer {} and subject {}, using the first one.",
                application.issuer(), subject);
        }

        if (!users.isEmpty()) {
            DocumentReference userReference = this.currentDocumentReferenceResolver.resolve(users.get(0));
            return this.xcontextProvider.get().getWiki().getDocument(userReference, this.xcontextProvider.get());
        }

        return null;
    }

    /**
     * Update the user with the given claims. If the user does not exist, it will be created.
     *
     * @param application the application to update the user for
     * @param claims the claims to update the user with
     * @return the reference of the user document
     */
    public DocumentReference updateUser(AuthorizedApplication application, JWTClaimsSet claims) throws XWikiException
    {
        ReentrantLock lock = this.userLocks.computeIfAbsent(claims.getSubject(), key -> new ReentrantLock());

        boolean newUser = false;
        try {
            XWikiDocument userDocument = getUser(application, claims.getSubject());
            XWikiContext xcontext = this.xcontextProvider.get();

            newUser = userDocument == null || userDocument.isNew();

            JWTTokenAuthenticatorUserDocument userClassDocument;

            if (newUser) {
                lock.lock();
                userClassDocument = new JWTTokenAuthenticatorUserDocument(claims.getSubject(), xcontext);
                // Make new users active by default but don't reset the active status of existing users.
                userClassDocument.setActive(true, xcontext);
                // Initialize the token object to find the user back.
                userClassDocument.setSubjectIssuer(claims.getSubject(), claims.getIssuer(), xcontext);
            } else {
                userClassDocument = new JWTTokenAuthenticatorUserDocument(userDocument);
            }

            userClassDocument.setFirstName(claims.getStringClaim("given_name"), xcontext);
            userClassDocument.setLastName(claims.getStringClaim("family_name"), xcontext);
            userClassDocument.setEmail(claims.getStringClaim("email"), xcontext);

            userClassDocument.maybeSave(xcontext);

            if (newUser) {
                String username =
                    this.localEntityReferenceSerializer.serialize(userClassDocument.getDocumentReference());
                xcontext.getWiki().setUserDefaultGroup(username, xcontext);
            }

            List<String> providerGroups = claims.getStringListClaim("groups");
            if (providerGroups != null) {
                updateGroups(application, userClassDocument.getDocumentReference(), providerGroups);
            }

            return userClassDocument.getDocumentReference();
        } catch (ParseException e) {
            throw new XWikiException("Invalid claim format", e);
        } catch (QueryException e) {
            throw new XWikiException("Failed to query for user.", e);
        } finally {
            if (newUser) {
                lock.unlock();
            }
        }
    }

    private void updateGroups(AuthorizedApplication application, DocumentReference userReference,
        List<String> providerGroups)
    {
        XWikiContext context = this.xcontextProvider.get();

        Collection<DocumentReference> existingGroups;
        try {
            existingGroups = this.groupManager.getGroups(userReference,
                userReference.getWikiReference(), false);
        } catch (GroupException e) {
            this.logger.error("Failed to get groups for user [{}]", userReference, e);
            return;
        }

        SpaceReference xwikiSpaceReference = new SpaceReference("XWiki", userReference.getWikiReference());

        List<DocumentReference> newGroups = Optional.ofNullable(providerGroups).orElseGet(List::of).stream()
            .map(group -> StringUtils.isBlank(application.groupFormat()) ? group
                : new StringSubstitutor(Map.of("group", group)).replace(application.groupFormat()))
            .map(group -> new DocumentReference(group, xwikiSpaceReference))
            .toList();

        String userName = this.localEntityReferenceSerializer.serialize(userReference);

        // Add missing groups.
        newGroups.stream()
            .filter(group -> !existingGroups.contains(group))
            .forEach(group -> this.groupUpdater.addUserToXWikiGroup(userName, group, context));

        // Remove groups that are not in the provider anymore, but don't remove the initial user groups.
        existingGroups.stream()
            .filter(group -> !newGroups.contains(group))
            .filter(group -> !this.configuration.getInitialXWikiGroups()
                .contains(this.localEntityReferenceSerializer.serialize(group)))
            .forEach(group -> this.groupUpdater.removeUserFromXWikiGroup(userName, group, context));
    }
}
