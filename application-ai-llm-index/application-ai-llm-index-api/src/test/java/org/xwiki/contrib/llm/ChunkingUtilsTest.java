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

import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link ChunkingUtils}.
 *
 * @version $Id$
 */
@ComponentTest
class ChunkingUtilsTest
{
    private static final String TEST_CONTENT = """
        = Heading 1 =
        
        This is a paragraph.
        
        == Heading 2 ==
        
        Another paragraph.
        
        ## Markdown heading
        
        Markdown paragraph.
        Second line of the paragraph.
        Third line of the paragraph.
        
        Heading
        =======
        
        A sentence in this paragraph. And a very very very long sentence that just doesn't end and needs to be split.
        """;

    @InjectMockComponents
    private ChunkingUtils chunkingUtils;

    @MockComponent
    private CollectionManager collectionManager;

    @Mock
    private Collection mockCollection;

    @Mock
    private Document mockDocument;

    @MockComponent
    private Provider<Chunk> chunkProvider;

    @BeforeEach
    void setUp() throws Exception
    {
        String collectionName = "collectionName";
        // Setup the mock objects
        when(this.mockDocument.getCollection()).thenReturn(collectionName);
        when(this.mockDocument.getContent()).thenReturn(TEST_CONTENT);
        when(this.collectionManager.getCollection(collectionName)).thenReturn(this.mockCollection);
        when(this.chunkProvider.get()).thenAnswer(invocation -> new Chunk());
    }

    @Test
    void testChunkingNoOverlap()
    {
        // Test the chunking of the document
        when(this.mockCollection.getChunkingMaxSize()).thenReturn(60);
        when(this.mockCollection.getChunkingOverlapOffset()).thenReturn(0);
        Map<Integer, Chunk> actualChunks = this.chunkingUtils.chunkDocument(this.mockDocument);

        List<String> expectedChunks = List.of("""
            = Heading 1 =
            
            This is a paragraph.
            
            """, """
            == Heading 2 ==
            
            Another paragraph.
            
            """, """
            ## Markdown heading
            
            Markdown paragraph.
            """, """
            Second line of the paragraph.
            Third line of the paragraph.
            
            """, """
            Heading
            =======
           
            A sentence in this paragraph.\s""",
            "And a very very very long sentence that just doesn't end ", "and needs to be split.\n");

        assertEquals(expectedChunks.size(), actualChunks.size());
        for (Map.Entry<Integer, Chunk> entry : actualChunks.entrySet()) {
            assertEquals(expectedChunks.get(entry.getKey()), entry.getValue().getContent());
        }
    }

    @Test
    void testChunkingOverlap()
    {
        // Test the chunking of the document
        when(this.mockCollection.getChunkingMaxSize()).thenReturn(80);
        when(this.mockCollection.getChunkingOverlapOffset()).thenReturn(30);
        Map<Integer, Chunk> actualChunks = this.chunkingUtils.chunkDocument(this.mockDocument);

        List<String> expectedChunks = List.of("""
            = Heading 1 =
            
            This is a paragraph.
            
            == Heading 2 ==
            
            Another paragraph.
            
            """, """
            Another paragraph.
            
            ## Markdown heading
            
            """, """
            ## Markdown heading
            
            Markdown paragraph.
            Second line of the paragraph.
            """, """
            line of the paragraph.
            Third line of the paragraph.
            
            """, """
            line of the paragraph.
            
            Heading
            =======
            
            """,
            """
            Heading
            =======
            
            A sentence in this paragraph.\s""",
            "sentence in this paragraph. And a very very very long sentence that just ",
            "very long sentence that just doesn't end and needs to be split.\n");

        assertEquals(expectedChunks.size(), actualChunks.size());
        for (Map.Entry<Integer, Chunk> entry : actualChunks.entrySet()) {
            assertEquals(expectedChunks.get(entry.getKey()), entry.getValue().getContent());
        }
    }
}
