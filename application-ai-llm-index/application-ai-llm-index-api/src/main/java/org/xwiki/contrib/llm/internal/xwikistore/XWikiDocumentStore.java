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
package org.xwiki.contrib.llm.internal.xwikistore;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * A document store that contains the documents that are listed in the configured spaces.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
@Named(XWikiDocumentStore.NAME)
public class XWikiDocumentStore implements DocumentStore
{
    /**
     * The name of this store.
     */
    public static final String NAME = "xwiki";

    private static final String MODIFYING_DOCUMENTS = "The XWiki store doesn't support modifying documents.";

    private List<SpaceReference> spaces;

    private String collectionName;

    private UserReference userReference;

    private DocumentReference userDocumentReference;

    @Inject
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @Inject
    @Named("withparameters")
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    @Named("withparameters")
    private EntityReferenceResolver<String> documentReferenceResolver;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private Provider<XWikiDocumentDocument> xWikiDocumentDocumentProvider;

    @Inject
    private XWikiDocumentStoreHelper helper;

    @Override
    public void initialize(Collection collection, UserReference userReference) throws IndexException
    {
        this.spaces = this.helper.resolveSpaceReferences(collection.getDocumentSpaces(),
            collection.getDocumentReference());
        this.collectionName = collection.getID();
        if (userReference != null) {
            this.userReference = userReference;
            this.userDocumentReference = this.userReferenceSerializer.serialize(userReference);
        }
    }

    private boolean isUserPresent()
    {
        return this.userReference != null;
    }

    @Override
    public List<String> getDocumentNames(int offset, int limit) throws IndexException
    {
        return this.helper.getDocuments(this.spaces, offset, limit).stream()
            .filter(documentReference ->
                !isUserPresent()
                    || this.authorizationManager.hasAccess(Right.VIEW, this.userDocumentReference, documentReference))
            .map(this.entityReferenceSerializer::serialize)
            .toList();
    }

    @Override
    public Document getDocument(String name) throws IndexException, AccessDeniedException
    {
        DocumentReference documentReference =
            new DocumentReference(this.documentReferenceResolver.resolve(name, EntityType.DOCUMENT));

        if (this.spaces.stream()
            .noneMatch(space -> documentReference.getReversedReferenceChain().contains(space))) {
            throw new IndexException("The document [%s] isn't part of the collection [%s]".formatted(name,
                this.collectionName));
        }

        if (isUserPresent()) {
            this.authorizationManager.checkAccess(Right.VIEW, this.userDocumentReference, documentReference);
        }

        XWikiDocumentDocument result = this.xWikiDocumentDocumentProvider.get();
        XWikiContext context = this.contextProvider.get();

        try {
            XWikiDocument xWikiDocument = context.getWiki().getDocument(documentReference, context);
            if (xWikiDocument.isNew()) {
                throw new IndexException("The document [%s] doesn't exist".formatted(name));
            }
            result.initialize(this.collectionName, xWikiDocument);
        } catch (XWikiException e) {
            throw new IndexException("Error loading the document [%s]".formatted(name));
        }
        return result;
    }

    @Override
    public Document createDocument(String name)
    {
        throw new UnsupportedOperationException(MODIFYING_DOCUMENTS);
    }

    @Override
    public void saveDocument(Document document)
    {
        throw new UnsupportedOperationException(MODIFYING_DOCUMENTS);
    }

    @Override
    public void deleteDocument(Document document)
    {
        throw new UnsupportedOperationException(MODIFYING_DOCUMENTS);
    }
}
