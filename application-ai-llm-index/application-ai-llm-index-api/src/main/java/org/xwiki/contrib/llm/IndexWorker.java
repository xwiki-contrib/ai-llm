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

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.CollectionIndexingTaskConsumer;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.contrib.llm.internal.InternalDocumentStore;
import org.xwiki.index.TaskManager;
import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * This class is responsible for listening to updates of collections and internal documents. It will add appropriate
 * indexing tasks to the queue. It reacts to a documents object only on the page of the internal store for the
 * document that the object names, see {@link InternalDocumentStore#getDocumentReference(String, String, String)};
 * objects on other pages are ignored.
 *
 * @version $Id$
 */
@Component
@Named(IndexWorker.NAME)
@Singleton
public class IndexWorker extends AbstractLocalEventListener
{
    /**
     * The name of the event listener.
     */
    public static final String NAME = "org.xwiki.contrib.llm.IndexWorker";

    @Inject
    private Logger logger;

    @Inject
    private TaskManager taskManager;

    @Inject
    private SolrConnector solrConnector;

    /**
     * Default constructor.
     */
    public IndexWorker()
    {
        super(NAME, new DocumentCreatedEvent(), new DocumentUpdatedEvent(), new DocumentDeletedEvent());
    }

    @Override
    public void processLocalEvent(Event event, Object source, Object data)
    {
        XWikiDocument xdocument = (XWikiDocument) source;
        XWikiDocument originalDocument = xdocument.getOriginalDocument();

        if (isDocumentPage(xdocument)) {
            addTaskForDocument(xdocument);
        } else if (originalDocument != null && isDocumentPage(originalDocument)) {
            // The document was an internal document before the change like a deletion - delete it from Solr. We do
            // this directly as TaskManager doesn't support tasks for deleted documents.
            BaseObject xObject = originalDocument.getXObject(Document.XCLASS_REFERENCE);
            String id = xObject.getStringValue(DefaultDocument.ID_KEY);
            String collection = xObject.getStringValue(DefaultDocument.PARENT_COLLECTION);
            String wiki = originalDocument.getDocumentReference().getWikiReference().getName();
            // Ensure that if the current store is not the internal store, we don't accidentally delete the document
            // of a different store that happens to have the same id.
            this.solrConnector.deleteChunksByDocIdAndStore(wiki, collection, id, InternalDocumentStore.NAME);
        }

        updateCollectionIndex(xdocument, originalDocument);
    }

    /**
     * Check whether the given document is the page that stores a document of a collection in the internal store: it
     * carries a documents object and its reference is the one the internal store derives from the object's collection
     * and document ids. A documents object on any other page is ignored so that editing an unrelated page can neither
     * queue the indexing of that page nor delete the chunks of the document the object names.
     *
     * @param document the document to check
     * @return {@code true} when the document is the page of the internal store for the document its object names
     */
    private boolean isDocumentPage(XWikiDocument document)
    {
        BaseObject documentObject = document.getXObject(Document.XCLASS_REFERENCE);
        if (documentObject == null) {
            return false;
        }

        String id = documentObject.getStringValue(DefaultDocument.ID_KEY);
        String collection = documentObject.getStringValue(DefaultDocument.PARENT_COLLECTION);
        boolean canonical = StringUtils.isNotBlank(id) && StringUtils.isNotBlank(collection)
            && InternalDocumentStore.getDocumentReference(document.getDocumentReference().getWikiReference().getName(),
                collection, id).equals(document.getDocumentReference().withoutLocale());
        if (!canonical) {
            this.logger.debug("Ignoring the documents object on [{}] as the page is not the document [{}] of the "
                + "collection [{}] in the internal store", document.getDocumentReference(), id, collection);
        }

        return canonical;
    }

    /**
     * Check whether the given document is the page of the collection its collections object names, see
     * {@link DefaultCollection#getDocumentReference(String, String)}. A collections object on any other page, for
     * instance on a copy of a collection page, is ignored so that saving or deleting that page can neither clear the
     * chunks of the collection nor queue its re-indexing.
     *
     * @param document the document to check
     * @return {@code true} when the document is the page of the collection its object names
     */
    private boolean isCollectionPage(XWikiDocument document)
    {
        BaseObject collectionObject = document.getXObject(Collection.XCLASS_REFERENCE);
        if (collectionObject == null) {
            return false;
        }

        String id = collectionObject.getStringValue(DefaultCollection.ID_FIELDNAME);
        boolean canonical = StringUtils.isNotBlank(id) && DefaultCollection.getDocumentReference(
            document.getDocumentReference().getWikiReference().getName(), id)
            .equals(document.getDocumentReference().withoutLocale());
        if (!canonical) {
            this.logger.debug("Ignoring the collections object on [{}] as the page is not the page of the "
                + "collection [{}]", document.getDocumentReference(), id);
        }

        return canonical;
    }

    private void updateCollectionIndex(XWikiDocument document, XWikiDocument originalDocument)
    {
        BaseObject collectionObject = document.getXObject(Collection.XCLASS_REFERENCE);
        BaseObject collectionObjectOriginal =
            originalDocument == null ? null : originalDocument.getXObject(Collection.XCLASS_REFERENCE);
        String wiki = document.getDocumentReference().getWikiReference().getName();

        if (collectionObject != null && collectionObjectOriginal != null) {
            updateChangedCollectionIndex(document, originalDocument, collectionObject, collectionObjectOriginal);
        } else if (collectionObject != null && isCollectionPage(document)) {
            // New collection - queue it for indexing.
            this.taskManager.addTask(wiki, document.getId(), CollectionIndexingTaskConsumer.NAME);
        } else if (collectionObjectOriginal != null && isCollectionPage(originalDocument)) {
            // Collection was deleted - delete it from Solr.
            String collectionId = collectionObjectOriginal.getStringValue(DefaultCollection.ID_FIELDNAME);
            this.solrConnector.deleteChunksByCollection(wiki, collectionId);
        }
    }

    /**
     * Handle a page that carried a collections object both before and after the change. When the store or the id
     * changed, the chunks of the old id are dropped only if the page was the page of that old id, and the page is
     * queued for re-indexing only if it is the page of the new id. The two gates are independent: a renamed
     * collection page whose object is realigned to the new space name by the migrator is queued without dropping
     * anything (the deletion event of the old page already dropped the old chunks), while a copy of a collection
     * page whose id is edited never touches the chunks of the collection it was copied from.
     */
    private void updateChangedCollectionIndex(XWikiDocument document, XWikiDocument originalDocument,
        BaseObject collectionObject, BaseObject collectionObjectOriginal)
    {
        String collectionId = collectionObject.getStringValue(DefaultCollection.ID_FIELDNAME);
        String oldCollectionId = collectionObjectOriginal.getStringValue(DefaultCollection.ID_FIELDNAME);
        String oldStoreHint = collectionObjectOriginal.getStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME);
        String newStoreHint = collectionObject.getStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME);

        boolean changed =
            !Objects.equals(oldStoreHint, newStoreHint) || !Objects.equals(oldCollectionId, collectionId);
        if (!changed) {
            return;
        }

        String wiki = document.getDocumentReference().getWikiReference().getName();
        if (isCollectionPage(originalDocument)) {
            this.solrConnector.deleteChunksByCollection(wiki, oldCollectionId);
        }
        if (isCollectionPage(document)) {
            this.taskManager.addTask(wiki, document.getId(), CollectionIndexingTaskConsumer.NAME);
        }
    }

    private void addTaskForDocument(XWikiDocument document)
    {
        try {
            String wikiId = document.getDocumentReference().getWikiReference().getName();
            long documentId = document.getId();

            this.taskManager.addTask(wikiId, documentId, IndexTaskConsumer.NAME);
        } catch (Exception e) {
            this.logger.error("Failure to process document in indexWorker", e);
        }
    }
}
