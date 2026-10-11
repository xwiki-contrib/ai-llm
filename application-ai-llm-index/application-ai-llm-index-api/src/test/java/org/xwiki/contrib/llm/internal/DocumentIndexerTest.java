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

import java.util.List;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.xwiki.contrib.llm.ChunkingUtils;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.EmbeddingsUtils;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DocumentIndexer}.
 *
 * @version $Id$
 */
@ComponentTest
class DocumentIndexerTest
{
    private static final String WIKI = "subwiki";

    private static final String PREVIOUS_WIKI = "xwiki";

    private static final String COLLECTION = "collection";

    private static final String DOCUMENT = "subwiki:Space.Page;";

    private static final String EMBEDDING_MODEL = "model";

    @InjectMockComponents
    private DocumentIndexer documentIndexer;

    @MockComponent
    private CollectionManager collectionManager;

    @MockComponent
    private SolrConnector solrConnector;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private EmbeddingsUtils embeddingsUtils;

    @MockComponent
    private ChunkingUtils chunkingUtils;

    @Mock
    private XWikiContext context;

    @Mock
    private Collection collection;

    @Mock
    private DocumentStore documentStore;

    @Mock
    private UserReference author;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.context.getWikiId()).thenReturn(PREVIOUS_WIKI);
        when(this.collectionManager.getCollection(COLLECTION)).thenReturn(this.collection);
        when(this.collection.getDocumentStore()).thenReturn(this.documentStore);
        when(this.collection.getEmbeddingModel()).thenReturn(EMBEDDING_MODEL);
        when(this.collection.getAuthor()).thenReturn(this.author);
        when(this.embeddingsUtils.getMaximumNumberOfTexts(EMBEDDING_MODEL, this.author)).thenReturn(5);
    }

    @Test
    void indexDocumentResolvesTheCollectionInTheGivenWikiAndRestoresTheContextWiki() throws Exception
    {
        Document document = mock();
        when(this.documentStore.getDocument(DOCUMENT)).thenReturn(document);
        when(this.chunkingUtils.chunkDocument(this.collection, document)).thenReturn(List.of());

        this.documentIndexer.indexDocument(WIKI, COLLECTION, DOCUMENT);

        // The collection page is resolved in the context wiki: the wiki of the collection is set before the
        // collection is looked up and the previous wiki is restored once the document is indexed.
        InOrder inOrder = inOrder(this.context, this.collectionManager, this.solrConnector);
        inOrder.verify(this.context).setWikiId(WIKI);
        inOrder.verify(this.collectionManager).getCollection(COLLECTION);
        inOrder.verify(this.solrConnector).deleteChunksByIndex(WIKI, COLLECTION, DOCUMENT, 0);
        inOrder.verify(this.context).setWikiId(PREVIOUS_WIKI);
    }

    @Test
    void indexDocumentRestoresTheContextWikiWhenTheCollectionLookupFails() throws Exception
    {
        IndexException cause = new IndexException("Collection lookup failed");
        when(this.collectionManager.getCollection(COLLECTION)).thenThrow(cause);

        IndexException exception = assertThrows(IndexException.class,
            () -> this.documentIndexer.indexDocument(WIKI, COLLECTION, DOCUMENT));

        assertSame(cause, exception);
        InOrder inOrder = inOrder(this.context, this.collectionManager);
        inOrder.verify(this.context).setWikiId(WIKI);
        inOrder.verify(this.collectionManager).getCollection(COLLECTION);
        inOrder.verify(this.context).setWikiId(PREVIOUS_WIKI);
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void indexDocumentFailsForAMissingDocumentAndRestoresTheContextWiki() throws Exception
    {
        when(this.documentStore.getDocument(DOCUMENT)).thenReturn(null);

        IndexException exception = assertThrows(IndexException.class,
            () -> this.documentIndexer.indexDocument(WIKI, COLLECTION, DOCUMENT));

        assertEquals("Document [subwiki:Space.Page;] does not exist in collection [collection] in wiki [subwiki]",
            exception.getMessage());
        verify(this.context).setWikiId(PREVIOUS_WIKI);
        verifyNoInteractions(this.solrConnector);
    }
}
