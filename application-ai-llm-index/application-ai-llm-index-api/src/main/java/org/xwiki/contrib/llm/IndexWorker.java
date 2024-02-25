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

import java.util.AbstractMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.index.TaskManager;

/**
 * This class is responsible for handling the document queue,
 * chunking the documents from the queue, computing the embeddings and storing them in solr.
 * 
 * @version $Id$
 */
@Component
@Named("IndexWorker")
@Singleton
public class IndexWorker implements EventListener
{
 
    @Inject
    private Logger logger;

    @Inject
    private TaskManager taskManager;

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;

    //the queue of documents to be processed as key value pairs of documentID and the collection it belongs to
    private Queue<AbstractMap.SimpleEntry<String, String>> keyValueQueue = new LinkedList<>();
    private boolean isProcessing;
    
    @Override public String getName()
    {
        return "IndexWorker";
    }
    
    @Override public List<Event> getEvents()
    {
        return Arrays.<Event>asList(new DocumentCreatedEvent(), new DocumentUpdatedEvent());
    }

    @Override public void onEvent(Event event, Object source, Object data)
    {
        XWikiDocument xdocument = (XWikiDocument) source;
        EntityReference documentClassReference = getObjectReference();
        BaseObject documentObject = xdocument.getXObject(documentClassReference);

        if (documentObject != null && !xdocument.getDocumentReference().getName().equals("DocumentsTemplate")) {
            try {
                //Add document to the queue
                Queue<XWikiDocument> queue = new LinkedList<>();
                queue.add(xdocument);

                if (!isProcessing && !queue.isEmpty()) {
                    isProcessing = true;
                    processDocumentQueue(queue);
                    isProcessing = false;
                }

            } catch (Exception e) {
                this.logger.error("Failure in indexWorker listener", e);
            }

        }
    }

    private void processDocumentQueue(Queue<XWikiDocument> queue)
    {
        // while queue is not empty, take next document in line and add it to the task manager
        while (!queue.isEmpty()) {
            try {
                XWikiDocument nextInLine = queue.poll();
                if (nextInLine != null) {
                    String wikiId = nextInLine.getDocumentReference().getWikiReference().getName();
                    long documentId = nextInLine.getId();
                    String taskType = "indexing";

                    this.taskManager.addTask(wikiId, documentId, taskType);
                }
            } catch (Exception e) {
                this.logger.error("Failure to process document in indexWorker", e);
            }
        }
    }

    //get XObject reference for the document XClass
    private EntityReference getObjectReference()
    {
        SpaceReference spaceRef = explicitStringSpaceRefResolver.resolve(Document.XCLASS_SPACE_STRING);

        EntityReference collectionClassRef = new EntityReference(Document.XCLASS_NAME,
                                    EntityType.DOCUMENT,
                                    spaceRef
                                );
        return new EntityReference(Document.XCLASS_NAME, EntityType.OBJECT, collectionClassRef);
    }



}
