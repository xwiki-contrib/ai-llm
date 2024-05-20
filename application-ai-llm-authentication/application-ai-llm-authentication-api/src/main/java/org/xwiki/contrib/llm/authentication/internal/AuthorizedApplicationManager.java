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

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.nimbusds.jose.jwk.OctetKeyPair;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Manager for authorized applications.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = AuthorizedApplicationManager.class)
@Singleton
public class AuthorizedApplicationManager
{
    /**
     * The full name of the class that represents an application.
     */
    public static final String AUTHORIZED_APPLICATION_CLASS =
        "AI.Authorized Applications.Code.Authorized ApplicationsClass";

    /**
     * The reference to the class that represents an application.
     */
    public static final LocalDocumentReference AUTHORIZED_APPLICATION_CLASS_REFERENCE = new LocalDocumentReference(
        List.of("AI", "Authorized Applications", "Code"), "Authorized ApplicationsClass");

    @Inject
    private QueryManager queryManager;

    @Inject
    private Logger logger;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> currentDocumentReferenceResolver;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private Ed25519PEMReader pemReader;

    /**
     * Get the application with the given issuer if there is any.
     *
     * @param issuer the issuer field of the application
     * @return the application if it exists
     */
    public Optional<AuthorizedApplication> getApplication(String issuer)
    {
        XWikiContext context = this.contextProvider.get();

        List<String> results = List.of();
        try {
            results = this.queryManager.createQuery(
                    "from doc.object(\'" + AUTHORIZED_APPLICATION_CLASS + "\') as app where app.url = :issuer",
                     Query.XWQL)
                .bindValue("issuer", issuer)
                .execute();
        } catch (QueryException e) {
            this.logger.error("Failed to query for authorized applications.", e);
        }       

        return results.stream()
            .map(result -> this.currentDocumentReferenceResolver.resolve(result))
            // Get the actual document(s).
            .flatMap(documentReference -> {
                try {
                    return Stream.ofNullable(context.getWiki().getDocument(documentReference, context));
                } catch (XWikiException e) {
                    this.logger.error("Failed to retrieve document [{}].", documentReference, e);
                    return Stream.empty();
                }
            })
            // Check if the author of the document has wiki admin right.
            .filter(document -> this.authorizationManager.hasAccess(Right.ADMIN, document.getAuthorReference(),
                document.getDocumentReference().getWikiReference()))
            // Get a stream of application XObjects
            .flatMap(document -> document.getXObjects(AUTHORIZED_APPLICATION_CLASS_REFERENCE).stream())
            // Make sure that if there are several applications on a document, we only consider those with the
            // correct issuer field.
            .filter(obj -> obj.getStringValue("url").equals(issuer))
            // Construct the actual AuthorizedApplication object.
            .flatMap(baseObject -> {
                XWikiDocument document = baseObject.getOwnerDocument();
                DocumentReference documentReference = document.getDocumentReference();

                try {
                    OctetKeyPair publicJWK =
                        this.pemReader.readPublicEd25519KeyFromPEM(baseObject.getStringValue("publicKey"));

                    return Stream.of(new AuthorizedApplication(documentReference, issuer,
                        document.getRenderedTitle(Syntax.PLAIN_1_0, context),
                        baseObject.getStringValue("groupFormat"), publicJWK));
                } catch (IOException e) {
                    this.logger.warn("Ignoring token from application [{}] because its public key is invalid: [{}].",
                        documentReference, ExceptionUtils.getRootCauseMessage(e));
                }

                return Stream.empty();
            })
            .findAny();
    }

}
