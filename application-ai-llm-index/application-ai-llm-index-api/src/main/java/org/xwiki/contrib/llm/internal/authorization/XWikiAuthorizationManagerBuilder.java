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

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.authorization.AuthorizationManagerBuilder;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStore;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.objects.BaseObject;

/**
 * XWiki authorization manager builder that simply checks standard XWiki rights.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Singleton
@Named("xwiki")
public class XWikiAuthorizationManagerBuilder implements AuthorizationManagerBuilder
{
    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    private org.xwiki.security.authorization.AuthorizationManager authorizationManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public AuthorizationManager build(BaseObject configurationObject) throws IndexException
    {
        String configurationStoreValue = configurationObject.getStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME);
        if (!XWikiDocumentStore.NAME.equals(configurationStoreValue)) {
            throw new IndexException("The xwiki rights checking method only works with the xwiki document store.");
        }

        return documentIds -> {
            XWikiContext context = this.contextProvider.get();
            return documentIds.stream()
                .collect(Collectors.toMap(Function.identity(), id ->
                    this.authorizationManager.hasAccess(Right.VIEW, context.getUserReference(),
                        this.documentReferenceResolver.resolve(id))
                    )
                );
        };
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
        return Object.class;
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
