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

import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.wiki.WikiComponent;
import org.xwiki.component.wiki.WikiComponentScope;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceSerializer;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;

/**
 * Abstract class for chat and embedding models.
 *
 * @version $Id$
 * @since 0.3
 */
public abstract class AbstractModel implements WikiComponent
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractModel.class);

    protected final GroupManager groupManager;

    protected final UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    protected final GPTAPIConfigProvider configProvider;

    protected final ModelConfiguration modelConfiguration;

    protected AbstractModel(ModelConfiguration modelConfiguration, ComponentManager componentManager)
        throws ComponentLookupException
    {
        this.modelConfiguration = modelConfiguration;
        this.groupManager = componentManager.getInstance(GroupManager.class);
        this.userReferenceSerializer =
            componentManager.getInstance(UserReferenceSerializer.TYPE_DOCUMENT_REFERENCE, "document");
        this.configProvider = componentManager.getInstance(GPTAPIConfigProvider.class);

    }

    @Override
    public DocumentReference getDocumentReference()
    {
        return this.modelConfiguration.getObjectReference().getDocumentReference();
    }

    @Override
    public EntityReference getEntityReference()
    {
        return this.modelConfiguration.getObjectReference();
    }

    @Override
    public DocumentReference getAuthorReference()
    {
        return this.userReferenceSerializer.serialize(this.modelConfiguration.getAuthor());
    }

    @Override
    public String getRoleHint()
    {
        return this.modelConfiguration.getID();
    }

    protected GPTAPIConfig getConfig()
    {
        try {
            String wiki = getWikiReference().getName();
            return this.configProvider.getConfigObjects(wiki).get(this.modelConfiguration.getServerName());
        } catch (GPTAPIException e) {
            LOGGER.warn("Failed to get config for server [{}]", this.modelConfiguration.getServerName(), e);
            return null;
        }
    }

    private WikiReference getWikiReference()
    {
        return this.modelConfiguration.getObjectReference().getDocumentReference().getWikiReference();
    }

    @Override
    public WikiComponentScope getScope()
    {
        return WikiComponentScope.WIKI;
    }

    /**
     * @param user the user to check access for
     * @return {@code true} if the user has access to the model, {@code false} otherwise.
     */
    public boolean hasAccess(UserReference user)
    {
        if (this.modelConfiguration.isAllowGuests()) {
            return true;
        }
        DocumentReference documentUserReference = this.userReferenceSerializer.serialize(user);
        Collection<DocumentReference> userGroups;
        try {
            userGroups = this.groupManager.getGroups(documentUserReference, getWikiReference(), true);
        } catch (GroupException e) {
            LOGGER.warn("Failed to get groups for user [{}]", documentUserReference, e);
            return false;
        }

        return this.modelConfiguration.getAllowedGroups().stream().anyMatch(userGroups::contains);
    }

    /**
     * @return {@code true} if the model is valid, {@code false} otherwise.
     */
    public boolean isValid()
    {
        return getConfig() != null;
    }
}
