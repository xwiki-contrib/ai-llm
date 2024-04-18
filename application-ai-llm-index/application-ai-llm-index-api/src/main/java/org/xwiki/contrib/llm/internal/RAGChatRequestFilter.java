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
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.function.FailableConsumer;
import org.slf4j.Logger;
import org.xwiki.contrib.llm.AbstractChatRequestFilter;
import org.xwiki.contrib.llm.ChatMessage;
import org.xwiki.contrib.llm.ChatRequest;
import org.xwiki.contrib.llm.ChatResponse;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.RequestError;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A filter that adds context from the given collections to the request.
 *
 * @version $Id$
 * @since 0.3
 */
public class RAGChatRequestFilter extends AbstractChatRequestFilter
{
    private static final String SEARCH_RESULTS_STRING = "SYSTEM MESSAGE: Search results: %n";
    private static final String SOURCE_STRING = "%s %n";
    private static final String CONTENT_CHUNK_STRING = "Content chunk: %n %s %n";
    private static final String SIMILARITY_SEARCH_ERROR_MSG = "There was an error during similarity search";
    private static final String USER_MESSAGE_STRING = "\n\n User message: ";
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
    public void processStreaming(ChatRequest request, FailableConsumer<ChatResponse, IOException> consumer)
            throws IOException, RequestError
    {
        // First, modify the request with additional context as needed
        ChatRequest modifiedRequest = addContext(request);
    
        // Get the sources from the search results cache or perform the search
        String sources = extractURLsAndformat(getSearchResults(request));
    
        // Create and send a custom ChatResponse with the sources
        ChatResponse sourcesResponse = new ChatResponse(null, new ChatMessage("assistant", sources));
        consumer.accept(sourcesResponse);
        
        // Now, use the super implementation with the modified request
        super.processStreaming(modifiedRequest, consumer);
    }
    

    @Override
    public ChatResponse process(ChatRequest request) throws IOException, RequestError
    {
        JSONArray searchResults = formatSearchResults(getSearchResults(request));
        ChatRequest modifiedRequest = addContext(request);
        ChatResponse response = super.process(modifiedRequest);
        return new ChatResponse(response.getFinishReason(), response.getMessage(), searchResults);
    }

    private ChatRequest addContext(ChatRequest request)
    {
        String context = augmentRequest(request);
        if (!request.getMessages().isEmpty()) {
            ChatMessage lastMessage = request.getMessages().get(request.getMessages().size() - 1);
            lastMessage.setContent(context + USER_MESSAGE_STRING + lastMessage.getContent());
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
        contextBuilder.append(contextPrompt);

        return contextBuilder.toString();
    }

    private List<List<String>> getSearchResults(ChatRequest request)
    {
        if (request.getMessages().isEmpty()) {
            return Collections.emptyList();
        }
    
        ChatMessage lastMessage = request.getMessages().get(request.getMessages().size() - 1);
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
    
    private JSONArray formatSearchResults(List<List<String>> searchResults)
    {
        JSONArray formattedResults = new JSONArray();
    
        for (List<String> result : searchResults) {
            if (result.size() == 4) {
                String docId = result.get(0);
                String url = result.get(1);
                String content = result.get(2);
                String similarityScore = result.get(3);
    
                JSONObject jsonResult = new JSONObject();
                jsonResult.put("docId", docId);
                jsonResult.put("url", url);
                jsonResult.put("content", content);
                jsonResult.put("similarityScore", similarityScore);
    
                formattedResults.put(jsonResult);
            }
        }
    
        return formattedResults;
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
