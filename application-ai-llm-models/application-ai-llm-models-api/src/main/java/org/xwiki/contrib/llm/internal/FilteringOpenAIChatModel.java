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

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

import org.apache.commons.lang3.function.FailableConsumer;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatModelDescriptor;
import org.xwiki.contrib.llm.ChatRequest;
import org.xwiki.contrib.llm.ChatRequestFilter;
import org.xwiki.contrib.llm.ChatResponse;
import org.xwiki.contrib.llm.RequestError;

/**
 * Chat model implementation that uses the {@link OpenAIChatModel} with the given {@link ChatRequestFilter} filter
 * objects.
 *
 * @version $Id$
 * @since 0.3
 */
public class FilteringOpenAIChatModel extends AbstractModel implements ChatModel
{
    private final ChatRequestFilter firstFilter;

    /**
     * Initialize the model.
     *
     * @param modelConfiguration the configuration of the model
     * @param filters the filters to apply to the requests
     * @param componentManager the component manager
     */
    public FilteringOpenAIChatModel(ModelConfiguration modelConfiguration, List<ChatRequestFilter> filters,
        ComponentManager componentManager)
        throws ComponentLookupException
    {
        super(modelConfiguration, componentManager);

        // Build the chain of filters
        ChatRequestFilter current = null;
        for (ChatRequestFilter filter : filters) {
            if (current != null) {
                current.setNext(filter);
            }
            current = filter;
        }

        OpenAIChatModel chatModel = new OpenAIChatModel(modelConfiguration, componentManager);
        if (current != null) {
            current.setNext(chatModel);
        }

        this.firstFilter = filters.isEmpty() ? chatModel : filters.get(0);
    }

    @Override
    public Type getRoleType()
    {
        return ChatModel.class;
    }

    @Override
    public void processStreaming(ChatRequest request, FailableConsumer<ChatResponse, IOException> consumer)
        throws IOException, RequestError
    {
        this.firstFilter.processStreaming(request, consumer);
    }

    @Override
    public ChatResponse process(ChatRequest request) throws IOException, RequestError
    {
        return this.firstFilter.process(request);
    }

    @Override
    public boolean supportsStreaming()
    {
        return getConfig().getCanStream();
    }

    @Override
    public ChatModelDescriptor getDescriptor()
    {
        return new ChatModelDescriptor(this.getRoleHint(), this.modelConfiguration.getName(),
            this.modelConfiguration.getContextSize(), supportsStreaming());
    }
}
