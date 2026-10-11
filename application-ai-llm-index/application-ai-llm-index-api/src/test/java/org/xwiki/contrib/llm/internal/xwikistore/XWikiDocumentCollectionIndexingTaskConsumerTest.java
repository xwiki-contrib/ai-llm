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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.test.reference.ReferenceComponentList;
import com.xpn.xwiki.util.Util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentCollectionIndexingTaskConsumer}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class XWikiDocumentCollectionIndexingTaskConsumerTest
{
    private static final String MAIN_WIKI = "xwiki";

    private static final String SUBWIKI = "subwiki";

    private static final String COLLECTION_ID = "testcollection";

    private static final List<String> SPACE_NAMES = List.of("subwiki:Space");

    private static final SpaceReference SPACE = new SpaceReference(SUBWIKI, "Space");

    private static final DocumentReference INDEXED_PAGE =
        new DocumentReference(SUBWIKI, "Space", "Indexed", Locale.ROOT);

    private static final DocumentReference NEW_PAGE = new DocumentReference(SUBWIKI, "Space", "New", Locale.FRENCH);

    private static final List<String> DOCUMENT_IDS = List.of("subwiki:Space.Indexed;", "subwiki:Space.New;fr");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private XWikiDocumentCollectionIndexingTaskConsumer consumer;

    @MockComponent
    private CollectionManager collectionManager;

    @MockComponent
    private XWikiDocumentStoreHelper helper;

    @MockComponent
    private TaskManager taskManager;

    @MockComponent
    private SolrUtils solrUtils;

    @MockComponent
    private SolrConnector solrConnector;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @Mock
    private Collection collection;

    @BeforeEach
    void setUp()
    {
        when(this.solrUtils.toFilterQueryString(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void consumeOfAMainWikiCollectionChecksTheIndexedDocumentsInTheWikiOfTheCollection() throws Exception
    {
        DocumentReference collectionReference = mockCollection(MAIN_WIKI);
        when(this.solrConnector.filterExistingDocuments(MAIN_WIKI, COLLECTION_ID, DOCUMENT_IDS))
            .thenReturn(List.of(DOCUMENT_IDS.get(0)));

        this.consumer.consume(new DocumentReference(collectionReference, Locale.ROOT), "1.1");

        // The pages are in the subwiki but the chunks of the collection are stored in the main wiki, the wiki of the
        // collection: the pages already indexed are looked up there, and only the page that is not indexed is queued.
        verify(this.solrConnector).deleteChunksByQuery(eq(MAIN_WIKI), eq(COLLECTION_ID), anyString());
        verify(this.solrConnector).filterExistingDocuments(MAIN_WIKI, COLLECTION_ID, DOCUMENT_IDS);
        verify(this.taskManager).addTask(SUBWIKI, taskId(NEW_PAGE), XWikiDocumentDocumentIndexingTaskConsumer.NAME);
        verifyNoMoreInteractions(this.taskManager);
    }

    @Test
    void consumeOfASubwikiCollectionChecksTheIndexedDocumentsInItsWiki() throws Exception
    {
        DocumentReference collectionReference = mockCollection(SUBWIKI);
        when(this.solrConnector.filterExistingDocuments(SUBWIKI, COLLECTION_ID, DOCUMENT_IDS))
            .thenReturn(List.of());

        this.consumer.consume(new DocumentReference(collectionReference, Locale.ROOT), "1.1");

        verify(this.solrConnector).deleteChunksByQuery(eq(SUBWIKI), eq(COLLECTION_ID), anyString());
        verify(this.solrConnector).filterExistingDocuments(SUBWIKI, COLLECTION_ID, DOCUMENT_IDS);
        verify(this.taskManager)
            .addTask(SUBWIKI, taskId(INDEXED_PAGE), XWikiDocumentDocumentIndexingTaskConsumer.NAME);
        verify(this.taskManager).addTask(SUBWIKI, taskId(NEW_PAGE), XWikiDocumentDocumentIndexingTaskConsumer.NAME);
        verifyNoMoreInteractions(this.taskManager);
    }

    @Test
    void consumeIgnoresAMissingCollection() throws Exception
    {
        DocumentReference collectionReference = DefaultCollection.getDocumentReference(MAIN_WIKI, COLLECTION_ID);

        this.consumer.consume(collectionReference, "1.1");

        verifyNoInteractions(this.solrConnector, this.taskManager);
        assertEquals("Ignoring indexing request for [xwiki:AI.Collections.testcollection.WebHome] as no collection "
            + "was found for id [testcollection].", this.logCapture.getMessage(0));
    }

    private DocumentReference mockCollection(String wiki) throws Exception
    {
        DocumentReference collectionReference = DefaultCollection.getDocumentReference(wiki, COLLECTION_ID);
        when(this.collectionManager.getCollection(COLLECTION_ID)).thenReturn(this.collection);
        when(this.collection.getDocumentReference()).thenReturn(collectionReference);
        when(this.collection.getDocumentStoreHint()).thenReturn(XWikiDocumentStore.NAME);
        when(this.collection.getDocumentSpaces()).thenReturn(SPACE_NAMES);
        when(this.helper.resolveSpaceReferences(SPACE_NAMES, collectionReference)).thenReturn(List.of(SPACE));
        when(this.helper.getDocumentsFromWiki(new WikiReference(SUBWIKI), List.of(SPACE), 0, 100))
            .thenReturn(List.of(INDEXED_PAGE, NEW_PAGE));
        return collectionReference;
    }

    private long taskId(DocumentReference documentReference) throws Exception
    {
        EntityReferenceSerializer<String> serializer =
            this.componentManager.getInstance(EntityReferenceSerializer.TYPE_STRING, "local/uid");
        return Util.getHash(serializer.serialize(documentReference));
    }
}
