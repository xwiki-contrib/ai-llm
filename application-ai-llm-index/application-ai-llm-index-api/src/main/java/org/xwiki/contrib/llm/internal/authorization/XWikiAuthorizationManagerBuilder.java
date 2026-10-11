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

import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.authorization.AuthorizationManagerBuilder;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStore;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStoreHelper;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.objects.BaseObject;

/**
 * XWiki authorization manager builder for collections that use the XWiki document store. A document is viewable when
 * the current user has view right on the wiki page that the document id designates. The locale that is part of the
 * document id is ignored as rights are set on the page and not on its translations. A document id that is not the
 * exact id of a page, as the XWiki document store writes it, is not viewable.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Singleton
@Named(XWikiAuthorizationManagerBuilder.NAME)
public class XWikiAuthorizationManagerBuilder implements AuthorizationManagerBuilder
{
    /**
     * The name of this rights checking method.
     *
     * @since 0.10.1
     */
    public static final String NAME = "xwiki";

    @Inject
    private XWikiDocumentStoreHelper helper;

    @Inject
    private org.xwiki.security.authorization.AuthorizationManager authorizationManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Override
    public AuthorizationManager build(BaseObject configurationObject) throws IndexException
    {
        String configurationStoreValue = configurationObject.getStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME);
        if (!XWikiDocumentStore.NAME.equals(configurationStoreValue)) {
            throw new IndexException("The xwiki rights checking method only works with the xwiki document store.");
        }

        return documentIds -> {
            DocumentReference userReference = this.contextProvider.get().getUserReference();
            return documentIds.stream()
                .collect(Collectors.toMap(Function.identity(), id -> canView(userReference, id)));
        };
    }

    private boolean canView(DocumentReference userReference, String documentId)
    {
        DocumentReference pageReference = getPageReference(documentId);
        if (pageReference == null) {
            this.logger.debug("Denying access to the document id [{}] that is not the exact id of a page.",
                documentId);
            return false;
        }

        return this.authorizationManager.hasAccess(Right.VIEW, userReference, pageReference);
    }

    /**
     * @param documentId the id of a document of the XWiki document store
     * @return the reference of the page, without locale, that the document id designates, or {@code null} when the
     *     document id is not the exact id that the XWiki document store writes for a document of that page
     */
    private DocumentReference getPageReference(String documentId)
    {
        DocumentReference documentReference = this.helper.resolveDocumentId(documentId);

        // Rights are set on the page: the returned reference has no locale.
        return documentReference == null ? null : documentReference.withoutLocale();
    }

    @Override
    public EntityReference getConfigurationClassReference()
    {
        return Collection.XCLASS_REFERENCE;
    }

    @Override
    public EntityReference getConfigurationSheetReference()
    {
        return null;
    }

    @Override
    public Class<?> getConfigurationType()
    {
        return null;
    }

    @Override
    public Object getConfiguration(BaseObject object)
    {
        return null;
    }

    @Override
    public void setConfiguration(BaseObject object, Object configuration)
    {
        // No configuration to set.
    }
}
