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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang.exception.ExceptionUtils;
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
    private final CollectionManager collectionManager;
    private final int maxResults;
    private final String contextPrompt;
    private final Logger logger;

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
        this.maxResults = maxResults != null && maxResults > 0 ? maxResults : 10;
        this.contextPrompt = contextPrompt;
        this.logger = logger;
    }

    @Override
    public void processStreaming(ChatCompletionRequest request,
        FailableConsumer<ChatCompletionChunk, IOException> consumer) throws IOException, RequestError
    {
        // Get the sources from the search results cache or perform the search
        List<Context> searchResults = getSearchResults(request);

        // First, modify the request with additional context as needed
        ChatCompletionRequest modifiedRequest = addContext(request, searchResults);
        String sources = extractURLsAndformat(searchResults);

        long timestamp = System.currentTimeMillis() / 1000;

        // Set the id to a random UUID
        String id = UUID.randomUUID().toString();

        // Create and send a custom ChatCompletionChunk with the sources
        ChatMessage chatMessage = new ChatMessage("assistant", sources + "\n", searchResults);
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
        List<Context> searchResults = getSearchResults(request);
        ChatCompletionRequest modifiedRequest = addContext(request, searchResults);
        ChatCompletionResult response = super.process(modifiedRequest);
        // Get the message from the response and add the context
        if (!response.choices().isEmpty()) {
            ChatMessage message = response.choices().get(0).message();
            message.setContext(searchResults);
            // Add the sources to the content
            message.setContent(extractURLsAndformat(searchResults) + "\n\n" + message.getContent());
        }
        return response;
    }

    private ChatCompletionRequest addContext(ChatCompletionRequest request, List<Context> context)
    {
        String searchResults = buildContext(context);
        String updatedContextPrompt = this.contextPrompt.replace("{{search_results}}", searchResults);
        ChatMessage systemMessage = new ChatMessage("system", updatedContextPrompt);
        // logger.info("System message: " + systemMessage.getContent());
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        // logger.info("ALL MESSAGERS: " + messages);
        messages.add(0, systemMessage);

        return request.but()
            .messages(messages)
            .build();
    }

    private String buildContext(List<Context> searchResults)
    {
        if (searchResults.isEmpty()) {
            return "No similar content found.";
        }

        StringBuilder contextBuilder = new StringBuilder();
        Set<String> addedUrls = new HashSet<>();

        contextBuilder.append(SEARCH_RESULTS_STRING);
        for (Context result : searchResults) {
            String sourceURL = result.url();
            String contentMsg = result.content();

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

    private List<Context> getSearchResults(ChatCompletionRequest request)
    {
        if (request.messages().isEmpty()) {
            return Collections.emptyList();
        }
    
        ChatMessage lastMessage = request.messages().get(request.messages().size() - 1);
        String message = lastMessage.getContent();

        // Perform solr similarity search on the last message
        try {
            return this.collectionManager.similaritySearch(message, this.collections, this.maxResults);
        } catch (Exception e) {
            this.logger.error(ERROR_LOG_FORMAT, SIMILARITY_SEARCH_ERROR_MSG, ExceptionUtils.getRootCauseMessage(e));
            return Collections.emptyList();
        }
    }

    private String extractURLsAndformat(List<Context> searchResults)
    {
        StringBuilder sourcesBuilder = new StringBuilder();
        List<String> addedUrls = new ArrayList<>();

        for (Context result : searchResults) {
            String sourceURL = result.url();

            // Check if URL has already been added
            if (!addedUrls.contains(sourceURL)) {
                sourcesBuilder.append(String.format(SOURCE_STRING, sourceURL));
                addedUrls.add(sourceURL);
            }
        }

        return sourcesBuilder.toString();
    }
}
