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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.collections4.CollectionUtils;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Event listener for detecting collection updates related to the XWiki document store.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Named(XWikiDocumentStoreEventListener.NAME)
@Singleton
public class XWikiDocumentStoreEventListener extends AbstractLocalEventListener
{
    /**
     * The name of the event listener.
     */
    public static final String NAME = "org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStoreEventListener";

    @Inject
    private SolrConnector solrConnector;

    @Inject
    private TaskManager taskManager;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    /**
     * Default constructor.
     */
    public XWikiDocumentStoreEventListener()
    {
        super(NAME, new DocumentCreatedEvent(), new DocumentUpdatedEvent(), new DocumentDeletedEvent());
    }

    @Override
    public void processLocalEvent(Event event, Object source, Object data)
    {
        XWikiDocument document = (XWikiDocument) source;
        XWikiDocument originalDocument = document.getOriginalDocument();
        String wiki = document.getDocumentReference().getWikiReference().getName();

        BaseObject updatedObject = document.getXObject(Collection.XCLASS_REFERENCE);
        BaseObject originalObject = originalDocument.getXObject(Collection.XCLASS_REFERENCE);

        boolean isXWikiStore = isXWikiCollection(updatedObject);
        boolean wasXWikiStore = isXWikiCollection(originalObject);

        // Only handle the case of a collection update, creation and deletion are handled by the generic IndexWorker.
        if (wasXWikiStore && isXWikiStore) {
            List<?> previousSpaces = originalObject.getListValue(DefaultCollection.DOCUMENT_SPACE_FIELDNAME);
            List<?> currentSpaces = updatedObject.getListValue(DefaultCollection.DOCUMENT_SPACE_FIELDNAME);

            // Compare the two lists as sets to detect true changes that aren't just reordering.
            if (!CollectionUtils.isEqualCollection(previousSpaces, currentSpaces)) {
                this.taskManager.addTask(wiki, document.getId(), XWikiDocumentCollectionIndexingTaskConsumer.NAME);
            }
        }

        // If the document has been deleted, delete it from all collections where it was indexed, matching
        // collections by store.
        if (event instanceof DocumentDeletedEvent) {
            String documentId = this.entityReferenceSerializer.serialize(document.getDocumentReferenceWithLocale());
            this.solrConnector.deleteChunksByStoreHintAndDocId(XWikiDocumentStore.NAME, documentId);
        } else {
            // Queue an indexing task. The task itself will determine if this document is actually part of a collection.
            this.taskManager.addTask(wiki, document.getId(), XWikiDocumentDocumentIndexingTaskConsumer.NAME);
        }
    }

    private static boolean isXWikiCollection(BaseObject baseObject)
    {
        return baseObject != null
            && XWikiDocumentStore.NAME.equals(baseObject.getStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME));
    }
}
