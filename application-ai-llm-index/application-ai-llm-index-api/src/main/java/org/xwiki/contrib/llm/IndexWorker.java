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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.index.TaskManager;
import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * This class is responsible for handling the document queue,
 * chunking the documents from the queue, computing the embeddings and storing them in solr.
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
        BaseObject documentObject = xdocument.getXObject(Document.XCLASS_REFERENCE);

        if (documentObject != null && !xdocument.getDocumentReference().getName().equals("DocumentsTemplate")) {
            addTaskForDocument(xdocument);
        } else {
            // Check if the document was an internal document before the change like a deletion - delete it
            // from Solr. We do this directly as TaskManager doesn't support tasks for deleted documents.
            BaseObject xObject = xdocument.getOriginalDocument().getXObject(Document.XCLASS_REFERENCE);
            if (xObject != null) {
                String id = xObject.getStringValue(DefaultDocument.ID_KEY);
                String collection = xObject.getStringValue(DefaultDocument.PARENT_COLLECTION);
                String wiki = xdocument.getDocumentReference().getWikiReference().getName();
                this.solrConnector.deleteChunksByDocId(wiki, collection, id);
            }
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
