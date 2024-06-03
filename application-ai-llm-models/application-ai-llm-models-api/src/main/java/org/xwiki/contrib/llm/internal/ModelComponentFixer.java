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
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.wiki.WikiComponent;
import org.xwiki.component.wiki.WikiComponentException;
import org.xwiki.component.wiki.WikiComponentManager;
import org.xwiki.component.wiki.WikiObjectComponentBuilder;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Fix chat and embedding model wiki components by re-loading them, e.g., after an extension installation.
 * <p>
 *     This is a workaround until the underlying bug in XWiki that wiki components aren't recreated when the
 *     classloader is recreated on extension installation has been fixed and this extension depends on a version
 *     with the fix, one of the issues to track is
 *     <a href="https://jira.xwiki.org/browse/XWIKI-21887">XWIKI-21887</a>
 *     . Without this fix, model wiki components cannot be found anymore until a restart or until the documents with
 *     the model definitions are reloaded as the component manager has those components registered under the old type
 *     from the old classloader while the model managers use the new type.
 * </p>
 *
 * @since 0.4
 * @version $Id$
 */
@Component(roles = ModelComponentFixer.class)
@Singleton
public class ModelComponentFixer
{
    @Inject
    @Named("context")
    private Provider<ComponentManager> contextComponentManagerProvider;

    @Inject
    private WikiComponentManager wikiComponentManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    @Named("AI.Models.Code.ModelsClass")
    private WikiObjectComponentBuilder componentBuilder;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> currentDocumentReferenceResolver;

    @Inject
    private Logger logger;

    /**
     * Try fixing model components.
     */
    public synchronized void fixComponents()
    {
        ComponentManager componentManager = this.contextComponentManagerProvider.get();

        boolean needsFixing = false;

        try {
            // Check if there is any chat model as we expect that there is always at least one chat model configured.
            needsFixing = componentManager.getInstanceList(ChatModel.class).isEmpty();
        } catch (ComponentLookupException e) {
            this.logger.warn("Failed to lookup chat model components: [{}]",
                ExceptionUtils.getRootCauseMessage(e));
        }

        if (needsFixing) {
            XWikiContext context = this.contextProvider.get();

            // Get all documents that contain an ModelsClass XObject.
            EntityReference classReference = this.componentBuilder.getClassReference();

            for (DocumentReference sourceDocumentReference : getDocumentsWithModelDefinitions(classReference)) {
                XWikiDocument document;
                try {
                    document = context.getWiki().getDocument(sourceDocumentReference, context);
                } catch (XWikiException e) {
                    this.logger.warn("Failed loading document [{}] to refresh model wiki components, skipping: [{}]",
                        sourceDocumentReference, ExceptionUtils.getRootCauseMessage(e));
                    continue;
                }

                document.getXObjects(classReference).stream()
                    .filter(Objects::nonNull)
                    .forEach(this::buildComponents);
            }
        }
    }

    private void buildComponents(BaseObject xObject)
    {
        ObjectReference objectReference = xObject.getReference();
        maybeUnregisterComponents(objectReference);

        try {
            List<WikiComponent> components = this.componentBuilder.buildComponents(objectReference);

            for (WikiComponent component : components) {
                this.wikiComponentManager.registerWikiComponent(component);
            }
        } catch (WikiComponentException e) {
            this.logger.warn("Failed to build the wiki component located in the document [{}]: [{}]",
                xObject.getDocumentReference(), ExceptionUtils.getRootCauseMessage(e));
        }
    }

    private void maybeUnregisterComponents(ObjectReference objectReference)
    {
        try {
            this.wikiComponentManager.unregisterWikiComponents(objectReference);
        } catch (WikiComponentException e) {
            this.logger.warn("Unable to unregister component(s) from the entity [{}] while refreshing"
                + " model components: [{}]", objectReference, ExceptionUtils.getRootCauseMessage(e));
        }
    }

    private List<DocumentReference> getDocumentsWithModelDefinitions(EntityReference classReference)
    {
        String className = this.entityReferenceSerializer.serialize(classReference);

        try {
            Query query =
                this.queryManager.createQuery("select distinct doc.fullName from Document doc, doc.object("
                + className + ") as document", Query.XWQL);
            List<String> results = query.execute();
            return results.stream()
                .map(this.currentDocumentReferenceResolver::resolve)
                .toList();
        } catch (QueryException e) {
            this.logger.warn("Failed getting the list of documents with model definitions: [{}]",
                ExceptionUtils.getRootCauseMessage(e));
        }

        return List.of();
    }
}
