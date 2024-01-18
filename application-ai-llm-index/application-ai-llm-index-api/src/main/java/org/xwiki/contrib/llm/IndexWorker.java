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
    private CollectionManager collectionManager;

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
        EntityReference documentClassReference = getObjectReference();
        this.logger.info("Event: {}", event);
        XWikiDocument xdocument = (XWikiDocument) source;
        BaseObject documentObject = xdocument.getXObject(documentClassReference);
        this.logger.info("Document ref on event: {}", xdocument.getDocumentReference());
        if (documentObject != null) {
            try {
                //Add document to the queue
                String docID = documentObject.getStringValue("id");
                String docCollection = documentObject.getStringValue("collection");
                this.keyValueQueue.add(new AbstractMap.SimpleEntry<>(docID, docCollection));
                this.logger.info("Document added to queue: {}", docID);
                this.logger.info("Queue size: {}", keyValueQueue.size());
                //if the queue is not empty and the worker is not processing, process the queue
                if (!keyValueQueue.isEmpty() && !isProcessing) {
                    isProcessing = true;
                    processDocumentQueue(xdocument);
                    isProcessing = false;
                }
            } catch (Exception e) {
                this.logger.error("Failure in indexWorker listener", e);
            }

        }
    }

    private void processDocumentQueue(XWikiDocument xdocument)
    {
        logger.info("document ref {}", xdocument.getDocumentReference());
        //while queue is not empty, get first document, log it's ID and remove it from the queue
        while (!this.keyValueQueue.isEmpty()) {
            logger.info("collectionManager pull {}", collectionManager.getCollections());
            logger.info("for document {}", xdocument.getDocumentReference());
            AbstractMap.SimpleEntry<String, String> nextInLine = this.keyValueQueue.poll();
            logger.info("nextInLine {}", nextInLine);
            if (nextInLine != null) {
                String key = nextInLine.getKey();
                String value = nextInLine.getValue();
                this.logger.info("Processing document: {}", key);
                try {
                    Document document = collectionManager.getCollection(value).getDocument(key);
                    logger.info("Document: {}", document);
                    List<Chunk> chunks = document.chunkDocument();
                    logger.info("Chunks: {}", chunks);
                    for (Chunk chunk : chunks) {
                        logger.info("Chunks: docID {}, chunk index {}", chunk.getDocumentID(), chunk.getChunkIndex());
                        chunk.computeEmbeddings();
                        SolrConnector.storeChunk(chunk, generateChunkID(chunk.getDocumentID(), chunk.getChunkIndex()));
                    }
                } catch (Exception e) {
                    this.logger.error("Failure to process document in indexWorker", e);
                }
            }          
        }
    }

    //get XObject reference for the collection XClass
    private EntityReference getObjectReference()
    {
        SpaceReference spaceRef = explicitStringSpaceRefResolver.resolve(Document.XCLASS_SPACE_STRING);

        EntityReference collectionClassRef = new EntityReference(Document.XCLASS_NAME,
                                    EntityType.DOCUMENT,
                                    spaceRef
                                );
        return new EntityReference(Document.XCLASS_NAME, EntityType.OBJECT, collectionClassRef);
    }

    //generate unique id for chunks
    private String generateChunkID(String docID, int chunkIndex)
    {
        return docID + "_" + chunkIndex;
    }

}
