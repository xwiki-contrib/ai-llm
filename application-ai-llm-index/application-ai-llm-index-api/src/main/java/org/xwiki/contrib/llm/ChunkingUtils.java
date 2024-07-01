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
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

import com.xpn.xwiki.XWikiContext;

/**
 * Utility class used in chunking the documents.
 * 
 * @version $Id$
 */
@Component(roles = ChunkingUtils.class)
@Singleton
public class ChunkingUtils
{
    private static final String HEADING_REGEX = "^( *=+[^=\n]|#+|(?:[^\n]*\n(?:=+|-+)$))";

    private static final Pattern HEADING_AT_END_PATTERN = Pattern.compile(".*" + HEADING_REGEX,
        Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern HEADING_PATTERN = Pattern.compile(HEADING_REGEX, Pattern.MULTILINE);

    private static final List<String> SEPARATORS = List.of("\n\n", "\n", ". ", " ");

    @Inject
    private Provider<Chunk> chunkProvider;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> contextProvider;
    
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
        throws IndexException
    {
        int maxChunkSize = collection.getChunkingMaxSize();
        int offset = collection.getChunkingOverlapOffset();

        validateChunkSizeAndOffset(maxChunkSize, offset);

        // get the document content
        String content = document.getContent();

        // Initialize the chunks map
        Map<Integer, Chunk> chunks = new HashMap<>();
    
        int start = 0;
        int end;
        int chunkIndex = 0;

        XWikiContext context = this.contextProvider.get();
    
        while (start < content.length()) {
            // Find the next index to end the chunk
            end = Math.min(start + maxChunkSize, content.length());
    
            // Extract the chunk content
            String chunkContent = content.substring(start, end);

            // Truncate the content to a semantic boundary if we're not at the end of the document, yet.
            if (end < content.length()) {
                OptionalInt truncateIndex = findGoodBoundaryAtEnd(chunkContent, maxChunkSize / 2);
                if (truncateIndex.isPresent()) {
                    end = start + truncateIndex.getAsInt();
                    chunkContent = content.substring(start, end);
                }
            }

            Chunk chunk = chunkProvider.get();
            chunk.initialize(document.getID(),
                            document.getCollection(),
                            document.getURL(),
                            document.getLanguage(),
                            start, end, chunkContent);
            chunk.setChunkIndex(chunkIndex);
            chunk.setWiki(context.getWikiId());
            chunks.put(chunkIndex, chunk);
    
            // Prepare for the next iteration
            if (end < content.length() && offset > 0) {
                OptionalInt goodOverlap =
                    findGoodBoundaryAtStart(chunkContent.substring(chunkContent.length() - offset), offset / 2);
                start = end - offset + goodOverlap.orElse(0);
            } else {
                start = end;
            }
            chunkIndex++;
        }
    
        return chunks;
    }

    private static void validateChunkSizeAndOffset(int maxChunkSize, int offset) throws IndexException
    {
        // Validate parameters:
        // - maximum chunk size needs to be at least 10 as we need to have some content in the chunk
        // - overlap offset must not be negative and must be smaller than half of the maximum chunk size
        if (maxChunkSize < 10) {
            throw new IndexException("The maximum chunk size must be at least 10 characters");
        }

        if (offset < 0) {
            throw new IndexException("The overlap offset must not be negative");
        }

        if (offset >= maxChunkSize / 2) {
            throw new IndexException("The overlap offset must be smaller than half of the maximum chunk size");
        }
    }

    private OptionalInt findGoodBoundaryAtEnd(String chunk, int minimumIndex)
    {
        // Try finding a nicer chunk boundary. Try the following options in order:
        // 1. The start of a section in XWiki syntax or Markdown.
        // 2. A blank line.
        // 3. A linebreak.
        // 4. The end of a sentence (". ").
        // 5. Any space.
        // Truncate to the first option that still gives a chunk that has at least half of the maximum size length.

        Matcher matcher = HEADING_AT_END_PATTERN.matcher(chunk);
        int headingIndex = matcher.find() ? matcher.start(1) : -1;
        if (headingIndex > minimumIndex) {
            return OptionalInt.of(headingIndex);
        } else {
            for (String separator : SEPARATORS) {
                int separatorIndex = chunk.lastIndexOf(separator) + separator.length();
                if (separatorIndex > minimumIndex) {
                    return OptionalInt.of(separatorIndex);
                }
            }
        }

        return OptionalInt.empty();
    }

    private OptionalInt findGoodBoundaryAtStart(String chunk, int maximumIndex)
    {
        Matcher matcher = HEADING_PATTERN.matcher(chunk);
        int headingIndex = matcher.find() ? matcher.start() : Integer.MAX_VALUE;
        if (headingIndex < maximumIndex) {
            return OptionalInt.of(headingIndex);
        } else {
            for (String separator : SEPARATORS) {
                int separatorIndex = chunk.indexOf(separator);
                if (separatorIndex > -1 && separatorIndex < maximumIndex) {
                    return OptionalInt.of(separatorIndex + separator.length());
                }
            }
        }

        return OptionalInt.empty();
    }

}
