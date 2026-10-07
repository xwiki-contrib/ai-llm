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

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.contrib.llm.internal.CollectionIndexingTaskConsumer;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.contrib.llm.internal.InternalDocumentStore;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.reference.ReferenceComponentList;
import com.xpn.xwiki.web.Utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit test for {@link IndexWorker}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class IndexWorkerTest
{
    private static final String WIKI_NAME = "wiki";

    private static final String COLLECTION = "testCollection";

    private static final String DOCUMENT_ID = "testDocument";

    private static final DocumentReference DOCUMENT_REFERENCE =
        InternalDocumentStore.getDocumentReference(WIKI_NAME, COLLECTION, DOCUMENT_ID);

    private static final DocumentReference OTHER_DOCUMENT_REFERENCE =
        new DocumentReference(WIKI_NAME, "Space", "Page");

    private static final String OTHER_COLLECTION = "otherCollection";

    private static final DocumentReference COLLECTION_REFERENCE =
        DefaultCollection.getDocumentReference(WIKI_NAME, COLLECTION);

    private static final DocumentReference OTHER_COLLECTION_REFERENCE =
        DefaultCollection.getDocumentReference(WIKI_NAME, OTHER_COLLECTION);

    private static final String OTHER_WIKI = "otherwiki";

    private static final DocumentReference OTHER_WIKI_DOCUMENT_REFERENCE =
        DOCUMENT_REFERENCE.setWikiReference(new WikiReference(OTHER_WIKI));

    @InjectMockComponents
    private IndexWorker indexWorker;

    @MockComponent
    private TaskManager taskManager;

    @MockComponent
    private SolrConnector solrConnector;

    @BeforeEach
    void setUp(MockitoComponentManager componentManager)
    {
        Utils.setComponentManager(componentManager);
    }

    @Test
    void documentCreated()
    {
        XWikiDocument createdDocument = getDocumentWithObject(DOCUMENT_REFERENCE, COLLECTION, DOCUMENT_ID);
        XWikiDocument previousDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        createdDocument.setOriginalDocument(previousDocument);

        this.indexWorker.processLocalEvent(new DocumentCreatedEvent(DOCUMENT_REFERENCE), createdDocument,
            mock(XWikiContext.class));

        verify(this.taskManager).addTask(WIKI_NAME, createdDocument.getId(), IndexTaskConsumer.NAME);
    }

    @Test
    void documentUpdated()
    {
        XWikiDocument updatedDocument = getDocumentWithObject(DOCUMENT_REFERENCE, COLLECTION, DOCUMENT_ID);
        XWikiDocument previousDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        updatedDocument.setOriginalDocument(previousDocument);

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(DOCUMENT_REFERENCE), updatedDocument,
            mock(XWikiContext.class));

        verify(this.taskManager).addTask(WIKI_NAME, updatedDocument.getId(), IndexTaskConsumer.NAME);
    }

    @Test
    void documentDeleted()
    {
        XWikiDocument deletedDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        XWikiDocument previousDocument = getDocumentWithObject(DOCUMENT_REFERENCE, COLLECTION, DOCUMENT_ID);
        deletedDocument.setOriginalDocument(previousDocument);

        this.indexWorker.processLocalEvent(new DocumentDeletedEvent(DOCUMENT_REFERENCE), deletedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);

        verify(this.solrConnector)
            .deleteChunksByDocIdAndStore(WIKI_NAME, COLLECTION, DOCUMENT_ID, InternalDocumentStore.NAME);
    }

    @Test
    void objectOnOtherPageCreated()
    {
        assertObjectIgnoredOnSave(getDocumentWithObject(OTHER_DOCUMENT_REFERENCE, COLLECTION, DOCUMENT_ID));
    }

    @Test
    void objectOnOtherPageDeleted()
    {
        assertObjectIgnoredOnDelete(getDocumentWithObject(OTHER_DOCUMENT_REFERENCE, COLLECTION, DOCUMENT_ID));
    }

    @Test
    void objectWithOtherCollectionOnDocumentPageUpdated()
    {
        assertObjectIgnoredOnSave(getDocumentWithObject(DOCUMENT_REFERENCE, OTHER_COLLECTION, DOCUMENT_ID));
    }

    @Test
    void objectWithOtherCollectionOnDocumentPageDeleted()
    {
        assertObjectIgnoredOnDelete(getDocumentWithObject(DOCUMENT_REFERENCE, OTHER_COLLECTION, DOCUMENT_ID));
    }

    @Test
    void objectOnDocumentPageOfOtherWikiCreated()
    {
        XWikiDocument createdDocument = getDocumentWithObject(OTHER_WIKI_DOCUMENT_REFERENCE, COLLECTION, DOCUMENT_ID);
        createdDocument.setOriginalDocument(new XWikiDocument(OTHER_WIKI_DOCUMENT_REFERENCE));

        this.indexWorker.processLocalEvent(new DocumentCreatedEvent(OTHER_WIKI_DOCUMENT_REFERENCE), createdDocument,
            mock(XWikiContext.class));

        // The page is the document page of the collection of its own wiki, the task is queued for that wiki.
        verify(this.taskManager).addTask(OTHER_WIKI, createdDocument.getId(), IndexTaskConsumer.NAME);
    }

    @Test
    void objectOnDocumentPageOfOtherWikiDeleted()
    {
        XWikiDocument deletedDocument = new XWikiDocument(OTHER_WIKI_DOCUMENT_REFERENCE);
        deletedDocument.setOriginalDocument(
            getDocumentWithObject(OTHER_WIKI_DOCUMENT_REFERENCE, COLLECTION, DOCUMENT_ID));

        this.indexWorker.processLocalEvent(new DocumentDeletedEvent(OTHER_WIKI_DOCUMENT_REFERENCE), deletedDocument,
            mock(XWikiContext.class));

        // The chunks of the collection of the page's own wiki are deleted.
        verify(this.solrConnector)
            .deleteChunksByDocIdAndStore(OTHER_WIKI, COLLECTION, DOCUMENT_ID, InternalDocumentStore.NAME);
    }

    @Test
    void objectWithBlankIdUpdated()
    {
        assertObjectIgnoredOnSave(getDocumentWithObject(DOCUMENT_REFERENCE, COLLECTION, " "));
    }

    @Test
    void objectWithBlankIdDeleted()
    {
        assertObjectIgnoredOnDelete(getDocumentWithObject(DOCUMENT_REFERENCE, COLLECTION, " "));
    }

    @Test
    void objectWithBlankCollectionDeleted()
    {
        assertObjectIgnoredOnDelete(getDocumentWithObject(DOCUMENT_REFERENCE, "", DOCUMENT_ID));
    }

    @Test
    void documentWithoutOriginalDeleted()
    {
        XWikiDocument deletedDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        deletedDocument.setOriginalDocument(null);

        this.indexWorker.processLocalEvent(new DocumentDeletedEvent(DOCUMENT_REFERENCE), deletedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void collectionCreated()
    {
        XWikiDocument createdDocument = getCollectionDocument(COLLECTION_REFERENCE, COLLECTION, null);
        createdDocument.setOriginalDocument(new XWikiDocument(COLLECTION_REFERENCE));

        this.indexWorker.processLocalEvent(new DocumentCreatedEvent(COLLECTION_REFERENCE), createdDocument,
            mock(XWikiContext.class));

        verify(this.taskManager).addTask(WIKI_NAME, createdDocument.getId(), CollectionIndexingTaskConsumer.NAME);
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void collectionCreatedWithoutOriginal()
    {
        XWikiDocument createdDocument = getCollectionDocument(COLLECTION_REFERENCE, COLLECTION, null);
        createdDocument.setOriginalDocument(null);

        this.indexWorker.processLocalEvent(new DocumentCreatedEvent(COLLECTION_REFERENCE), createdDocument,
            mock(XWikiContext.class));

        verify(this.taskManager).addTask(WIKI_NAME, createdDocument.getId(), CollectionIndexingTaskConsumer.NAME);
    }

    @Test
    void collectionStoreChanged()
    {
        XWikiDocument updatedDocument = getCollectionDocument(COLLECTION_REFERENCE, COLLECTION, "xwiki");
        updatedDocument.setOriginalDocument(getCollectionDocument(COLLECTION_REFERENCE, COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(COLLECTION_REFERENCE), updatedDocument,
            mock(XWikiContext.class));

        verify(this.solrConnector).deleteChunksByCollection(WIKI_NAME, COLLECTION);
        verify(this.taskManager).addTask(WIKI_NAME, updatedDocument.getId(), CollectionIndexingTaskConsumer.NAME);
    }

    @Test
    void collectionIdChangedToNonMatchingId()
    {
        // The page is the page of the old id but not of the new one: the chunks of the old id are dropped and
        // nothing is queued, as the page is no longer the page of any collection.
        XWikiDocument updatedDocument = getCollectionDocument(COLLECTION_REFERENCE, OTHER_COLLECTION, null);
        updatedDocument.setOriginalDocument(getCollectionDocument(COLLECTION_REFERENCE, COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(COLLECTION_REFERENCE), updatedDocument,
            mock(XWikiContext.class));

        verify(this.solrConnector).deleteChunksByCollection(WIKI_NAME, COLLECTION);
        verifyNoInteractions(this.taskManager);
    }

    @Test
    void collectionIdRealignedToThePage()
    {
        // The page of the new id carries an object with the old id that is edited to the new id. This is what the
        // migrator does after a rename (the deletion event of the old page already dropped the chunks of the old id)
        // and what a copy of a collection page looks like once its id is edited to match: the page is queued and the
        // chunks of the old id, which belong to the page of the old id, are untouched.
        XWikiDocument updatedDocument = getCollectionDocument(OTHER_COLLECTION_REFERENCE, OTHER_COLLECTION, null);
        updatedDocument.setOriginalDocument(getCollectionDocument(OTHER_COLLECTION_REFERENCE, COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(OTHER_COLLECTION_REFERENCE), updatedDocument,
            mock(XWikiContext.class));

        verify(this.taskManager).addTask(WIKI_NAME, updatedDocument.getId(), CollectionIndexingTaskConsumer.NAME);
        verify(this.solrConnector, never()).deleteChunksByCollection(any(), any());
    }

    @Test
    void collectionStoreChangedWithLocale()
    {
        // The reference of a document can carry a locale, the page is still the page of the collection.
        DocumentReference localizedReference = new DocumentReference(COLLECTION_REFERENCE, Locale.FRENCH);
        XWikiDocument updatedDocument = getCollectionDocument(localizedReference, COLLECTION, "xwiki");
        updatedDocument.setOriginalDocument(getCollectionDocument(localizedReference, COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(localizedReference), updatedDocument,
            mock(XWikiContext.class));

        verify(this.solrConnector).deleteChunksByCollection(WIKI_NAME, COLLECTION);
        verify(this.taskManager).addTask(WIKI_NAME, updatedDocument.getId(), CollectionIndexingTaskConsumer.NAME);
    }

    @Test
    void documentWithRootLocaleUpdated()
    {
        DocumentReference localizedReference = new DocumentReference(DOCUMENT_REFERENCE, Locale.ROOT);
        XWikiDocument updatedDocument = getDocumentWithObject(localizedReference, COLLECTION, DOCUMENT_ID);
        updatedDocument.setOriginalDocument(new XWikiDocument(localizedReference));

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(localizedReference), updatedDocument,
            mock(XWikiContext.class));

        verify(this.taskManager).addTask(WIKI_NAME, updatedDocument.getId(), IndexTaskConsumer.NAME);
    }

    @Test
    void collectionUnchanged()
    {
        XWikiDocument updatedDocument = getCollectionDocument(COLLECTION_REFERENCE, COLLECTION, null);
        updatedDocument.setOriginalDocument(getCollectionDocument(COLLECTION_REFERENCE, COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(COLLECTION_REFERENCE), updatedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void collectionDeleted()
    {
        XWikiDocument deletedDocument = new XWikiDocument(COLLECTION_REFERENCE);
        deletedDocument.setOriginalDocument(getCollectionDocument(COLLECTION_REFERENCE, COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentDeletedEvent(COLLECTION_REFERENCE), deletedDocument,
            mock(XWikiContext.class));

        verify(this.solrConnector).deleteChunksByCollection(WIKI_NAME, COLLECTION);
        verifyNoInteractions(this.taskManager);
    }

    @Test
    void copiedCollectionPageCreated()
    {
        XWikiDocument createdDocument = getCollectionDocument(OTHER_DOCUMENT_REFERENCE, COLLECTION, null);
        createdDocument.setOriginalDocument(new XWikiDocument(OTHER_DOCUMENT_REFERENCE));

        this.indexWorker.processLocalEvent(new DocumentCreatedEvent(OTHER_DOCUMENT_REFERENCE), createdDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void copiedCollectionPageStoreChanged()
    {
        XWikiDocument updatedDocument = getCollectionDocument(OTHER_DOCUMENT_REFERENCE, COLLECTION, "xwiki");
        updatedDocument.setOriginalDocument(getCollectionDocument(OTHER_DOCUMENT_REFERENCE, COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(OTHER_DOCUMENT_REFERENCE), updatedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void copiedCollectionPageDeleted()
    {
        XWikiDocument deletedDocument = new XWikiDocument(OTHER_DOCUMENT_REFERENCE);
        deletedDocument.setOriginalDocument(getCollectionDocument(OTHER_DOCUMENT_REFERENCE, COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentDeletedEvent(OTHER_DOCUMENT_REFERENCE), deletedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);
        verifyNoInteractions(this.solrConnector);
    }

    @Test
    void collectionPageWithOtherIdDeleted()
    {
        XWikiDocument deletedDocument = new XWikiDocument(COLLECTION_REFERENCE);
        deletedDocument.setOriginalDocument(getCollectionDocument(COLLECTION_REFERENCE, OTHER_COLLECTION, null));

        this.indexWorker.processLocalEvent(new DocumentDeletedEvent(COLLECTION_REFERENCE), deletedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);
        verifyNoInteractions(this.solrConnector);
    }

    private void assertObjectIgnoredOnSave(XWikiDocument savedDocument)
    {
        DocumentReference reference = savedDocument.getDocumentReference();
        savedDocument.setOriginalDocument(new XWikiDocument(reference));

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(reference), savedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);
        verifyNoInteractions(this.solrConnector);
    }

    private void assertObjectIgnoredOnDelete(XWikiDocument previousDocument)
    {
        DocumentReference reference = previousDocument.getDocumentReference();
        XWikiDocument deletedDocument = new XWikiDocument(reference);
        deletedDocument.setOriginalDocument(previousDocument);

        this.indexWorker.processLocalEvent(new DocumentDeletedEvent(reference), deletedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);
        verifyNoInteractions(this.solrConnector);
    }

    private static XWikiDocument getCollectionDocument(DocumentReference reference, String id, String storeHint)
    {
        XWikiDocument resultDocument = new XWikiDocument(reference);
        BaseObject object = new BaseObject();
        object.setXClassReference(Collection.XCLASS_REFERENCE);
        object.setStringValue(DefaultCollection.ID_FIELDNAME, id);
        if (storeHint != null) {
            object.setStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME, storeHint);
        }
        resultDocument.addXObject(object);
        return resultDocument;
    }

    private static XWikiDocument getDocumentWithObject(DocumentReference reference, String collection, String id)
    {
        XWikiDocument resultDocument = new XWikiDocument(reference);
        BaseObject object = new BaseObject();
        object.setXClassReference(Document.XCLASS_REFERENCE);
        object.setStringValue(DefaultDocument.ID_KEY, id);
        object.setStringValue(DefaultDocument.PARENT_COLLECTION, collection);
        resultDocument.addXObject(object);
        return resultDocument;
    }
}
