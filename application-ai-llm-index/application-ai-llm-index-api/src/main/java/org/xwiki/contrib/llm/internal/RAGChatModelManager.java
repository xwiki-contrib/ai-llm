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

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelDescriptor;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.user.UserReference;

/**
 * Implementation of {@code ChatModelManager} to list all models with RAG support.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named("rag")
@Singleton
public class RAGChatModelManager implements ChatModelManager
{
    @Inject
    @Named("openai")
    private ChatModelManager openaiChatModelManager;

    @Inject
    private Provider<RAGChatModel> ragChatModelProvider;

    @Override
    public ChatModel getModel(String name, UserReference userReference, String wikiId) throws GPTAPIException
    {
        // TODO: check if the name is a valid RAG model name, and load the configuration.
        // TODO: load the list of collections to use.
        List<String> collections = List.of();
        // TODO: load the actually configured underlying model.
        ChatModel underlyingModel = this.openaiChatModelManager.getModel(name, userReference, wikiId);
        RAGChatModel result = this.ragChatModelProvider.get();
        result.initialize(underlyingModel, collections);
        return result;
    }

    @Override
    public List<ChatModelDescriptor> getModels(UserReference userReference, String wikiId) throws GPTAPIException
    {
        // TODO: implement, list all configured models with RAG support.
        return List.of();
    }
}
