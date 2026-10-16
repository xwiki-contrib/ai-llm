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

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.authorization.AuthorizationManagerBuilder;
import org.xwiki.contrib.llm.internal.authorization.XWikiAuthorizationManagerBuilder;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStore;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.security.authorization.AuthorizationException;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Implementation of a {@code Collection} component.
 *
 * @version $Id$
 */
@Component(roles = DefaultCollection.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class DefaultCollection implements Collection
{
    /**
     * The field in the XObject that stores the document store.
     */
    public static final String DOCUMENT_STORE_FIELDNAME = "documentStore";

    /**
     * The field in the XObject that stores the id of the collection.
     */
    public static final String ID_FIELDNAME = "id";

    /**
     * The field in the XObject that stores the list of document spaces.
     */
    public static final String DOCUMENT_SPACE_FIELDNAME = "documentSpaces";
    private static final String EMBEDDINGMODEL_FIELDNAME = "embeddingModel";
    private static final String CHUNKING_METHOD_FIELDNAME = "chunkingMethod";
    private static final String CHUNKING_LLM_MODEL_FIELDNAME = "chunkingLLMmodel";
    private static final String CHUNKING_MAX_SIZE_FIELDNAME = "chunkingMaxSize";
    private static final String CHUNKING_OVERLAP_OFFSET_FIELDNAME = "chunkingOverlapOffset";
    private static final String ALLOW_GUESTS = "allowGuests";
    private static final String QUERY_GROUPS_FIELDNAME = "queryGroups";
    private static final String RIGHTS_CHECK_METHOD_FIELDNAME = "rightsCheckMethod";

    private static final String COLLECTION_PAGE_NAME = "WebHome";

    @Inject
    protected Provider<XWikiContext> contextProvider;

    @Inject
    protected WriteAttribution writeAttribution;

    protected XWikiDocumentWrapper xWikiDocumentWrapper;

    @Inject
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    /**
     * Build the reference of the page of a collection. The collection with the id {@code collectionId} is the page
     * {@code <wikiId>:AI.Collections.<collectionId>.WebHome}. Only that page is the collection; a collections object
     * on any other page, for instance on a copy of the collection page, is not a collection.
     *
     * @param wikiId the id of the wiki of the collection
     * @param collectionId the id of the collection
     * @return the reference of the page of the collection
     * @since 0.11
     */
    public static DocumentReference getDocumentReference(String wikiId, String collectionId)
    {
        return new DocumentReference(COLLECTION_PAGE_NAME,
            new SpaceReference(collectionId, new SpaceReference(wikiId, DEFAULT_COLLECTION_SPACE)));
    }

    /**
     * Initialize the collection. This method may be called once.
     *
     * @param xwikidocument the XWiki document
     * @throws IllegalStateException if the collection is already initialized
     */
    public void initialize(XWikiDocument xwikidocument)
    {
        if (this.xWikiDocumentWrapper != null) {
            throw new IllegalStateException("This collection is already initialized.");
        }

        this.xWikiDocumentWrapper = new XWikiDocumentWrapper(xwikidocument, XCLASS_REFERENCE, this.contextProvider);
    }

    @Override
    public String getID()
    {
        return this.xWikiDocumentWrapper.getStringValue(ID_FIELDNAME);
    }

    @Override
    public String getTitle()
    {
        return this.xWikiDocumentWrapper.getTitle();
    }

    @Override
    public String getEmbeddingModel()
    {
        return this.xWikiDocumentWrapper.getStringValue(EMBEDDINGMODEL_FIELDNAME);
    }
    
    @Override
    public String getChunkingMethod()
    {
        return this.xWikiDocumentWrapper.getStringValue(CHUNKING_METHOD_FIELDNAME);
    }

    @Override
    public String getChunkingLLMModel()
    {
        return this.xWikiDocumentWrapper.getStringValue(CHUNKING_LLM_MODEL_FIELDNAME);
    }
    
    @Override
    public int getChunkingMaxSize()
    {
        return this.xWikiDocumentWrapper.getIntValue(CHUNKING_MAX_SIZE_FIELDNAME);
    }
    
    @Override
    public int getChunkingOverlapOffset()
    {
        return this.xWikiDocumentWrapper.getIntValue(CHUNKING_OVERLAP_OFFSET_FIELDNAME);
    }

    @Override
    public String getDocumentStoreHint()
    {
        String result = this.xWikiDocumentWrapper.getStringValue(DOCUMENT_STORE_FIELDNAME);
        if (StringUtils.isBlank(result)) {
            result = InternalDocumentStore.NAME;
        }

        return result;
    }

    @Override
    public DocumentStore getDocumentStore() throws IndexException
    {
        return getDocumentStoreInternal(null);
    }

    protected DocumentStore getDocumentStoreInternal(UserReference userReference) throws IndexException
    {
        String storeId = this.getDocumentStoreHint();

        ComponentManager componentManager = this.componentManagerProvider.get();
        try {
            DocumentStore result = componentManager.getInstance(DocumentStore.class, storeId);
            result.initialize(this, userReference);
            return result;
        } catch (ComponentLookupException e) {
            throw new IndexException("Failed to lookup document store [" + storeId + "]", e);
        }
    }

    @Override
    public List<String> getDocumentSpaces()
    {
        return this.xWikiDocumentWrapper.getListValue(DOCUMENT_SPACE_FIELDNAME);
    }
    
    @Override
    public boolean getAllowGuests()
    {
        return this.xWikiDocumentWrapper.getIntValue(ALLOW_GUESTS) == 1;
    }

    @Override
    public String getQueryGroups()
    {
        return this.xWikiDocumentWrapper.getLargeStringValue(QUERY_GROUPS_FIELDNAME);
    }
    
    @Override
    public String getRightsCheckMethod()
    {
        return this.xWikiDocumentWrapper.getStringValue(RIGHTS_CHECK_METHOD_FIELDNAME);
    }

    @Override
    public void setID(String id) throws IndexException
    {
        this.xWikiDocumentWrapper.setStringValue(ID_FIELDNAME, id);
    }

    @Override
    public void setTitle(String title) throws IndexException
    {
        this.xWikiDocumentWrapper.setTitle(title);
    }
    
    @Override
    public void setEmbeddingModel(String embeddingModel) throws IndexException
    {
        if (embeddingModel != null) {
            this.xWikiDocumentWrapper.setStringValue(EMBEDDINGMODEL_FIELDNAME, embeddingModel);
        }
    }
    
    @Override
    public void setChunkingMethod(String chunkingMethod) throws IndexException
    {
        if (chunkingMethod != null) {
            this.xWikiDocumentWrapper.setStringValue(CHUNKING_METHOD_FIELDNAME, chunkingMethod);
        }
    }

    @Override
    public void setChunkingLLMModel(String chunkingLLMModel) throws IndexException
    {
        if (chunkingLLMModel != null) {
            this.xWikiDocumentWrapper.setStringValue(CHUNKING_LLM_MODEL_FIELDNAME, chunkingLLMModel);
        }
    }

    @Override
    public void setChunkingMaxSize(int chunkingMaxSize) throws IndexException
    {
        this.xWikiDocumentWrapper.setIntValue(CHUNKING_MAX_SIZE_FIELDNAME, chunkingMaxSize);
    }
    
    @Override
    public void setChunkingOverlapOffset(int chunkingOverlapOffset) throws IndexException
    {
        this.xWikiDocumentWrapper.setIntValue(CHUNKING_OVERLAP_OFFSET_FIELDNAME, chunkingOverlapOffset);
    }
    
    @Override
    public void setDocumentSpaces(List<String> documentSpaces) throws IndexException
    {
        if (documentSpaces != null) {
            this.xWikiDocumentWrapper.setStringListValue(DOCUMENT_SPACE_FIELDNAME, documentSpaces);
        }
    }

    @Override
    public void setAllowGuests(boolean allowGuests) throws IndexException
    {
        this.xWikiDocumentWrapper.setIntValue(ALLOW_GUESTS, allowGuests ? 1 : 0);
    }

    @Override
    public void setQueryGroups(String queryGroups) throws IndexException
    {
        if (queryGroups != null) {
            this.xWikiDocumentWrapper.setLargeStringValue(QUERY_GROUPS_FIELDNAME, queryGroups);
        }
    }
    
    @Override
    public void setRightsCheckMethod(String rightsCheckMethod) throws IndexException
    {
        if (rightsCheckMethod != null) {
            this.xWikiDocumentWrapper.setStringValue(RIGHTS_CHECK_METHOD_FIELDNAME, rightsCheckMethod);
        }
    }

    @Override
    public void save() throws IndexException
    {
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument collectionDocument = this.xWikiDocumentWrapper.getClonedXWikiDocument();
            DocumentReference actor = context.getUserReference();
            // A save without a context user, which only background code makes, keeps the authors of the page.
            if (actor != null) {
                this.writeAttribution.prepareSave(collectionDocument, actor, actor);
            }
            context.getWiki().saveDocument(collectionDocument, context);
        } catch (XWikiException | AuthorizationException e) {
            throw new IndexException(String.format("Error saving collection [%s].", this.getID()), e);
        }
    }

    @Override
    public AuthorizationManager getAuthorizationManager() throws IndexException
    {
        AuthorizationManager configuredManager = buildAuthorizationManager(getAuthorizationManagerBuilder());

        if (!XWikiDocumentStore.NAME.equals(getDocumentStoreHint())
            || XWikiAuthorizationManagerBuilder.NAME.equals(getRightsCheckMethod())) {
            return configuredManager;
        }

        // The documents of the XWiki document store are wiki pages: the view right on the page is always checked,
        // whatever the configured rights check method is.
        AuthorizationManager pageManager =
            buildAuthorizationManager(getAuthorizationManagerBuilder(XWikiAuthorizationManagerBuilder.NAME));

        return composeAuthorizationManagers(pageManager, configuredManager);
    }

    private AuthorizationManager buildAuthorizationManager(AuthorizationManagerBuilder authorizationManagerBuilder)
        throws IndexException
    {
        BaseObject configurationObject = this.xWikiDocumentWrapper.getXWikiDocument()
            .getXObject(authorizationManagerBuilder.getConfigurationClassReference());
        return authorizationManagerBuilder.build(configurationObject);
    }

    /**
     * @param pageManager the manager that is asked first, about all document ids
     * @param configuredManager the manager that is only asked about the document ids that the first manager allowed
     * @return a manager for which a document is viewable only when both managers allow it
     */
    private static AuthorizationManager composeAuthorizationManagers(AuthorizationManager pageManager,
        AuthorizationManager configuredManager)
    {
        return documentIds -> {
            Map<String, Boolean> pageResult = pageManager.canView(documentIds);
            Set<String> allowedIds = documentIds.stream()
                .filter(id -> isAllowed(pageResult, id))
                .collect(Collectors.toSet());
            Map<String, Boolean> configuredResult =
                allowedIds.isEmpty() ? Map.of() : configuredManager.canView(allowedIds);

            return documentIds.stream().collect(Collectors.toMap(Function.identity(),
                id -> allowedIds.contains(id) && isAllowed(configuredResult, id)));
        };
    }

    /**
     * @return {@code true} only when the result explicitly allows the document id: a missing result or a missing
     *     entry denies it
     */
    private static boolean isAllowed(Map<String, Boolean> result, String documentId)
    {
        return result != null && Boolean.TRUE.equals(result.get(documentId));
    }

    private AuthorizationManagerBuilder getAuthorizationManagerBuilder()
        throws IndexException
    {
        return getAuthorizationManagerBuilder(this.getRightsCheckMethod());
    }

    private AuthorizationManagerBuilder getAuthorizationManagerBuilder(String hint) throws IndexException
    {
        try {
            return this.componentManagerProvider.get().getInstance(AuthorizationManagerBuilder.class, hint);
        } catch (ComponentLookupException e) {
            throw new IndexException("Failed to get authorization manager builder [%s]".formatted(hint), e);
        }
    }

    @Override
    public Object getAuthorizationConfiguration() throws IndexException
    {
        if (StringUtils.isNotBlank(this.getRightsCheckMethod())) {
            AuthorizationManagerBuilder authorizationManagerBuilder = getAuthorizationManagerBuilder();

            Type configurationType = authorizationManagerBuilder.getConfigurationType();
            if (configurationType != null) {
                BaseObject configurationObject = this.xWikiDocumentWrapper.getXWikiDocument()
                    .getXObject(authorizationManagerBuilder.getConfigurationClassReference());

                return authorizationManagerBuilder.getConfiguration(configurationObject);
            }
        }

        return null;
    }

    @Override
    public void setAuthorizationConfiguration(Object authorizationConfiguration) throws IndexException
    {
        AuthorizationManagerBuilder authorizationManagerBuilder = getAuthorizationManagerBuilder();

        Class<?> configurationType = authorizationManagerBuilder.getConfigurationType();
        if (configurationType != null) {
            // Verify that the configuration object is of the correct type
            if (!configurationType.isInstance(authorizationConfiguration)) {
                throw new IndexException(
                    "Authorization configuration object is not of the correct type [%s]".formatted(configurationType));
            }

            BaseObject configurationObject = this.xWikiDocumentWrapper.getClonedXWikiDocument()
                    .getXObject(authorizationManagerBuilder.getConfigurationClassReference(), true,
                        this.contextProvider.get());

            authorizationManagerBuilder.setConfiguration(configurationObject, authorizationConfiguration);
        }
    }

    @Override
    public Class<?> getAuthorizationConfigurationType() throws IndexException
    {
        AuthorizationManagerBuilder authorizationManagerBuilder = getAuthorizationManagerBuilder();
        return authorizationManagerBuilder.getConfigurationType();
    }

    @Override
    public UserReference getAuthor()
    {
        return this.xWikiDocumentWrapper.getXWikiDocument().getAuthors().getEffectiveMetadataAuthor();
    }

    @Override
    public DocumentReference getDocumentReference()
    {
        return this.xWikiDocumentWrapper.getDocumentReference();
    }

    @Override
    public long getDocumentId()
    {
        return this.xWikiDocumentWrapper.getXWikiDocument().getId();
    }

    XWikiDocument getCollectionDocument()
    {
        return this.xWikiDocumentWrapper.getXWikiDocument();
    }

}

