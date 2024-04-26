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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.function.FailableConsumer;
import org.slf4j.Logger;
import org.xwiki.contrib.llm.AbstractChatRequestFilter;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.contrib.llm.openai.ChatCompletionChunk;
import org.xwiki.contrib.llm.openai.ChatCompletionChunkChoice;
import org.xwiki.contrib.llm.openai.ChatCompletionRequest;
import org.xwiki.contrib.llm.openai.ChatCompletionResult;
import org.xwiki.contrib.llm.openai.ChatMessage;
import org.xwiki.contrib.llm.openai.Context;

/**
 * A filter that adds context from the given collections to the request.
 *
 * @version $Id$
 * @since 0.3
 */
public class RAGChatRequestFilter extends AbstractChatRequestFilter
{
    private static final String SEARCH_RESULTS_STRING = "Search results: %n";
    private static final String SOURCE_STRING = "%s %n";
    private static final String CONTENT_CHUNK_STRING = "Content chunk: %n %s %n";
    private static final String SIMILARITY_SEARCH_ERROR_MSG = "There was an error during similarity search";
    private static final String ERROR_LOG_FORMAT = "{}: {}";


    private final List<String> collections;
    private CollectionManager collectionManager;
    private Integer maxResults;
    private String contextPrompt;
    private Logger logger;

    // Global state to hold search results
    private Map<String, List<List<String>>> searchResultsCache = new ConcurrentHashMap<>();

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
    public void processStreaming(ChatCompletionRequest request,
        FailableConsumer<ChatCompletionChunk, IOException> consumer) throws IOException, RequestError
    {
        // First, modify the request with additional context as needed
        ChatCompletionRequest modifiedRequest = addContext(request);
    
        // Get the sources from the search results cache or perform the search
        List<List<String>> searchResults = getSearchResults(request);
        String sources = extractURLsAndformat(searchResults);

        long timestamp = System.currentTimeMillis() / 1000;

        // Set the id to a random UUID
        String id = UUID.randomUUID().toString();

        // Create and send a custom ChatCompletionChunk with the sources
        ChatMessage chatMessage = new ChatMessage("assistant", sources + "\n",
            formatSearchResults(searchResults));
        ChatCompletionChunkChoice choice = new ChatCompletionChunkChoice(0, chatMessage, null);
        ChatCompletionChunk sourcesResponse = new ChatCompletionChunk(id, timestamp, request.model(), List.of(choice));
        consumer.accept(sourcesResponse);

        // Wrap the consumer to add the same timestamp and id to all responses
        FailableConsumer<ChatCompletionChunk, IOException> wrappedConsumer = response -> {
            ChatCompletionChunk wrappedResponse = new ChatCompletionChunk(id, timestamp, response.model(),
                response.choices());
            consumer.accept(wrappedResponse);
        };

        // Now, use the super implementation with the modified request
        super.processStreaming(modifiedRequest, wrappedConsumer);
    }
    

    @Override
    public ChatCompletionResult process(ChatCompletionRequest request) throws IOException, RequestError
    {
        List<Context> searchResults = formatSearchResults(getSearchResults(request));
        ChatCompletionRequest modifiedRequest = addContext(request);
        ChatCompletionResult response = super.process(modifiedRequest);
        // Get the message from the response and add the context
        if (!response.choices().isEmpty()) {
            ChatMessage message = response.choices().get(0).message();
            message.setContext(searchResults);
        }
        return response;
    }

    private ChatCompletionRequest addContext(ChatCompletionRequest request)
    {
        String searchResults = augmentRequest(request);
        String updatedContextPrompt = this.contextPrompt.replace("{{search_results}}", searchResults);
        ChatMessage systemMessage = new ChatMessage("system", updatedContextPrompt);
        // logger.info("System message: " + systemMessage.getContent());
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        // logger.info("ALL MESSAGERS: " + messages);
        messages.add(0, systemMessage);

        return ChatCompletionRequest.builder(request)
            .setMessages(messages)
            .build();
    }


    private String augmentRequest(ChatCompletionRequest request)
    {
        if (request.messages().isEmpty()) {
            return "No user message to augment.";
        }

        ChatMessage lastMessage = request.messages().get(request.messages().size() - 1);
        String message = lastMessage.getContent();

        // Check if search results are already cached
        if (searchResultsCache.containsKey(message)) {
            return buildContext(searchResultsCache.get(message));
        }

        // Perform solr similarity search on the last message
        try {
            // If max results is not set, default to 10
            if (maxResults == null) {
                maxResults = 10;
            }
            List<List<String>> searchResults = collectionManager.similaritySearch(message, collections, maxResults);
            
            // Cache the search results
            searchResultsCache.put(message, searchResults);
            
            return buildContext(searchResults);
        } catch (Exception e) {
            logger.error(ERROR_LOG_FORMAT, SIMILARITY_SEARCH_ERROR_MSG, e.getMessage());
            return SIMILARITY_SEARCH_ERROR_MSG;
        }
    }

    private String buildContext(List<List<String>> searchResults)
    {
        if (searchResults.isEmpty()) {
            return "No similar content found.";
        }

        StringBuilder contextBuilder = new StringBuilder();
        List<String> addedUrls = new ArrayList<>();

        contextBuilder.append(SEARCH_RESULTS_STRING);
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

        return contextBuilder.toString();
    }

    private List<List<String>> getSearchResults(ChatCompletionRequest request)
    {
        if (request.messages().isEmpty()) {
            return Collections.emptyList();
        }
    
        ChatMessage lastMessage = request.messages().get(request.messages().size() - 1);
        String message = lastMessage.getContent();
    
        // Check if search results are already cached
        if (searchResultsCache.containsKey(message)) {
            return searchResultsCache.get(message);
        }
    
        // Perform solr similarity search on the last message
        try {
            // If max results is not set, default to 10
            if (maxResults == null) {
                maxResults = 10;
            }
            List<List<String>> searchResults = collectionManager.similaritySearch(message, collections, maxResults);
            
            // Cache the search results
            searchResultsCache.put(message, searchResults);
            
            return searchResults;
        } catch (Exception e) {
            logger.error(ERROR_LOG_FORMAT, SIMILARITY_SEARCH_ERROR_MSG, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    private List<Context> formatSearchResults(List<List<String>> searchResults)
    {
        return searchResults.stream()
            .filter(result -> result.size() == 4)
            .map(result -> new Context(result.get(0), result.get(1), result.get(2), Double.parseDouble(result.get(3))))
            .toList();
    }
    

    private String extractURLsAndformat(List<List<String>> searchResults)
    {
        StringBuilder sourcesBuilder = new StringBuilder();
        List<String> addedUrls = new ArrayList<>();

        for (List<String> result : searchResults) {
            String sourceURL = result.get(1);

            // Check if URL has already been added
            if (!addedUrls.contains(sourceURL)) {
                sourcesBuilder.append(String.format(SOURCE_STRING, sourceURL));
                addedUrls.add(sourceURL);
            }
        }

        return sourcesBuilder.toString();
    }
}
