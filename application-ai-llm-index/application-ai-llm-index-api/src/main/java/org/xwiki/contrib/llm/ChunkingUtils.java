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
package org.xwiki.contrib.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.model.reference.SpaceReferenceResolver;

/**
 * Utility class used in chunking the documents.
 * 
 * @version $Id$
 */
@Component(roles = ChunkingUtils.class)
@Singleton
public class ChunkingUtils
{

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;

    @Inject
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    @Inject 
    private Provider<Chunk> chunkProvider;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    private Logger logger;
    
    /**
     * This method is responsible for splitting the document into chunks.
     * 
     * @param document
     * @return a Map of chunks
     */
    public Map<Integer, Chunk> chunkDocument(Document document) 
    {
        Map<Integer, Chunk> chunkMap;
        Collection collection;
        try {
            collection = collectionManager.getCollection(document.getCollection());
            if (collection.getChunkingMethod().equals("llmFormattedChunking")) {
                chunkMap = aiFormattedChunking(document, collection);
            } else {
                chunkMap = chunkDocumentBasedOnCharacters(document, collection, false);
            }
            return chunkMap;
        } catch (IndexException e) {
            logger.error("Error while chunking the document [{}]: [{}]", document, e.getMessage());
        }
        return new HashMap<>();
    }

    private Map<Integer, Chunk> aiFormattedChunking(Document document, Collection collection)
    {
        return chunkDocumentBasedOnCharacters(document, collection, true);
    }

    private Map<Integer, Chunk> chunkDocumentBasedOnCharacters(Document document,
                                                               Collection collection,
                                                               boolean aiFormatted)
    {
        int maxChunkSize = collection.getChunkingMaxSize();
        int offset = collection.getChunkingOverlapOffset();

        // get the document content
        String content = document.getContent();

        // Initialize the chunks map
        Map<Integer, Chunk> chunks = new HashMap<>();
    
        int start = 0;
        int end;
        int chunkIndex = 0;
    
        while (start + offset < content.length()) {
            // Find the next index to end the chunk
            end = Math.min(start + maxChunkSize, content.length());
    
            // Extract the chunk content
            String baseChunkContent = content.substring(start, end);

            // Format the chunk content using llm
            String chunkContent = baseChunkContent;
            if (aiFormatted) {
                chunkContent = formatChunkContent(baseChunkContent, collection);
                logger.info("Formatted chunk content: [{}]", chunkContent);
            }
            Chunk chunk = chunkProvider.get();
            chunk.initialize(document.getID(),
                            document.getCollection(),
                            document.getURL(),
                            document.getLanguage(),
                            start, end, chunkContent);
            chunk.setChunkIndex(chunkIndex);
            chunks.put(chunkIndex, chunk);
    
            // Prepare for the next iteration
            start = end - offset;
            chunkIndex++;
        }
    
        return chunks;
    }
    
    private String formatChunkContent(String chunkContent, Collection collection)
    {
        String chunkingModelId = collection.getChunkingLLMModel();
        try {
            ChatModel model = this.componentManagerProvider.get().getInstance(ChatModel.class, chunkingModelId);
            if (model == null)
            {
                return chunkContent;
            }
            // create formatted request
            List<ChatMessage> messages = new ArrayList<>();
            String formatInstructions = String.format("Format the following chunk of text "
                                                     + "to make it more compact "
                                                     + "without loosing information, and at the end, "
                                                     + "add some questions the text provides answers to: %s",
                                                      chunkContent);
            ChatMessage chatMessage = new ChatMessage("user", formatInstructions);
            messages.add(chatMessage);

            ChatRequestParameters requestParameters = new ChatRequestParameters(1);
            ChatRequest request = new ChatRequest(messages, requestParameters);
            ChatResponse response = model.process(request);
            return response.getMessage().getContent();
        } catch (RequestError | IOException | ComponentLookupException e) {
            // Handle the exception here
            logger.error("Error while getting the model falling back on maxChar chunking method: [{}]", e.getMessage());
            return chunkContent;
        }
    }
}
