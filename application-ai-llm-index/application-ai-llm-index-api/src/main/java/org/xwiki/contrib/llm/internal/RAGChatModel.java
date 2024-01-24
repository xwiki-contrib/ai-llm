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
import java.util.List;

import org.apache.commons.lang3.function.FailableConsumer;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.ChatModel;
import org.xwiki.contrib.llm.ChatRequest;
import org.xwiki.contrib.llm.ChatResponse;
import org.xwiki.contrib.llm.RequestError;

/**
 * RAG-enabled chat model.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = RAGChatModel.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class RAGChatModel implements ChatModel
{
    private ChatModel underlyingModel;

    private List<String> collections;

    /**
     * Initialize the RAG model.
     *
     * @param underlyingModel the underlying model to which requests should be forwarded
     * @param collections the collections to use for RAG
     */
    public void initialize(ChatModel underlyingModel, List<String> collections)
    {
        this.underlyingModel = underlyingModel;
        this.collections = collections;
    }

    @Override
    public void processStreaming(ChatRequest request, FailableConsumer<String, IOException> consumer) throws IOException
    {

        this.underlyingModel.processStreaming(addContext(request), consumer);
    }

    @Override
    public ChatResponse process(ChatRequest request) throws RequestError
    {
        return this.underlyingModel.process(addContext(request));
    }

    private ChatRequest addContext(ChatRequest request)
    {
        // TODO: add context to the request using the given collections.
        return request;
    }

    @Override
    public boolean supportsStreaming()
    {
        return this.underlyingModel.supportsStreaming();
    }
}
