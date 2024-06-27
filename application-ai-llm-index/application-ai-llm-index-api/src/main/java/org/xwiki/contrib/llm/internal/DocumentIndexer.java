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

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Chunk;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.EmbeddingsUtils;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;

/**
 * Indexes a document by chunking it and storing the chunks in Solr.
 *
 * @version $Id$
 * @since 0.4
 */
@Component(roles = DocumentIndexer.class)
@Singleton
public class DocumentIndexer
{
    @Inject
    private CollectionManager collectionManager;

    @Inject
    private SolrConnector solrConnector;

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private EmbeddingsUtils embeddingsUtils;

    /**
     * Index a document by chunking it and storing the chunks in Solr.
     *
     * @param wiki the wiki where the document is
     * @param collection the collection where the document is
     * @param document the document to index
     * @throws IndexException if an error occurs while indexing the document
     */
    public void indexDocument(String wiki, String collection, String document) throws IndexException
    {
        XWikiContext context = this.contextProvider.get();

        String previousWiki = context.getWikiId();

        try {
            Collection collectionObj = this.collectionManager.getCollection(collection);
            Document documentObj = collectionObj.getDocumentStore().getDocument(document);
            this.solrConnector.deleteChunksByDocId(wiki, collection, document);
            List<Chunk> chunks = documentObj.chunkDocument();
            String embeddingModel = collectionObj.getEmbeddingModel();
            UserReference author = collectionObj.getAuthor();
            int maximumParallelism = this.embeddingsUtils.getMaximumNumberOfTexts(embeddingModel, author);

            // Group chunks into groups of size maximumParallelism
            for (int i = 0; i < chunks.size(); i += maximumParallelism) {
                int end = Math.min(i + maximumParallelism, chunks.size());
                List<Chunk> chunkGroup = chunks.subList(i, end);
                embedAndStoreChunks(document, chunkGroup, embeddingModel, author, i, end);
            }
        } catch (AccessDeniedException e) {
            throw new IndexException("Access denied while getting document for chunking", e);
        } finally {
            context.setWikiId(previousWiki);
        }
    }

    private void embedAndStoreChunks(String document, List<Chunk> chunkGroup, String embeddingModel,
        UserReference author, int firstChunkIndex, int lastChunkIndex) throws IndexException
    {
        try {
            List<String> texts = chunkGroup.stream().map(Chunk::getContent).toList();
            List<double[]> embeddings = this.embeddingsUtils.computeEmbeddings(texts, embeddingModel, author);
            for (int j = 0; j < chunkGroup.size(); j++) {
                chunkGroup.get(j).setEmbeddings(embeddings.get(j));
            }
        } catch (IndexException e) {
            String rootCauseMessage = ExceptionUtils.getRootCauseMessage(e);
            this.logger.warn("Error while embedding chunks [{}-{}] of document [{}]: [{}]", firstChunkIndex,
                lastChunkIndex, document, rootCauseMessage);
            String errorMessage = "Error computing the embedding: %s".formatted(rootCauseMessage);
            chunkGroup.forEach(chunk -> chunk.setErrorMessage(errorMessage));
        }

        try {
            this.solrConnector.storeChunks(chunkGroup);
        } catch (Exception e) {
            // Storing in Solr shouldn't fail, if this fails it doesn't make sense to continue embedding chunks.
            throw new IndexException("Error while storing chunks in Solr", e);
        }
    }
}
