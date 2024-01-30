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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * This component creates {@link org.xwiki.contrib.llm.ChatModel} and {@link org.xwiki.contrib.llm.EmbeddingModel}
 * components from {@code AI.Models.Code.ModelsClass} objects.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named("AI.Models.Code.ModelsClass")
@Singleton
public class ModelWikiObjectComponentBuilder implements WikiObjectComponentBuilder
{
    // Local reference to the class AI.Models.Code.ModelsClass that should trigger the builder.
    private static final LocalDocumentReference CLASS_REFERENCE =
        new LocalDocumentReference(List.of("AI", "Models", "Code"), "ModelsClass");

    /**
     * The name of the field containing the type of the model.
     */
    private static final String TYPE_FIELD = "type";

    /**
     * The value indicating that the model is a LLM model.
     */
    private static final String TYPE_LLM = "llm";

    /**
     * The value indicating that the model is an embedding model.
     */
    private static final String TYPE_EMBEDDING = "emb";

    /**
     * The name of the field containing the context size.
     */
    private static final String CONTEXT_SIZE_FIELD = "contextSize";

    /**
     * The name of the field containing the dimensions.
     */
    private static final String DIMENSIONS_FIELD = "dimensions";

    /**
     * The name of the field containing the groups that can access the model.
     */
    private static final String GROUPS_FIELD = "groups";

    /**
     * The name of the field containing the model id.
     */
    private static final String MODEL_FIELD = "model";

    /**
     * The name of the field containing the server name that should be called.
     */
    private static final String SERVER_NAME_FIELD = "serverName";


    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private ComponentManager componentManager;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Override
    public EntityReference getClassReference()
    {
        return CLASS_REFERENCE;
    }

    @Override
    public List<WikiComponent> buildComponents(ObjectReference reference) throws WikiComponentException
    {
        XWikiContext context = this.contextProvider.get();
        try {
            XWikiDocument document = context.getWiki().getDocument(reference.getDocumentReference(), context);
            // Check wiki admin rights.
            if (!this.authorizationManager.hasAccess(Right.ADMIN, document.getAuthorReference(),
                document.getDocumentReference().getWikiReference())) {
                throw new WikiComponentException(String.format(
                    "Failed to build component for object [%s], user [%s] does not have admin rights on the wiki.",
                    reference, document.getDocumentReference()));
            }

            BaseObject xObject = document.getXObject(reference);

            ModelConfiguration modelConfiguration = buildModelConfiguration(xObject);

            String modelType = xObject.getStringValue(TYPE_FIELD);
            if (TYPE_LLM.equals(modelType)) {
                return List.of(new OpenAIChatModel(modelConfiguration, this.componentManager));
            } else if (TYPE_EMBEDDING.equals(modelType)) {
                return List.of(new OpenAIEmbeddingModel(modelConfiguration, this.componentManager));
            } else {
                throw new WikiComponentException(String.format("Unknown model type [%s]", modelType));
            }
        } catch (XWikiException e) {
            throw new WikiComponentException(String.format("Failed to load document for [%s]", reference), e);
        } catch (ComponentLookupException e) {
            throw new WikiComponentException(String.format("Failed to lookup components for [%s]", reference), e);
        }
    }

    private ModelConfiguration buildModelConfiguration(BaseObject xObject)
    {
        String groupString = xObject.getStringValue(GROUPS_FIELD);
        List<DocumentReference> groups = Arrays.stream(StringUtils.split(groupString, ','))
            .map(String::trim)
            .map(this.documentReferenceResolver::resolve)
            .collect(Collectors.toList());

        ModelConfiguration modelConfiguration = new ModelConfiguration();
        modelConfiguration.setServerName(xObject.getStringValue(SERVER_NAME_FIELD));
        modelConfiguration.setModel(xObject.getStringValue(MODEL_FIELD));
        modelConfiguration.setDimensions(xObject.getIntValue(DIMENSIONS_FIELD));
        modelConfiguration.setContextSize(xObject.getIntValue(CONTEXT_SIZE_FIELD));
        modelConfiguration.setId(this.localEntityReferenceSerializer.serialize(xObject.getDocumentReference()));
        modelConfiguration.setName(xObject.getOwnerDocument().getTitle());
        modelConfiguration.setDocumentReference(xObject.getDocumentReference());
        modelConfiguration.setAuthor(xObject.getOwnerDocument().getAuthors().getEffectiveMetadataAuthor());
        modelConfiguration.setAllowedGroups(groups);
        return modelConfiguration;
    }
}
