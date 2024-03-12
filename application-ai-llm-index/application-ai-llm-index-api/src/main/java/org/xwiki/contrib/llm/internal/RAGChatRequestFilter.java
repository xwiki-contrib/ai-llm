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
import org.slf4j.Logger;
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
    private static final String PROVIDED_CONTEXT_STRING = "Provided context: %n";
    private static final String SOURCE_STRING = "Source: %s %n";
    private static final String CONTENT_CHUNK_STRING = "Content chunk: %n %s %n";

    private final List<String> collections;
    private CollectionManager collectionManager;
    private Integer maxResults;
    private String contextPrompt;
    private Logger logger;

    /**
     * Constructor.
     *
     * @param collections the collections to use
     * @param collectionManager the collection manager
     * @param maxResults the maximum number of results to return
     * @param contextPrompt the context prompt
     * @param logger the logger
     */
    public RAGChatRequestFilter(List<String> collections,
                                CollectionManager collectionManager,
                                Integer maxResults,
                                String contextPrompt, Logger logger)
    {
        this.collections = collections;
        this.collectionManager = collectionManager;
        this.maxResults = maxResults;
        this.contextPrompt = contextPrompt;
        this.logger = logger;
    }


    @Override
    public void processStreaming(ChatRequest request, FailableConsumer<ChatResponse, IOException> consumer)
        throws IOException, RequestError
    {
        super.processStreaming(addContext(request), consumer);
    }

    @Override
    public ChatResponse process(ChatRequest request) throws IOException, RequestError
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
            // If max results is not set, default to 10
            if (maxResults == null) {
                maxResults = 10;
            }
            List<List<String>> searchResults = collectionManager.similaritySearch(message, collections, maxResults);
            if (!searchResults.isEmpty()) {
                contextBuilder.append(PROVIDED_CONTEXT_STRING);
                for (List<String> result : searchResults) {
                    String sourceURL = result.get(1);
                    String contentMsg = result.get(2);

                    // Check if URL has already been added
                    if (!addedUrls.contains(sourceURL)) {
                        contextBuilder.append(String.format(SOURCE_STRING + CONTENT_CHUNK_STRING,
                                                            sourceURL, contentMsg));
                        addedUrls.add(sourceURL); 
                    } else {
                        contextBuilder.append(String.format(CONTENT_CHUNK_STRING, contentMsg));
                    }
                }
                contextBuilder.append(contextPrompt);
            } else {
                return "No similar content found.";
            }
        } catch (Exception e) {
            logger.error("Error during similarity search: {}", e.getMessage());
            return "There was an Error during similarity search";
        }

        return contextBuilder.toString();
    }

}
