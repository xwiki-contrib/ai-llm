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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Chunk;
import org.xwiki.contrib.llm.ChunkingUtils;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.EmbeddingModel;
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

    @Inject
    private ChunkingUtils chunkingUtils;

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

            if (documentObj == null) {
                throw new IndexException("Document [%s] does not exist in collection [%s] in wiki [%s]"
                    .formatted(document, collection, wiki));
            }

            List<Chunk> chunks = this.chunkingUtils.chunkDocument(collectionObj, documentObj);
            String embeddingModel = collectionObj.getEmbeddingModel();
            UserReference author = collectionObj.getAuthor();
            int maximumParallelism = this.embeddingsUtils.getMaximumNumberOfTexts(embeddingModel, author);

            // Group chunks into groups of size maximumParallelism.
            for (int i = 0; i < chunks.size(); i += maximumParallelism) {
                int end = Math.min(i + maximumParallelism, chunks.size());
                List<Chunk> chunkGroup = chunks.subList(i, end);
                embedAndStoreChunks(document, chunkGroup, embeddingModel, author, i, end);
            }

            // Delete all remaining chunks.
            this.solrConnector.deleteChunksByIndex(wiki, collection, document, chunks.size());
        } catch (AccessDeniedException e) {
            throw new IndexException("Access denied while getting document for chunking", e);
        } finally {
            context.setWikiId(previousWiki);
        }
    }

    private void embedAndStoreChunks(String document, List<Chunk> chunkGroup, String embeddingModel,
        UserReference author, int firstChunkIndex, int lastChunkIndex) throws IndexException
    {
        if (chunkGroup.isEmpty()) {
            return;
        }

        String wiki = chunkGroup.get(0).getWiki();
        String collection = chunkGroup.get(0).getCollection();
        List<Chunk> existingChunks =
            this.solrConnector.getChunks(wiki, collection, document, firstChunkIndex, lastChunkIndex);

        copyExistingEmbeddings(chunkGroup, existingChunks);

        embedChunks(document, chunkGroup, embeddingModel, author);

        updateChunksIfModified(wiki, collection, document, firstChunkIndex, lastChunkIndex, existingChunks, chunkGroup);
    }

    private static void copyExistingEmbeddings(List<Chunk> chunkGroup, List<Chunk> existingChunks)
    {
        // Take the embedding from the existing chunks if the content matches. For this, index chunks by content.
        Map<String, Chunk> chunkByContent = existingChunks.stream()
            .collect(Collectors.toMap(Chunk::getContent, Function.identity()));
        for (Chunk chunk : chunkGroup) {
            Chunk existingChunk = chunkByContent.get(chunk.getContent());
            // Check that we have an existing embedding that actually contains a non-zero embedding.
            if (existingChunk != null && existingChunk.getEmbeddings() != null
                && Arrays.stream(existingChunk.getEmbeddings()).anyMatch(v -> v != 0.0)) {
                chunk.setEmbeddings(existingChunk.getEmbeddings());
            }
        }
    }

    private void embedChunks(String document, List<Chunk> chunkGroup, String embeddingModel, UserReference author)
    {
        List<Chunk> chunksToEmbed = chunkGroup.stream()
            .filter(chunk -> chunk.getEmbeddings() == null)
            .toList();
        if (!chunksToEmbed.isEmpty()) {
            try {
                List<String> texts = chunksToEmbed.stream().map(Chunk::getContent).toList();
                List<double[]> embeddings = this.embeddingsUtils.computeEmbeddings(texts,
                                                                                embeddingModel,
                                                                                author,
                                                                                EmbeddingModel.EmbeddingPurpose.INDEX);
                for (int j = 0; j < chunksToEmbed.size(); j++) {
                    chunksToEmbed.get(j).setEmbeddings(embeddings.get(j));
                }
            } catch (IndexException e) {
                String rootCauseMessage = ExceptionUtils.getRootCauseMessage(e);
                int firstIndex = chunksToEmbed.get(0).getChunkIndex();
                int lastIndex = chunksToEmbed.get(chunksToEmbed.size() - 1).getChunkIndex();
                this.logger.warn("Error while embedding chunks [{}-{}] of document [{}]: [{}]", firstIndex,
                    lastIndex, document, rootCauseMessage);
                String errorMessage = "Error computing the embedding: %s".formatted(rootCauseMessage);
                chunkGroup.forEach(chunk -> chunk.setErrorMessage(errorMessage));
            }
        }
    }

    private void updateChunksIfModified(String wiki, String collection, String document, int firstChunkIndex,
        int lastChunkIndex, List<Chunk> existingChunks, List<Chunk> newChunks) throws IndexException
    {
        try {
            if (!CollectionUtils.isEqualCollection(newChunks, existingChunks)) {
                // Check if all chunk ids from the existing chunks are in the new chunks.
                Set<String> newChunkIds = newChunks.stream().map(Chunk::getId).collect(Collectors.toSet());
                if (!existingChunks.stream().allMatch(chunk -> newChunkIds.contains(chunk.getId()))) {
                    // Delete the existing chunks. Delete all of them to avoid creating too many or too large queries.
                    // This should only happen when the ID generation algorithm was changed, this was the case in
                    // version 0.4 of LLM the extension.
                    this.solrConnector.deleteChunksByIndex(wiki, collection, document, firstChunkIndex, lastChunkIndex);
                }

                this.solrConnector.storeChunks(newChunks);
            }
        } catch (Exception e) {
            // Storing in Solr shouldn't fail, if this fails it doesn't make sense to continue embedding chunks.
            throw new IndexException("Error while storing chunks in Solr", e);
        }
    }
}
