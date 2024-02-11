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
import java.util.ArrayList;
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
        List<String> sources = augmentRequest(request);
        String sourceURL = sources.get(0);
        String contentMsg = sources.get(1);
        String sysMsg = String.format("Provided context: %n"
                       + "Source: %s %n "
                       + "Content: %n %s %n"
                       + "Instructions: "
                       + "Respond strictly in the following format without the bracket: %n"
                       + "Source: [the provided URL] %n"
                       + "Answer: [Formulate a response based on the provided context, after you quoted the source]",
                        sourceURL, contentMsg);
       
        request.getMessages().add(new ChatMessage("system", sysMsg));
        return request;
    }

    private List<String> augmentRequest(ChatRequest request)
    {
        List<String> sources = new ArrayList<>();
        ChatMessage lastMessage = request.getMessages().get(request.getMessages().size() - 1);
        String message = lastMessage.getContent();

        //perform solr similarity search on the last message
        try {
            List<List<String>> sr = solrConnector.similaritySearch(message);
            //add url of the source to the sources list
            sources.add(sr.get(0).get(1));
            //add the source content to the search response
            sources.add(sr.get(0).get(2));
        } catch (Exception e) {
            sources = new ArrayList<>();
            sources.add("");
            sources.add("Source not found");
        }
        return sources;
    }
}
