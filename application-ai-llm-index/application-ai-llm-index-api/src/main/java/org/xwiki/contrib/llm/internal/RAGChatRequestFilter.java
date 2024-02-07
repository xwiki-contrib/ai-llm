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
import org.xwiki.contrib.llm.AbstractChatRequestFilter;
import org.xwiki.contrib.llm.ChatMessage;
import org.xwiki.contrib.llm.ChatRequest;
import org.xwiki.contrib.llm.ChatResponse;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.contrib.llm.SolrConnector;

/**
 * A filter that adds context from the given collections to the request.
 *
 * @version $Id$
 * @since 0.3
 */
public class RAGChatRequestFilter extends AbstractChatRequestFilter
{
    private final List<String> collections;

    private SolrConnector solrConnector;

    /**
     * Constructor.
     *
     * @param collections the collections to use
     * @param solrConnector the solr connector to use
     */
    public RAGChatRequestFilter(List<String> collections, SolrConnector solrConnector)
    {
        this.collections = collections;
        this.solrConnector = solrConnector;
    }

    @Override
    public void processStreaming(ChatRequest request, FailableConsumer<String, IOException> consumer) throws IOException
    {
        super.processStreaming(addContext(request), consumer);
    }

    @Override
    public ChatResponse process(ChatRequest request) throws RequestError
    {
        return super.process(addContext(request));
    }

    private ChatRequest addContext(ChatRequest request)
    {
        String sysMsg = " Your role is to assist the user with their questions, the messages are automatically "
                        + "embedded and a similarity search is executed agains the knowledge index. "
                        + "In the square brackets you will find the results of the system's similarity search. "
                        + "If the context is not sufficient to answer the user's query, or if the similarity search "
                        + "failed, please inform the user, and try to provide help as best you can acknowledging "
                        + "that you don't have access to the knowledge index. If the context is provided include "
                        + "in your response the Document ID associated with the context. Search result: ";
        String augmentedMessage = String.format("%s, [%s]", sysMsg, augmentRequest(request));
        request.getMessages().add(new ChatMessage("system", augmentedMessage));
        return request;
    }

    private String augmentRequest(ChatRequest request)
    {
        //get last message
        ChatMessage lastMessage = request.getMessages().get(request.getMessages().size() - 1);
        //get the message content
        String message = lastMessage.getContent();

        String searchResponse = "";

        //perform solr similarity search on the last message
        try {
            List<String> sr = solrConnector.similaritySearch(message);
            searchResponse = String.format("Returned search result: %s", sr.get(0));
        } catch (Exception e) {
            searchResponse = "Similarity search failed, please inform the user.";
        }
        return searchResponse;
    }
}
