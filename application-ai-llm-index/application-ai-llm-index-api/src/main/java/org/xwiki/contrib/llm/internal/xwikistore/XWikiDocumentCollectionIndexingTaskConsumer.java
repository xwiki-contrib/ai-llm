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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.index.IndexException;
import org.xwiki.index.TaskConsumer;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.search.solr.SolrUtils;

import com.xpn.xwiki.util.Util;

/**
 * (Re-)Indexes a collection that references one or several spaces of XWiki documents.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Singleton
@Named(XWikiDocumentCollectionIndexingTaskConsumer.NAME)
public class XWikiDocumentCollectionIndexingTaskConsumer implements TaskConsumer
{
    /**
     * The name of this task consumer.
     */
    public static final String NAME = "llm_xwiki_collection";

    private static final int BATCH_SIZE = 100;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    private XWikiDocumentStoreHelper helper;

    @Inject
    private Logger logger;

    @Inject
    private TaskManager taskManager;

    @Inject
    @Named("local/uid")
    private EntityReferenceSerializer<String> localUIDEntityReferenceSerializer;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    @Named("withparameters")
    private EntityReferenceSerializer<String> withParametersEntityReferenceSerializer;

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private SolrConnector solrConnector;

    @Override
    public void consume(DocumentReference documentReference, String version) throws IndexException
    {
        String collectionId = documentReference.getLastSpaceReference().getName();
        Collection collection;

        // Get the collection configuration.
        try {
            collection = this.collectionManager.getCollection(collectionId);
        } catch (org.xwiki.contrib.llm.IndexException e) {
            throw new IndexException("Error loading collection", e);
        }

        // Check if the found collection matches what we were looking for.
        if (collection != null && documentReference.withoutLocale().equals(collection.getDocumentReference())
            && XWikiDocumentStore.NAME.equals(collection.getDocumentStoreHint())) {
            // Get the spaces to index.
            List<SpaceReference> spaceReferences =
                this.helper.resolveSpaceReferences(collection.getDocumentSpaces(), collection.getDocumentReference());
            // Clear any documents not part of the spaces to index.
            cleanOtherDocuments(documentReference.getWikiReference().getName(), collectionId, spaceReferences);
            // Index all remaining documents.
            indexDocuments(collectionId, spaceReferences);
        } else if (collection != null) {
            if (documentReference.equals(collection.getDocumentReference())) {
                this.logger.warn("Ignoring indexing request for [{}] as the collection doesn't use the XWiki document "
                    + "store.", documentReference);
            } else {
                this.logger.warn("Ignoring indexing request for [{}] as the found collection [{}] doesn't match the "
                        + "document to index. Maybe the collection is stored at the wrong place?", documentReference,
                    collection.getDocumentReference());
            }
        } else {
            this.logger.warn("Ignoring indexing request for [{}] as no collection was found for id [{}].",
                documentReference, collectionId);
        }
    }

    private void cleanOtherDocuments(String wiki, String collectionId, List<SpaceReference> spaceReferences)
    {
        // Construct a Solr query to match all chunks whose documentId starts with one of the given spaceReferences.
        if (spaceReferences.isEmpty()) {
            // No spaces to keep - clear all documents from the collection.
            this.solrConnector.deleteChunksByCollection(wiki, collectionId);
        } else {
            String query = spaceReferences.stream()
                .map(this.entityReferenceSerializer::serialize)
                .map(this.solrUtils::toFilterQueryString)
                .map(filter -> filter + ".*")
                .collect(Collectors.joining(" OR ", "(*:* -(" + AiLLMSolrCoreInitializer.FIELD_DOC_ID + ":(",
                    // Also clean all chunks indexed with a different store hint.
                    ") OR " + AiLLMSolrCoreInitializer.FIELD_STORE_HINT + ":" + XWikiDocumentStore.NAME + "))"));

            this.solrConnector.deleteChunksByQuery(wiki, collectionId, query);
        }
    }

    private void indexDocuments(String collectionId, List<SpaceReference> spaceReferences) throws IndexException
    {
        for (SpaceReference spaceReference : spaceReferences) {
            WikiReference wikiReference = spaceReference.getWikiReference();
            for (int offset = 0;; offset += BATCH_SIZE) {
                List<DocumentReference> documents;
                try {
                    documents = this.helper.getDocumentsFromWiki(wikiReference,
                        List.of(spaceReference), offset, BATCH_SIZE);
                } catch (org.xwiki.contrib.llm.IndexException e) {
                    throw new IndexException("Error loading documents", e);
                }

                // Check which documents of this batch have already been indexed.
                List<String> documentIds = documents.stream()
                    .map(this.withParametersEntityReferenceSerializer::serialize)
                    .toList();

                Set<String> indexedDocuments =
                    new HashSet<>(this.solrConnector.filterExistingDocuments(wikiReference.getName(), collectionId,
                        documentIds));

                documents.stream()
                    .filter(documentReference ->
                        !indexedDocuments.contains(
                            this.withParametersEntityReferenceSerializer.serialize(documentReference)))
                    .forEach(this::addTaskForDocument);

                if (documents.size() < BATCH_SIZE) {
                    break;
                }
            }
        }
    }

    private void addTaskForDocument(DocumentReference documentReference)
    {
        long documentId = Util.getHash(this.localUIDEntityReferenceSerializer.serialize(documentReference));
        this.taskManager.addTask(documentReference.getWikiReference().getName(), documentId,
            XWikiDocumentDocumentIndexingTaskConsumer.NAME);
    }
}
