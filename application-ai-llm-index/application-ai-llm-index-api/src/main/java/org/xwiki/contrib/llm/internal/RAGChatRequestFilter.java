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
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.RequestError;

/**
 * A filter that adds context from the given collections to the request.
 *
 * @version $Id$
 * @since 0.3
 */
public class RAGChatRequestFilter extends AbstractChatRequestFilter
{
    private final List<String> collections;

    private CollectionManager collectionManager;

    /**
     * Constructor.
     *
     * @param collections the collections to use
     * @param collectionManager the collection manager
     */
    public RAGChatRequestFilter(List<String> collections, CollectionManager collectionManager)
    {
        this.collections = collections;
        this.collectionManager = collectionManager;
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
        String context = augmentRequest(request);
        if (!request.getMessages().isEmpty()) {
            ChatMessage lastMessage = request.getMessages().get(request.getMessages().size() - 1);
            lastMessage.setContent(context + "\n\n User message: " + lastMessage.getContent());
        }
        return request;
    }

    private String augmentRequest(ChatRequest request)
    {
        if (request.getMessages().isEmpty()) {
            return "No user message to augment.";
        }

        ChatMessage lastMessage = request.getMessages().get(request.getMessages().size() - 1);
        String message = lastMessage.getContent();

        StringBuilder contextBuilder = new StringBuilder();
        List<String> addedUrls = new ArrayList<>();

        // Perform solr similarity search on the last message
        try {
            List<List<String>> searchResults = collectionManager.similaritySearch(message, collections, 5);
            if (!searchResults.isEmpty()) {
                contextBuilder.append("Provided context: \n");
                for (List<String> result : searchResults) {
                    if (result.size() >= 3) {
                        String sourceURL = result.get(1);
                        // Check if URL has already been added
                        if (!addedUrls.contains(sourceURL)) {
                            String contentMsg = result.get(2);
                            contextBuilder.append(String.format(
                                    "Source: %s \n"
                                    + "Content: \n %s \n\n",
                                    sourceURL, contentMsg));
                            addedUrls.add(sourceURL); 
                        }
                    }
                }
                contextBuilder.append("Instructions: "
                                    + "Respond strictly in the following format: \n"
                                    + "Source: \n the provided URLs separated by new line"
                                    + "Answer: \n Formulate a response based on the provided context, "
                                    + "after you quoted the source "
                                    + "or inform the user that the requested information was found in the source.");
            } else {
                return "No similar content found.";
            }
        } catch (Exception e) {
            // Log the exception or handle it as needed
            return "Error during similarity search: " + e.getMessage();
        }

        return contextBuilder.toString();
    }

}
