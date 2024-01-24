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
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.function.Failable;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelDescriptor;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.user.UserReference;

/**
 * Default implementation of {@link ChatModelManager}.
 * Gets models from all named implementations of {@link ChatModelManager}.
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
    private Provider<ComponentManager> contextComponentManagerProvider;

    @Override
    public ChatModel getModel(String name, UserReference userReference, String wikiId) throws GPTAPIException
    {
        List<ChatModelManager> chatModelManagers = getChatModelManagers();

        GPTAPIException lastException = null;
        for (ChatModelManager chatModelManager : chatModelManagers) {
            if (chatModelManager instanceof DefaultChatModelManager) {
                continue;
            }

            try {
                ChatModel model = chatModelManager.getModel(name, userReference, wikiId);
                if (model != null) {
                    return model;
                }
            } catch (GPTAPIException e) {
                lastException = e;
            }
        }

        if (lastException != null) {
            throw lastException;
        } else {
            throw new GPTAPIException("No model found for [" + name + "]");
        }
    }

    @Override
    public List<ChatModelDescriptor> getModels(UserReference userReference, String wikiId) throws GPTAPIException
    {
        // Use a Failable.stream to support throwing exceptions in the stream.
        return Failable.stream(this.getChatModelManagers())
            .filter(m -> !(m instanceof DefaultChatModelManager))
            .map(m -> m.getModels(userReference, wikiId))
            .stream().flatMap(List::stream)
            .collect(Collectors.toList());
    }

    private List<ChatModelManager> getChatModelManagers() throws GPTAPIException
    {
        ComponentManager componentManager = this.contextComponentManagerProvider.get();

        try {
            return componentManager.getInstanceList(ChatModelManager.class);
        } catch (Exception e) {
            throw new GPTAPIException("Failed to get chat model managers", e);
        }
    }
}
