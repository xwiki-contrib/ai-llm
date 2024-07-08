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
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.DocumentStore;
import org.xwiki.index.IndexException;
import org.xwiki.index.TaskConsumer;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.util.Util;

/**
 * A {@link TaskConsumer} that queues all documents of a collection for index.
 *
 * @version $Id$
 * @since 0.5
 */
@Component
@Singleton
@Named(CollectionIndexingTaskConsumer.NAME)
public class CollectionIndexingTaskConsumer implements TaskConsumer
{
    /**
     * The name of the task consumer.
     */
    public static final String NAME = "llm_collection_indexing";

    private static final int BATCH_SIZE = 1000;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    private TaskManager taskManager;

    @Inject
    @Named("local/uid")
    private EntityReferenceSerializer<String> localUIDEntityReferenceSerializer;

    @Override
    public void consume(DocumentReference documentReference, String version) throws IndexException
    {
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument document = context.getWiki().getDocument(documentReference, context);

            BaseObject collectionObject = document.getXObject(Collection.XCLASS_REFERENCE);
            if (collectionObject == null) {
                this.logger.warn("Document [{}] that is queued for indexing as collection is not a collection",
                    documentReference);
                return;
            }

            String collectionId = collectionObject.getStringValue(DefaultCollection.ID_FIELDNAME);

            Collection collection = this.collectionManager.getCollection(collectionId);
            DocumentStore documentStore = collection.getDocumentStore();

            Optional<String> taskConsumerHint = documentStore.getTaskConsumerHint();

            if (taskConsumerHint.isPresent()) {
                for (int offset = 0; ; offset += BATCH_SIZE) {
                    List<DocumentReference> documents = documentStore.getDocumentReferences(offset, BATCH_SIZE);

                    for (DocumentReference documentToIndex : documents) {
                        long documentId =
                            Util.getHash(this.localUIDEntityReferenceSerializer.serialize(documentToIndex));
                        this.taskManager.addTask(documentToIndex.getWikiReference().getName(), documentId,
                            taskConsumerHint.get());
                    }

                    if (documents.size() < BATCH_SIZE) {
                        break;
                    }
                }
            }
        } catch (org.xwiki.contrib.llm.IndexException e) {
            throw new IndexException("Failed to index collection document [" + documentReference + "]", e);
        } catch (XWikiException e) {
            throw new IndexException("Failed to load the collection document [" + documentReference + "]", e);
        }
    }
}
