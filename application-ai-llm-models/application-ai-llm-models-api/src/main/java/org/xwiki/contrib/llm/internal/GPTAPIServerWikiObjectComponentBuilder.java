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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.wiki.WikiComponent;
import org.xwiki.component.wiki.WikiComponentException;
import org.xwiki.component.wiki.WikiObjectComponentBuilder;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * A component builder that builds {@link GPTAPIServer} components out of the configuration objects.
 *
 * @version $Id$
 * @since 0.5
 */
@Component
@Singleton
@Named(GPTAPIServerWikiObjectComponentBuilder.NAME)
public class GPTAPIServerWikiObjectComponentBuilder implements WikiObjectComponentBuilder
{
    /**
     * The name of this component builder.
     */
    public static final String NAME = "AI.Code.AIConfigClass";

    private static final List<String> SPACE_NAMES = List.of("AI", "Code");

    /**
     * The reference of the document that stores the server configuration.
     */
    public static final LocalDocumentReference AI_CONFIG_DOCUMENT =
        new LocalDocumentReference(SPACE_NAMES, "AIConfig");

    /**
     * The reference of the server configuration class.
     */
    public static final LocalDocumentReference AI_CONFIG_CLASS_REFERENCE =
        new LocalDocumentReference(SPACE_NAMES, "AIConfigClass");

    private static final String INTERNAL_SERVER = "internal";

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    @Inject
    private GPTAPIConfigBuilder configBuilder;

    @Override
    public EntityReference getClassReference()
    {
        return AI_CONFIG_CLASS_REFERENCE;
    }

    @Override
    public List<WikiComponent> buildComponents(ObjectReference reference) throws WikiComponentException
    {
        DocumentReference documentReference = reference.getDocumentReference();

        if (!documentReference.getLocalDocumentReference().equals(AI_CONFIG_DOCUMENT)) {
            return List.of();
        }

        XWikiContext context = this.contextProvider.get();
        try {
            XWikiDocument document = context.getWiki().getDocument(documentReference, context);
            // Check wiki admin rights.
            DocumentReference authorReference = document.getAuthorReference();
            if (!this.authorizationManager.hasAccess(Right.ADMIN, authorReference,
                documentReference.getWikiReference())) {
                throw new WikiComponentException(String.format(
                    "Failed to build component for object [%s], user [%s] does not have admin rights on the wiki.",
                    reference, authorReference));
            }

            BaseObject xObject = document.getXObject(reference);

            GPTAPIConfig config = this.configBuilder.build(xObject);

            ComponentManager componentManager = this.componentManagerProvider.get();

            GPTAPIServerWikiComponent component;
            // TODO: make the server implementation a configuration parameter.
            if (StringUtils.isNotBlank(config.getURL())) {
                component = componentManager.getInstance(GPTAPIServerWikiComponent.class, "openai");
            } else {
                if (componentManager.hasComponent(GPTAPIServerWikiComponent.class, INTERNAL_SERVER)) {
                    component = componentManager.getInstance(GPTAPIServerWikiComponent.class, INTERNAL_SERVER);
                } else {
                    throw new WikiComponentException("No internal server implementation available, please install the"
                        + " internal LLM inference extension.");
                }
            }
            component.initialize(config, reference, authorReference);
            return List.of(component);
        } catch (XWikiException e) {
            throw new WikiComponentException(String.format("Failed to load document for [%s]", reference), e);
        } catch (ComponentLookupException e) {
            throw new WikiComponentException(String.format("Failed to lookup components for [%s]", reference), e);
        }
    }
}
