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

import javax.inject.Named;

import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.index.TaskManager;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrCoreInitializer;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.XWikiSolrCore;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentStoreReindexer}.
 *
 * @version $Id$
 */
@ComponentTest
class XWikiDocumentStoreReindexerTest
{
    private static final String MAIN_WIKI = "xwiki";

    private static final String SUB_WIKI = "subwiki";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @InjectMockComponents
    private XWikiDocumentStoreReindexer reindexer;

    @MockComponent
    private ExecutionContextManager executionContextManager;

    @MockComponent
    private Execution execution;

    @MockComponent
    private XWikiContext context;

    @MockComponent
    private Solr solr;

    @MockComponent
    @Named(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE)
    private SolrCoreInitializer coreInitializer;

    @Mock
    private XWikiSolrCore core;

    @MockComponent
    private XWikiDocumentStoreReindexMarker marker;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private CollectionManager collectionManager;

    @MockComponent
    private TaskManager taskManager;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.context.getWikiId()).thenReturn("previous");
        when(this.wikiDescriptorManager.getAllIds()).thenReturn(List.of(MAIN_WIKI, SUB_WIKI));
        when(this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE)).thenReturn(this.core);
    }

    private Collection mockCollection(String id, String storeHint, long documentId) throws IndexException
    {
        Collection collection = mock();
        when(collection.getDocumentStoreHint()).thenReturn(storeHint);
        when(collection.getDocumentId()).thenReturn(documentId);
        when(this.collectionManager.getCollection(id)).thenReturn(collection);
        return collection;
    }

    @Test
    void markerSetRequeuesXWikiStoreCollectionsOfEveryWikiAndClearsTheMarker() throws Exception
    {
        when(this.marker.isSet()).thenReturn(true);
        when(this.collectionManager.getCollections()).thenReturn(List.of("pages", "uploads"), List.of("subpages"));
        mockCollection("pages", XWikiDocumentStore.NAME, 1L);
        mockCollection("uploads", "internal", 2L);
        mockCollection("subpages", XWikiDocumentStore.NAME, 3L);

        this.reindexer.requeueIfMarked();

        verify(this.coreInitializer).initialize(this.core);
        verify(this.taskManager).addTask(MAIN_WIKI, 1L, XWikiDocumentCollectionIndexingTaskConsumer.NAME);
        verify(this.taskManager).addTask(SUB_WIKI, 3L, XWikiDocumentCollectionIndexingTaskConsumer.NAME);
        verify(this.taskManager, never()).addTask(anyString(), eq(2L), anyString());
        verify(this.marker).clear();

        var order = inOrder(this.context);
        order.verify(this.context).setWikiId(MAIN_WIKI);
        order.verify(this.context).setWikiId("previous");
        order.verify(this.context).setWikiId(SUB_WIKI);
        order.verify(this.context).setWikiId("previous");

        var contextOrder = inOrder(this.executionContextManager, this.coreInitializer, this.marker, this.execution);
        contextOrder.verify(this.executionContextManager).initialize(any());
        contextOrder.verify(this.coreInitializer).initialize(this.core);
        contextOrder.verify(this.marker).isSet();
        contextOrder.verify(this.execution).removeContext();

        assertEquals("Re-queued [2] collections using the XWiki document store for indexing.",
            this.logCapture.getMessage(0));
    }

    @Test
    void markerNotSetRequeuesNothingButInitializesTheCore() throws Exception
    {
        when(this.marker.isSet()).thenReturn(false);

        this.reindexer.requeueIfMarked();

        verify(this.coreInitializer).initialize(this.core);
        verify(this.taskManager, never()).addTask(anyString(), anyLong(), anyString());
        verify(this.marker, never()).clear();
        verify(this.execution).removeContext();
    }

    @Test
    void coreInitializationFailureIsLoggedAndTheMarkerIsKept() throws Exception
    {
        SolrException failure = new SolrException("Solr is down");
        doThrow(failure).when(this.coreInitializer).initialize(this.core);

        this.reindexer.requeueIfMarked();

        verify(this.marker, never()).isSet();
        verify(this.marker, never()).clear();
        verify(this.taskManager, never()).addTask(anyString(), anyLong(), anyString());
        verify(this.execution).removeContext();

        assertEquals("Failed to re-queue the collections using the XWiki document store.",
            this.logCapture.getMessage(0));
    }

    @Test
    void failingWikiIsLoggedOthersAreProcessedAndTheMarkerIsCleared() throws Exception
    {
        when(this.marker.isSet()).thenReturn(true);
        IndexException failure = new IndexException("Main wiki is broken");
        when(this.collectionManager.getCollections())
            .thenThrow(failure)
            .thenReturn(List.of("subpages"));
        mockCollection("subpages", XWikiDocumentStore.NAME, 3L);

        this.reindexer.requeueIfMarked();

        verify(this.taskManager).addTask(SUB_WIKI, 3L, XWikiDocumentCollectionIndexingTaskConsumer.NAME);
        verify(this.marker).clear();
        verify(this.execution).removeContext();
        // The wiki is restored after the failure as well.
        verify(this.context, times(2)).setWikiId("previous");

        assertEquals("Failed to re-queue the collections of wiki [xwiki] (root cause: [IndexException: Main wiki is "
            + "broken]). Reindex the collections of that wiki that use the XWiki document store manually from the "
            + "collections administration.", this.logCapture.getMessage(0));
        assertSame(failure, ((ThrowableProxy) this.logCapture.getLogEvent(0).getThrowableProxy()).getThrowable());
        assertEquals("Re-queued [1] collections using the XWiki document store for indexing.",
            this.logCapture.getMessage(1));
    }
}
