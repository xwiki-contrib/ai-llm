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

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
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
        Collection collection;
        try {
            collection = collectionManager.getCollection(document.getCollection());
            return chunkDocumentBasedOnCharacters(collection, document);
        } catch (IndexException e) {
            logger.error("Error while chunking the document [{}]: [{}]", document, e.getMessage());
        }
        return new HashMap<>();
    }

    private Map<Integer, Chunk> chunkDocumentBasedOnCharacters(Collection collection, Document document)
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
            String chunkContent = content.substring(start, end);

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

}
