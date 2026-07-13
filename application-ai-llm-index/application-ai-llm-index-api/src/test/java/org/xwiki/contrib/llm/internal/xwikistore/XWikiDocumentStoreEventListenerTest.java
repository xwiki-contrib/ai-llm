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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.reference.ReferenceComponentList;
import com.xpn.xwiki.web.Utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link XWikiDocumentStoreEventListener}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class XWikiDocumentStoreEventListenerTest
{
    private static final DocumentReference DOCUMENT_REFERENCE = new DocumentReference("wiki", "Space", "Page");

    @InjectMockComponents
    private XWikiDocumentStoreEventListener listener;

    @MockComponent
    private SolrConnector solrConnector;

    @MockComponent
    private TaskManager taskManager;

    @BeforeEach
    void setUp(MockitoComponentManager componentManager)
    {
        Utils.setComponentManager(componentManager);
    }

    @AfterEach
    void tearDown()
    {
        // Reset the static component manager so it cannot leak into later test classes in the same fork,
        // matching what MockitoOldcore does after each test.
        Utils.setComponentManager(null);
    }

    @Test
    void deleteTranslationDeletesChunksWithLocaleSuffix()
    {
        XWikiDocument blankSource = buildDeleteEventSource(Locale.FRENCH);

        this.listener.processLocalEvent(new DocumentDeletedEvent(blankSource.getDocumentReference()), blankSource,
            null);

        verify(this.solrConnector).deleteChunksByStoreHintAndDocId(XWikiDocumentStore.NAME, "wiki:Space.Page;fr");
        verify(this.taskManager, never()).addTask(any(), anyLong(), any());
    }

    @Test
    void deleteDefaultDocumentDeletesChunksWithEmptyLocaleSuffix()
    {
        XWikiDocument blankSource = buildDeleteEventSource(null);

        this.listener.processLocalEvent(new DocumentDeletedEvent(blankSource.getDocumentReference()), blankSource,
            null);

        verify(this.solrConnector).deleteChunksByStoreHintAndDocId(XWikiDocumentStore.NAME, "wiki:Space.Page;");
        verify(this.taskManager, never()).addTask(any(), anyLong(), any());
    }

    @Test
    void updateQueuesIndexingTaskAndDeletesNothing()
    {
        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.setOriginalDocument(new XWikiDocument(DOCUMENT_REFERENCE));

        this.listener.processLocalEvent(new DocumentUpdatedEvent(document.getDocumentReference()), document, null);

        verify(this.taskManager).addTask("wiki", document.getId(),
            XWikiDocumentDocumentIndexingTaskConsumer.NAME);
        verify(this.solrConnector, never()).deleteChunksByStoreHintAndDocId(any(), any());
    }

    @Test
    void collectionSpaceListChangeQueuesCollectionIndexingTask()
    {
        XWikiDocument document = buildCollectionDocument(List.of("SpaceA", "SpaceB"), List.of("SpaceA"));

        this.listener.processLocalEvent(new DocumentUpdatedEvent(DOCUMENT_REFERENCE), document, null);

        verify(this.taskManager).addTask("wiki", document.getId(),
            XWikiDocumentCollectionIndexingTaskConsumer.NAME);
        verify(this.taskManager).addTask("wiki", document.getId(),
            XWikiDocumentDocumentIndexingTaskConsumer.NAME);
    }

    @Test
    void collectionSpaceListReorderQueuesNoCollectionIndexingTask()
    {
        XWikiDocument document = buildCollectionDocument(List.of("SpaceB", "SpaceA"), List.of("SpaceA", "SpaceB"));

        this.listener.processLocalEvent(new DocumentUpdatedEvent(DOCUMENT_REFERENCE), document, null);

        // The space lists are compared as sets: a pure reordering is not a change.
        verify(this.taskManager, never()).addTask("wiki", document.getId(),
            XWikiDocumentCollectionIndexingTaskConsumer.NAME);
        verify(this.taskManager).addTask("wiki", document.getId(),
            XWikiDocumentDocumentIndexingTaskConsumer.NAME);
    }

    /**
     * Builds an updated collection document holding an XWiki-store collection XObject, with the given current
     * space list, whose original document holds the given previous space list.
     *
     * @param currentSpaces the space list on the updated document
     * @param previousSpaces the space list on the original document
     * @return the updated document, original attached
     */
    private XWikiDocument buildCollectionDocument(List<String> currentSpaces, List<String> previousSpaces)
    {
        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.addXObject(buildCollectionObject(currentSpaces));
        XWikiDocument originalDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        originalDocument.addXObject(buildCollectionObject(previousSpaces));
        document.setOriginalDocument(originalDocument);
        return document;
    }

    private BaseObject buildCollectionObject(List<String> spaces)
    {
        BaseObject object = new BaseObject();
        object.setXClassReference(Collection.XCLASS_REFERENCE);
        object.setStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME, XWikiDocumentStore.NAME);
        object.setStringListValue(DefaultCollection.DOCUMENT_SPACE_FIELDNAME, spaces);
        return object;
    }

    /**
     * Builds the delete event source exactly as the platform does: the source is a blank document built on the
     * locale-free reference, and the actually deleted document (carrying the locale) is attached as the original
     * document.
     *
     * @param locale the locale of the deleted document, or {@code null} for the default document
     * @return the blank event source document
     */
    private XWikiDocument buildDeleteEventSource(Locale locale)
    {
        XWikiDocument realDoc = new XWikiDocument(DOCUMENT_REFERENCE);
        if (locale != null) {
            realDoc.setLocale(locale);
        }
        XWikiDocument blankSource = new XWikiDocument(DOCUMENT_REFERENCE);
        blankSource.setOriginalDocument(realDoc);
        return blankSource;
    }
}
