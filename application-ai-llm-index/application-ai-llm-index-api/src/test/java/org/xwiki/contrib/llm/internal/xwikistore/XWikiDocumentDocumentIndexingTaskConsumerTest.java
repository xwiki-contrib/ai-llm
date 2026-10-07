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
package org.xwiki.contrib.llm.internal.xwikistore;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.DocumentIndexer;
import org.xwiki.index.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentDocumentIndexingTaskConsumer}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class XWikiDocumentDocumentIndexingTaskConsumerTest
{
    private static final DocumentReference DOCUMENT_REFERENCE =
        new DocumentReference("subwiki", "Space", "Page", Locale.FRENCH);

    private static final String DOCUMENT_ID = "subwiki:Space.Page;fr";

    @InjectMockComponents
    private XWikiDocumentDocumentIndexingTaskConsumer consumer;

    @MockComponent
    private XWikiDocumentStoreHelper helper;

    @MockComponent
    private DocumentIndexer documentIndexer;

    @MockComponent
    private SolrConnector solrConnector;

    @MockComponent
    private XWikiContext context;

    @Mock
    private XWiki xWiki;

    @Mock
    private XWikiDocument document;

    @BeforeEach
    void setUp() throws XWikiException
    {
        when(this.context.getWiki()).thenReturn(this.xWiki);
        when(this.context.getMainXWiki()).thenReturn("xwiki");
        when(this.xWiki.getDocument(DOCUMENT_REFERENCE, this.context)).thenReturn(this.document);
    }

    @Test
    void hiddenDocumentOfACollectionIsRemovedFromTheIndexAndNotIndexed() throws Exception
    {
        when(this.document.isHidden()).thenReturn(true);
        when(this.helper.getCollections(DOCUMENT_REFERENCE, new WikiReference("xwiki")))
            .thenReturn(List.of("global"));

        this.consumer.consume(DOCUMENT_REFERENCE, "1.1");

        verify(this.solrConnector).deleteChunksByStoreHintAndDocId(XWikiDocumentStore.NAME, DOCUMENT_ID);
        verifyNoInteractions(this.documentIndexer);
    }

    @Test
    void hiddenDocumentOfNoCollectionIsNeitherLoadedNorRemovedFromTheIndex() throws Exception
    {
        when(this.document.isHidden()).thenReturn(true);

        this.consumer.consume(DOCUMENT_REFERENCE, "1.1");

        verify(this.helper).getCollections(DOCUMENT_REFERENCE, new WikiReference("subwiki"));
        verify(this.helper).getCollections(DOCUMENT_REFERENCE, new WikiReference("xwiki"));
        verifyNoInteractions(this.xWiki);
        verifyNoInteractions(this.solrConnector);
        verifyNoInteractions(this.documentIndexer);
    }

    @Test
    void visibleDocumentIsIndexedInItsWikiAndTheMainWiki() throws Exception
    {
        when(this.document.isHidden()).thenReturn(false);
        when(this.helper.getCollections(DOCUMENT_REFERENCE, new WikiReference("subwiki")))
            .thenReturn(List.of("local"));
        when(this.helper.getCollections(DOCUMENT_REFERENCE, new WikiReference("xwiki")))
            .thenReturn(List.of("global"));

        this.consumer.consume(DOCUMENT_REFERENCE, "1.1");

        verify(this.documentIndexer).indexDocument("subwiki", "local", DOCUMENT_ID);
        verify(this.documentIndexer).indexDocument("xwiki", "global", DOCUMENT_ID);
        verify(this.solrConnector, never()).deleteChunksByStoreHintAndDocId(any(), any());
    }

    @Test
    void documentLoadFailureIsReportedAsIndexException() throws Exception
    {
        when(this.helper.getCollections(DOCUMENT_REFERENCE, new WikiReference("subwiki")))
            .thenReturn(List.of("local"));
        XWikiException cause = mock();
        when(this.xWiki.getDocument(DOCUMENT_REFERENCE, this.context)).thenThrow(cause);

        IndexException exception =
            assertThrows(IndexException.class, () -> this.consumer.consume(DOCUMENT_REFERENCE, "1.1"));

        assertSame(cause, exception.getCause());
        verifyNoInteractions(this.documentIndexer);
        verifyNoInteractions(this.solrConnector);
    }
}
