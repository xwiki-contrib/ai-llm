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
package org.xwiki.contrib.llm.internal.authorization;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Builder for {@link ExternalAuthorizationRequest}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = ExternalAuthorizationRequestBuilder.class)
@Singleton
public class ExternalAuthorizationRequestBuilder
{
    private static final LocalDocumentReference LDAP_AUTHENTICATION_CONFIGURATION_CLASS_REFERENCE =
        new LocalDocumentReference("XWiki", "LDAPProfileClass");

    private static final String LDAP_XFIELD_DN = "dn";

    private static final String LDAP_XFIELD_UID = "uid";

    private static final LocalDocumentReference OIDC_USER_CLASS_REFERENCE =
        new LocalDocumentReference(List.of(XWiki.SYSTEM_SPACE, "OIDC"), "UserClass");

    private static final String ISSUER_FIELD = "issuer";

    private static final String SUBJECT_FIELD = "subject";

    private static final LocalDocumentReference JWT_TOKEN_AUTHENTICATOR_USER_CLASS_REFERENCE =
        new LocalDocumentReference(List.of("AI", "Code"), "JWTTokenAuthenticatorUserClass");

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    private Logger logger;

    /**
     * Build an external authorization request.
     *
     * @param documentIds the document ids to include in the request
     * @return the external authorization request
     */
    public ExternalAuthorizationRequest build(Set<String> documentIds)
    {
        try {
            XWikiDocument userDocument = getUserDocument();

            XWikiContext context = this.contextProvider.get();

            String xwikiUser = this.entityReferenceSerializer.serialize(context.getUserReference());
            LDAPUser ldapUser = null;
            List<JWTUser> jwtUsers = null;
            List<JWTUser> oidcUsers = null;

            if (userDocument != null && !userDocument.isNew()) {
                BaseObject ldapObject = userDocument.getXObject(LDAP_AUTHENTICATION_CONFIGURATION_CLASS_REFERENCE);
                if (ldapObject != null) {
                    ldapUser = new LDAPUser(ldapObject.getStringValue(LDAP_XFIELD_UID),
                        ldapObject.getStringValue(LDAP_XFIELD_DN));
                }

                List<BaseObject> jwtObjects = userDocument.getXObjects(JWT_TOKEN_AUTHENTICATOR_USER_CLASS_REFERENCE);
                jwtUsers = jwtObjects.stream()
                    .filter(Objects::nonNull)
                    .map(jwtObject -> new JWTUser(jwtObject.getStringValue(ISSUER_FIELD),
                    jwtObject.getStringValue(SUBJECT_FIELD))).toList();

                List<BaseObject> oidcObjects = userDocument.getXObjects(OIDC_USER_CLASS_REFERENCE);
                oidcUsers = oidcObjects.stream()
                    .filter(Objects::nonNull)
                    .map(oidcObject -> new JWTUser(oidcObject.getStringValue(ISSUER_FIELD),
                    oidcObject.getStringValue(SUBJECT_FIELD))).toList();
            }

            return new ExternalAuthorizationRequest(documentIds, xwikiUser, ldapUser, jwtUsers, oidcUsers);
        } catch (XWikiException e) {
            this.logger.warn("Failed to build external authorization request, assuming guest user: {}",
                ExceptionUtils.getRootCauseMessage(e));
            return new ExternalAuthorizationRequest(documentIds, null, null, null, null);
        }
    }

    private XWikiDocument getUserDocument() throws XWikiException
    {
        XWikiContext context = this.contextProvider.get();
        XWiki xwiki = context.getWiki();

        DocumentReference userReference = context.getUserReference();

        if (userReference == null) {
            return null;
        } else {
            return xwiki.getDocument(userReference, context);
        }
    }
}
