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
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.contrib.llm.IndexTaskConsumer;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link CollectionIndexingTaskConsumer}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class CollectionIndexingTaskConsumerTest
{
    private static final String WIKI_NAME = "testwiki";

    private static final String COLLECTION_ID = "testcollection";

    private static final DocumentReference COLLECTION_REFERENCE =
        DefaultCollection.getDocumentReference(WIKI_NAME, COLLECTION_ID);

    private static final DocumentReference COPY_REFERENCE = new DocumentReference(WIKI_NAME, "Space", "Copy");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private CollectionIndexingTaskConsumer taskConsumer;

    @MockComponent
    private CollectionManager collectionManager;

    @MockComponent
    private TaskManager taskManager;

    @Test
    void consumeQueuesTheDocumentsOfTheCollection() throws Exception
    {
        saveCollectionDocument(COLLECTION_REFERENCE);
        Collection collection = mock(Collection.class);
        DocumentStore documentStore = mock(DocumentStore.class);
        when(this.collectionManager.getCollection(COLLECTION_ID)).thenReturn(collection);
        when(collection.getDocumentStore()).thenReturn(documentStore);
        when(documentStore.getTaskConsumerHint()).thenReturn(Optional.of(IndexTaskConsumer.NAME));
        DocumentReference documentReference =
            InternalDocumentStore.getDocumentReference(WIKI_NAME, COLLECTION_ID, "doc");
        when(documentStore.getDocumentReferences(0, 1000)).thenReturn(List.of(documentReference));

        // The task executor passes the reference of the document with its locale, the root locale for the page of
        // a collection as only the default document carries the collections object.
        this.taskConsumer.consume(new DocumentReference(COLLECTION_REFERENCE, Locale.ROOT), "1.1");

        verify(this.taskManager).addTask(eq(WIKI_NAME), anyLong(), eq(IndexTaskConsumer.NAME));
    }

    @Test
    void consumeIgnoresACopyOfTheCollectionPage() throws Exception
    {
        saveCollectionDocument(COPY_REFERENCE);

        this.taskConsumer.consume(COPY_REFERENCE, "1.1");

        verifyNoInteractions(this.collectionManager);
        verifyNoInteractions(this.taskManager);
        assertEquals("Document [testwiki:Space.Copy] that is queued for indexing as collection is not the page of "
            + "collection [testcollection]", this.logCapture.getMessage(0));
    }

    @Test
    void consumeIgnoresAMissingCollection() throws Exception
    {
        saveCollectionDocument(COLLECTION_REFERENCE);
        when(this.collectionManager.getCollection(COLLECTION_ID)).thenReturn(null);

        this.taskConsumer.consume(COLLECTION_REFERENCE, "1.1");

        verifyNoInteractions(this.taskManager);
        assertEquals("Collection [testcollection] of document [testwiki:AI.Collections.testcollection.WebHome] that "
            + "is queued for indexing does not exist", this.logCapture.getMessage(0));
    }

    @Test
    void consumeIgnoresAPageWithoutCollectionObject() throws Exception
    {
        this.taskConsumer.consume(COLLECTION_REFERENCE, "1.1");

        verifyNoInteractions(this.collectionManager);
        verifyNoInteractions(this.taskManager);
        assertEquals("Document [testwiki:AI.Collections.testcollection.WebHome] that is queued for indexing as "
            + "collection is not a collection", this.logCapture.getMessage(0));
    }

    private void saveCollectionDocument(DocumentReference reference) throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        XWikiDocument document = new XWikiDocument(reference);
        BaseObject object = new BaseObject();
        object.setXClassReference(Collection.XCLASS_REFERENCE);
        object.setStringValue(DefaultCollection.ID_FIELDNAME, COLLECTION_ID);
        document.addXObject(object);
        this.oldcore.getSpyXWiki().saveDocument(document, context);
    }
}
