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

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.model.reference.SpaceReferenceResolver;

/**
 * Utility class used in chunking the documents.
 * 
 * @version $Id$
 */
@Component(roles = Utils.class)
@Singleton
public class Utils
{

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;

    @Inject 
    private Provider<Chunk> chunkProvider;

    /**
     * This method is responsible for splitting the document into chunks.
     * 
     * @param document
     * @return a Map of chunks
     */
    public Map<Integer, Chunk> chunkDocument(DefaultDocument document)
    {      
        // get the document content
        String content = document.getContent();
    
        // Initialize the chunks map
        Map<Integer, Chunk> chunks = new HashMap<>();
    
        int start = 0;
        int end;
        int chunkIndex = 0;
        int maxChunkSize = 1000;
    
        while (start < content.length()) {
            // Find the next index to end the chunk
            end = findNextChunkEnd(content, start, maxChunkSize);
    
            // Extract the chunk content
            String chunkContent = content.substring(start, end);
            Chunk chunk = chunkProvider.get();
            chunk.initialize(document.getID(),
                                    document.getLanguage(),
                                    chunkIndex, start, end, chunkContent);
            chunks.put(chunkIndex, chunk);
    
            // Prepare for the next iteration
            start = end;
            chunkIndex++;
        }
    
        return chunks;
    }
    
    private int findNextChunkEnd(String content, int start, int maxChunkSize)
    {
        int end = Math.min(start + maxChunkSize, content.length());
        int lastSuitableEnd = findEmptyLineOrPunctuation(content, start, end);
        return lastSuitableEnd > start ? lastSuitableEnd : end;
    }
    
    private int findEmptyLineOrPunctuation(String content, int start, int end)
    {
        boolean emptyLineFound = false;
        int lastSuitableEnd = start;
        
        for (int i = start; i < end; i++) {
            if (!emptyLineFound) {
                lastSuitableEnd = checkForEmptyLine(content, i, lastSuitableEnd);
                emptyLineFound = lastSuitableEnd != i;
            }
            if (!emptyLineFound) {
                lastSuitableEnd = checkForPunctuation(content, i, lastSuitableEnd);
            }
        }
        return lastSuitableEnd;
    }
    
    private int checkForEmptyLine(String content, int index, int lastSuitableEnd)
    {
        if (index + 1 < content.length() && isLineBreak(content.charAt(index))
                && isLineBreakOrSpace(content.charAt(index + 1))) {
            int j = index + 2;
            while (j < content.length() && isLineBreakOrSpace(content.charAt(j))) {
                j++;
            }
            return j;
        }
        return lastSuitableEnd;
    }
    
    private int checkForPunctuation(String content, int index, int lastSuitableEnd)
    {
        if (content.charAt(index) == '.'
            || content.charAt(index) == '?'
            || content.charAt(index) == '!') {
            return index + 1;
        }
        return lastSuitableEnd;
    }
    
    private boolean isLineBreak(char ch)
    {
        return ch == '\n' || ch == '\r';
    }
    
    private boolean isLineBreakOrSpace(char ch)
    {
        return isLineBreak(ch) || ch == ' ';
    }
    
}
