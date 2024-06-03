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

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelDescriptor;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;

/**
 * Default implementation of {@link ChatModelManager}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Singleton
public class DefaultChatModelManager implements ChatModelManager
{
    @Inject
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private ModelComponentFixer modelComponentFixer;

    @Override
    public ChatModel getModel(String name, UserReference userReference, String wikiId) throws GPTAPIException
    {
        XWikiContext context = this.contextProvider.get();
        String currentWiki = context.getWikiId();
        try {
            context.setWikiId(wikiId);
            ChatModel result = getChatModelComponent(name);
            if (!result.hasAccess(userReference)) {
                throw new GPTAPIException(
                    String.format("User [%s] does not have access to chat model [%s] in wiki [%s].",
                        userReference, name, wikiId));
            }
            return result;
        } catch (ComponentLookupException e) {
            throw new GPTAPIException(
                String.format("Failed to get chat model with name [%s] in wiki [%s].", name, wikiId), e);
        } finally {
            context.setWikiId(currentWiki);
        }
    }

    private ChatModel getChatModelComponent(String name) throws ComponentLookupException
    {
        if (!this.componentManagerProvider.get().hasComponent(ChatModel.class, name)) {
            this.modelComponentFixer.fixComponents();
        }
        return this.componentManagerProvider.get().getInstance(ChatModel.class, name);
    }

    @Override
    public List<ChatModelDescriptor> getModels(UserReference userReference, String wikiId) throws GPTAPIException
    {
        XWikiContext context = this.contextProvider.get();
        String currentWiki = context.getWikiId();
        try {
            context.setWikiId(wikiId);

            List<ChatModel> models = this.componentManagerProvider.get().getInstanceList(ChatModel.class);
            if (models.isEmpty()) {
                this.modelComponentFixer.fixComponents();
                models = this.componentManagerProvider.get().getInstanceList(ChatModel.class);
            }
            return models.stream()
                .filter(ChatModel::isValid)
                .filter(model -> model.hasAccess(userReference))
                .map(ChatModel::getDescriptor)
                .sorted(Comparator.comparing(ChatModelDescriptor::getName))
                .collect(Collectors.toList());
        } catch (ComponentLookupException e) {
            throw new GPTAPIException(String.format("Failed to get chat models in wiki [%s].", wikiId), e);
        } finally {
            context.setWikiId(currentWiki);
        }
    }
}

