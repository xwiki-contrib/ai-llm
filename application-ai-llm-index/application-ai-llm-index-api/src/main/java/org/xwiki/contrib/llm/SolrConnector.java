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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.contrib.llm.internal.InternalDocumentStore;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.user.CurrentUserReference;

import com.xpn.xwiki.XWikiContext;

/**
 * Connects to the Solr server.
 *
 * @version $Id$
 */
@Component(roles = SolrConnector.class)
@Singleton
public class SolrConnector
{
    private static final String FIELD_ID = "id";
    private static final String FIELD_SCORE = "score";

    private static final String SOLR_SEPARATOR = ":";

    private static final String AND = " AND ";

    private static final String OR_DELIMITER = " OR ";

    private static final String RANGE_START = "[";

    private static final String TO = " TO ";

    private static final String PARENTHESIS_OPEN = "(";

    private static final String PARENTHESIS_CLOSE = ")";

    @Inject
    private Logger logger;

    @Inject
    private EmbeddingsUtils embeddingsUtils;

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private Solr solr;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Provider<Chunk> chunkProvider;

    /**
     * Connects to the Solr server and stores a chunk.
     * If a chunk with the same id exists, it will be updated.
     * 
     * @param chunk the chunk to be storred
     * @param id the id of the chunk
     */
    public void storeChunk(Chunk chunk, String id) throws SolrServerException
    {
        try (SolrClient client = solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            SolrInputDocument solrDocument = getSolrDocument(chunk);
            client.add(solrDocument);
            client.commit();
            
        } catch (Exception e) {
            this.logger.error("Failed to store chunk with id [{}]", id, e);
        }
    }

    /**
     * Connects to the Solr server and stores a list of chunks.
     *
     * @param chunks the list of chunks to be stored
     */
    public void storeChunks(List<Chunk> chunks) throws SolrServerException, IOException, SolrException
    {
        try (SolrClient client = this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            List<SolrInputDocument> solrDocuments = chunks.stream().map(this::getSolrDocument).toList();
            // Don't commit changes explicitly to avoid the performance impact of committing, just ask Solr to commit
            // within 10 seconds.
            client.add(solrDocuments, 10000);
            // Trigger a soft commit to ensure that the chunks are available for search.
            client.commit(null, false, true, true);

        }
    }

    private SolrInputDocument getSolrDocument(Chunk chunk)
    {
        SolrInputDocument solrDocument = new SolrInputDocument();
        // Ensure that the ID is correct.
        chunk.computeId();
        solrDocument.addField(FIELD_ID, chunk.getId());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_WIKI, chunk.getWiki());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_DOC_ID, chunk.getDocumentID());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_COLLECTION, chunk.getCollection());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_DOC_URL, chunk.getDocumentURL());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_LANGUAGE, chunk.getLanguage());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_INDEX, chunk.getChunkIndex());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_POS_FIRST_CHAR, chunk.getPosFirstChar());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_POS_LAST_CHAR, chunk.getPosLastChar());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_ERROR_MESSAGE, chunk.getErrorMessage());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_STORE_HINT, chunk.getStoreHint());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_CONTENT, chunk.getContent());
        solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_CONTENT_INDEX, chunk.getContent());
        double[] embeddings = chunk.getEmbeddings();
        // The embeddings could be null if we got an error and want to store the error.
        if (embeddings != null) {
            List<Float> embeddingsList = Arrays.stream(embeddings)
                .mapToObj(d -> (float) d)
                .toList();
            solrDocument.setField(AiLLMSolrCoreInitializer.FIELD_VECTOR, embeddingsList);
        }
        return solrDocument;
    }

    /**
     * Connects to the Solr server and deletes a document.
     * 
     * @param id the id of the chunk
     */
    public void deleteChunk(String id)
    {
        try (SolrClient client = solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            client.deleteById(id);
            client.commit();
        } catch (Exception e) {
            this.logger.error("Failed to delete chunk with id [{}]", id, e);
        }
    }

    /**
     * Connects to the Solr server and deletes all chunks of a document.
     *
     * @param wiki the wiki in which the document is stored
     * @param collectionId the id of the collection the document is part of
     * @param documentId the id of the document
     */
    public void deleteChunksByDocId(String wiki, String collectionId, String documentId)
    {
        String query = buildQuery(wiki, collectionId, documentId);
        try {
            deleteChunksByQuery(query);
        } catch (Exception e) {
            this.logger.error("Failed to delete chunks of document [{}] in collection [{}] in wiki [{}]",
                documentId, collectionId, wiki, e);
        }
    }

    /**
     * Connects to the Solr server and deletes all chunks of a document that belong to the specified store.
     *
     * @param wiki the wiki in which the document is stored
     * @param collectionId the id of the collection the document is part of
     * @param documentId the id of the document
     * @param storeId the id of the document store
     */
    public void deleteChunksByDocIdAndStore(String wiki, String collectionId, String documentId, String storeId)
    {
        String query = buildQuery(wiki, collectionId, documentId) + AND + buildStoreQuery(storeId);
        try {
            deleteChunksByQuery(query);
        } catch (Exception e) {
            this.logger.error("Failed to delete chunks of document [{}] and store [{}] in collection [{}] in wiki [{}]",
                documentId, storeId, collectionId, wiki, e);
        }
    }

    private String buildStoreQuery(String storeId)
    {
        String result = AiLLMSolrCoreInitializer.FIELD_STORE_HINT + SOLR_SEPARATOR
            + this.solrUtils.toCompleteFilterQueryString(storeId);
        // Before version 0.5, there was no storeId field and all documents belonged to the "internal" store.
        // Therefore, also match documents with empty storeId field when the storeId is "internal".
        if (InternalDocumentStore.NAME.equals(storeId)) {
            result = "(%s OR (*:* AND -%s:[* TO *]))".formatted(result, AiLLMSolrCoreInitializer.FIELD_STORE_HINT);
        }

        return result;
    }

    /**
     * Delete all chunks with the given store hint and document id.
     *
     * @param storeHint the hint of the store for which chunks shall be deleted
     * @param documentId the document id for which chunks shall bee deleted
     */
    public void deleteChunksByStoreHintAndDocId(String storeHint, String documentId)
    {
        String query = AiLLMSolrCoreInitializer.FIELD_STORE_HINT + SOLR_SEPARATOR
            + this.solrUtils.toCompleteFilterQueryString(storeHint)
            + AND
            + AiLLMSolrCoreInitializer.FIELD_DOC_ID + SOLR_SEPARATOR
            + this.solrUtils.toCompleteFilterQueryString(documentId);
        try {
            deleteChunksByQuery(query);
        } catch (Exception e) {
            this.logger.error("Failed to delete chunks of document [{}] with store hint [{}]",
                documentId, storeHint, e);
        }
    }

    /**
     * Delete chunks that match the specified query from the wiki and collection.
     *
     * @param wiki the wiki to delete the chunks from
     * @param collectionId the collection to delete the chunks from
     * @param query the query that matches the chunks to delete
     */
    public void deleteChunksByQuery(String wiki, String collectionId, String query)
    {
        String fullQuery = String.join(AND,
            buildWikiQuery(wiki),
            AiLLMSolrCoreInitializer.FIELD_COLLECTION + SOLR_SEPARATOR
                + this.solrUtils.toCompleteFilterQueryString(collectionId),
            PARENTHESIS_OPEN + query + PARENTHESIS_CLOSE
        );
        try {
            deleteChunksByQuery(fullQuery);
        } catch (Exception e) {
            this.logger.error("Failed to delete chunks in collection [{}] in wiki [{}] with query [{}]",
                collectionId, wiki, query, e);
        }
    }

    /**
     * Get a range of chunks of a document.
     *
     * @param wiki the wiki to get the chunks from
     * @param collectionId the collection to get the chunks from
     * @param documentId the document to get the chunks from
     * @param startChunk the index of the first chunk to return
     * @param endChunk the index after the last chunk to return
     * @return the specified chunks
     */
    public List<Chunk> getChunks(String wiki, String collectionId, String documentId, int startChunk, int endChunk)
    {
        String filterQuery = buildQuery(wiki, collectionId, documentId);
        String queryString =
            AiLLMSolrCoreInitializer.FIELD_INDEX + SOLR_SEPARATOR + RANGE_START + startChunk + TO + endChunk + "]";
        SolrQuery query = new SolrQuery();
        query.addFilterQuery(filterQuery);
        query.addFilterQuery(queryString);
        query.setRows(endChunk - startChunk);

        try (SolrClient client = this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            QueryResponse response = client.query(query);
            SolrDocumentList documents = response.getResults();
            return documents.stream()
                .map(this::toChunk)
                .toList();
        } catch (Exception e) {
            this.logger.error("Failed to get chunks [{}, {}] of document [{}] in collection [{}] in wiki [{}]",
                startChunk, endChunk, documentId, collectionId, wiki, e);
            return List.of();
        }
    }

    private Chunk toChunk(SolrDocument solrDocument)
    {
        Chunk result = this.chunkProvider.get();
        result.initialize(
            (String) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_DOC_ID),
            (String) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_COLLECTION),
            (String) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_DOC_URL),
            (String) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_LANGUAGE),
            (Integer) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_POS_FIRST_CHAR),
            (Integer) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_POS_LAST_CHAR),
            (String) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_CONTENT)
        );
        result.setWiki((String) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_WIKI));
        result.setChunkIndex((Integer) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_INDEX));
        result.setErrorMessage((String) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_ERROR_MESSAGE));
        result.setStoreHint((String) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_STORE_HINT));
        result.setId((String) solrDocument.getFieldValue(FIELD_ID));
        List<?> vectorField = (List<?>) solrDocument.getFieldValue(AiLLMSolrCoreInitializer.FIELD_VECTOR);
        if (vectorField != null) {
            result.setEmbeddings(vectorField.stream()
                .map(String.class::cast)
                .mapToDouble(Double::parseDouble)
                .toArray());
        }
        return result;
    }

    /**
     * Delete chunks of a document starting with a specific index.
     *
     * @param wiki the wiki in which the document is stored
     * @param collectionId the id of the collection the document is part of
     * @param documentId the id of the document
     * @param startChunk the index of the first chunk to delete
     */
    public void deleteChunksByIndex(String wiki, String collectionId, String documentId, int startChunk)
    {
        String documentQuery = buildQuery(wiki, collectionId, documentId);
        String indexQuery = AiLLMSolrCoreInitializer.FIELD_INDEX + SOLR_SEPARATOR + RANGE_START + startChunk + " TO *]";
        String query = documentQuery + AND + indexQuery;
        try {
            deleteChunksByQuery(query);
        } catch (Exception e) {
            this.logger.error(
                "Failed to delete chunks starting with index [{}] of document [{}] in collection [{}] in wiki [{}]",
                startChunk, documentId, collectionId, wiki, e);
        }
    }

    /**
     * Delete chunks of a document with a specified range of indexes.
     *
     * @param wiki the wiki in which the document is stored
     * @param collectionId the id of the collection the document is part of
     * @param documentId the id of the document
     * @param startChunk the index of the first chunk to delete
     * @param endChunk the index of the first chunk to not delete anymore
     */
    public void deleteChunksByIndex(String wiki, String collectionId, String documentId, int startChunk, int endChunk)
    {
        String documentQuery = buildQuery(wiki, collectionId, documentId);
        String indexQuery =
            AiLLMSolrCoreInitializer.FIELD_INDEX + SOLR_SEPARATOR + RANGE_START + startChunk + TO + endChunk + "}";
        String query = documentQuery + AND + indexQuery;
        try {
            deleteChunksByQuery(query);
        } catch (Exception e) {
            this.logger.error("Failed to delete chunks [{}] - [{}] of document [{}] in collection [{}] in wiki [{}]",
                startChunk, endChunk, documentId, collectionId, wiki, e);
        }
    }

    private void deleteChunksByQuery(String query) throws IOException, SolrServerException, SolrException
    {
        try (SolrClient client = this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            // Ask for an actual commit within 10 seconds to avoid the cost of a hard commit.
            client.deleteByQuery(query, 10000);
            // Trigger an explicit soft commit to ensure that the chunks are really gone when we search for them
            // while checking if we should re-embed a chunk.
            client.commit(null, false, true, true);
        }
    }

    /**
     * Filter the given document ids to only return those for which at least one chunk has been indexed.
     *
     * @param wiki the wiki of the documents
     * @param collectionId the collection of the documents
     * @param documentIds the ids of the documents to check
     * @return the document ids that have already been indexed, or an empty list if the query failed
     */
    public List<String> filterExistingDocuments(String wiki, String collectionId, List<String> documentIds)
    {
        SolrQuery query = new SolrQuery();
        query.addFilterQuery(buildWikiQuery(wiki));
        query.addFilterQuery(AiLLMSolrCoreInitializer.FIELD_COLLECTION + SOLR_SEPARATOR
            + this.solrUtils.toCompleteFilterQueryString(collectionId));
        // Only check for chunk 0 to avoid duplicates.
        query.addFilterQuery(AiLLMSolrCoreInitializer.FIELD_INDEX + SOLR_SEPARATOR + "0");
        query.setFields(AiLLMSolrCoreInitializer.FIELD_DOC_ID);
        query.setRows(documentIds.size());
        query.addFilterQuery(AiLLMSolrCoreInitializer.FIELD_DOC_ID + SOLR_SEPARATOR
            + documentIds.stream()
                .map(this.solrUtils::toCompleteFilterQueryString)
                .collect(Collectors.joining(OR_DELIMITER, PARENTHESIS_OPEN, PARENTHESIS_CLOSE)));

        try (SolrClient client = this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            QueryResponse response = client.query(query);
            SolrDocumentList documents = response.getResults();
            return documents.stream()
                .map(document -> String.valueOf(document.getFieldValue(AiLLMSolrCoreInitializer.FIELD_DOC_ID)))
                .toList();
        } catch (Exception e) {
            this.logger.error("Failed to filter existing documents in collection [{}] in wiki [{}]",
                collectionId, wiki, e);
            return List.of();
        }
    }

    /**
     * Connects to the Solr server and deletes all chunks of a collection.
     *
     * @param wiki the wiki in which the collection is stored
     * @param collectionId the id of the collection to delete
     */
    public void deleteChunksByCollection(String wiki, String collectionId)
    {
        String query = buildWikiQuery(wiki) + AND + AiLLMSolrCoreInitializer.FIELD_COLLECTION + SOLR_SEPARATOR
            + this.solrUtils.toCompleteFilterQueryString(collectionId);
        try {
            deleteChunksByQuery(query);
        } catch (Exception e) {
            this.logger.error("Failed to delete chunks of collection [{}] in wiki [{}]", collectionId, wiki, e);
        }
    }

    private String buildQuery(String wiki, String collectionId, String documentId)
    {
        return String.join(AND,
            buildWikiQuery(wiki),
              AiLLMSolrCoreInitializer.FIELD_COLLECTION + SOLR_SEPARATOR
                  + this.solrUtils.toCompleteFilterQueryString(collectionId),
              AiLLMSolrCoreInitializer.FIELD_DOC_ID + SOLR_SEPARATOR
                  + this.solrUtils.toCompleteFilterQueryString(documentId)
          );
    }

    private String buildWikiQuery(String wiki)
    {
        return "((" + AiLLMSolrCoreInitializer.FIELD_WIKI + SOLR_SEPARATOR
            + this.solrUtils.toCompleteFilterQueryString(wiki)
            // Also match documents with empty wiki field as before version 0.4, no wiki field was stored.
            + ") OR (*:* AND -" + AiLLMSolrCoreInitializer.FIELD_WIKI + ":[* TO *]))";
    }

    /**
     * Connects to the Solr server and deletes all documents.
     */
    public void clearIndexCore() throws SolrServerException
    {
        try {
            deleteChunksByQuery("*:*");
        } catch (Exception e) {
            throw new SolrServerException("Failed to clear index core", e);
        }
    }

    /**
     * Simple search in the Solr index.
     * 
     * @param solrQuery the query to use for the search
     * @param limit the maximum number of results to return
     * @param includeVector if true, includes the vector in the results
     * @return a list of document details
     */
    public List<Context> search(String solrQuery, int limit, boolean includeVector) throws SolrServerException
    {
        List<Context> resultsList = List.of();
        try (SolrClient client = solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            SolrQuery query = new SolrQuery();
            query.setQuery(solrQuery);
            query.setFields(FIELD_ID,
                            AiLLMSolrCoreInitializer.FIELD_DOC_ID,
                            AiLLMSolrCoreInitializer.FIELD_COLLECTION,
                            AiLLMSolrCoreInitializer.FIELD_WIKI,
                            AiLLMSolrCoreInitializer.FIELD_DOC_URL,
                            AiLLMSolrCoreInitializer.FIELD_LANGUAGE,
                            AiLLMSolrCoreInitializer.FIELD_INDEX,
                            AiLLMSolrCoreInitializer.FIELD_POS_FIRST_CHAR,
                            AiLLMSolrCoreInitializer.FIELD_POS_LAST_CHAR,
                            AiLLMSolrCoreInitializer.FIELD_CONTENT,
                            FIELD_SCORE,
                            AiLLMSolrCoreInitializer.FIELD_VECTOR
                            );
            query.setRows(limit);
            QueryResponse response = client.query(query);
            SolrDocumentList documents = response.getResults();
            resultsList = collectResults(documents, includeVector);
        } catch (Exception e) {
            logger.error("Search failed: {}", e.getMessage());
        }
        return resultsList;
    }

    /**
     * Simple similarity search in the Solr index.
     * 
     * @param textQuery the query to search for
     * @param collectionEmbeddingModelMap a map of collections and their corresponding embedding models
     * @param limit the maximum number of results to return
     * @return a list of document details
     */
    public List<Context> similaritySearch(String textQuery,
                                               Map<String, String> collectionEmbeddingModelMap,
                                               int limit) throws SolrServerException
    {
        List<Context> resultsList = new ArrayList<>();

        if (limit <= 0) {
            return resultsList;
        }
        
        try (SolrClient client = solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            // split embeddingModelMap into sets of collections with the same embedding model
            Map<String, List<String>> embeddingModelCollectionsMap = collectionEmbeddingModelMap.entrySet().stream()
                // Group by value (embedding model) and collect keys (collections) into a list
                .collect(Collectors.groupingBy(Map.Entry::getValue,
                    Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

            // perform similarity search for each set of collections with the same embedding model
            for (Map.Entry<String, List<String>> entry : embeddingModelCollectionsMap.entrySet()) {
                String embeddingsModelID = entry.getKey();
                List<String> collectionsWithSameEmbeddingModel = entry.getValue();
                double[] queryEmbeddings = embeddingsUtils.computeEmbeddings(textQuery,
                                                                            embeddingsModelID,
                                                                            CurrentUserReference.INSTANCE,
                                                                            EmbeddingModel.EmbeddingPurpose.QUERY);
                String embeddingsAsString = arrayToString(queryEmbeddings);
                SolrQuery query = prepareQuery(embeddingsAsString, collectionsWithSameEmbeddingModel, limit);
                QueryResponse response = client.query(query);
                SolrDocumentList documents = response.getResults();
                resultsList.addAll(collectResults(documents, false));

                //order the resultsList in desc order of FIELD_SCORE
                resultsList.sort(Comparator.comparingDouble(Context::similarityScore).reversed());

                //limit the resultsList to the specified limit
                if (resultsList.size() > limit) {
                    resultsList = resultsList.subList(0, limit);
                }
            }

        } catch (Exception e) {
            logger.error("Similarity search failed: {}", e.getMessage(), e);
        }
        return resultsList;
    }

    /**
     * Perform a keyword search in the content of the chunks in the given collections.
     *
     * @param textQuery the query to search for
     * @param collections the collections to search in
     * @param limit the maximum number of results to return
     * @return a list of context chunks
     */
    public List<Context> keywordSearch(String textQuery, java.util.Collection<String> collections, int limit)
    {
        List<Context> resultsList = new ArrayList<>();

        if (limit > 0) {
            try (SolrClient client = this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
                SolrQuery query = new SolrQuery();
                query.setQuery("%s:%s".formatted(AiLLMSolrCoreInitializer.FIELD_CONTENT_INDEX,
                    this.solrUtils.toCompleteFilterQueryString(textQuery)));
                query.setRows(limit);
                setContextQueryFields(query);
                // Constructing the filter query from the collections list
                addCollectionsQuery(collections, query);
                QueryResponse response = client.query(query);
                SolrDocumentList documents = response.getResults();
                resultsList = collectResults(documents, false);
            } catch (Exception e) {
                this.logger.error("Keyword search failed: {}", e.getMessage(), e);
            }
        }
        return resultsList;
    }

    private SolrQuery prepareQuery(String embeddingsAsString, List<String> collections, int limit)
    {
        SolrQuery query = new SolrQuery();
        query.addFilterQuery(buildWikiQuery(this.contextProvider.get().getWikiId()));
        query.setQuery(String.format("{!knn f=vector topK=%s}%s", limit, embeddingsAsString));

        addCollectionsQuery(collections, query);

        setContextQueryFields(query);
        return query;
    }

    private static void setContextQueryFields(SolrQuery query)
    {
        query.setFields(FIELD_ID,
                        AiLLMSolrCoreInitializer.FIELD_DOC_ID,
                        AiLLMSolrCoreInitializer.FIELD_COLLECTION,
                        AiLLMSolrCoreInitializer.FIELD_DOC_URL,
                        AiLLMSolrCoreInitializer.FIELD_LANGUAGE,
                        AiLLMSolrCoreInitializer.FIELD_INDEX,
                        AiLLMSolrCoreInitializer.FIELD_POS_FIRST_CHAR,
                        AiLLMSolrCoreInitializer.FIELD_POS_LAST_CHAR,
                        AiLLMSolrCoreInitializer.FIELD_CONTENT,
                        FIELD_SCORE);
    }

    private void addCollectionsQuery(java.util.Collection<String> collections, SolrQuery query)
    {
        // Constructing the filter query from the collections list
        if (collections != null && !collections.isEmpty()) {
            String filterQuery = collections.stream()
                                    .map(collection -> AiLLMSolrCoreInitializer.FIELD_COLLECTION
                                                    + SOLR_SEPARATOR
                                                    + solrUtils.toCompleteFilterQueryString(collection)
                                        )
                                    .collect(Collectors.joining(OR_DELIMITER));
            query.addFilterQuery(filterQuery);
        }
    }

    private List<Context> collectResults(SolrDocumentList documents, boolean includeVector)
    {
        //noinspection unchecked
        return documents.stream()
            .map(document -> new Context(
                String.valueOf(document.getFieldValue(AiLLMSolrCoreInitializer.FIELD_COLLECTION)),
                String.valueOf(document.getFieldValue(AiLLMSolrCoreInitializer.FIELD_DOC_ID)),
                String.valueOf(document.getFieldValue(AiLLMSolrCoreInitializer.FIELD_DOC_URL)),
                String.valueOf(document.getFieldValue(AiLLMSolrCoreInitializer.FIELD_CONTENT)),
                document.getFieldValue(FIELD_SCORE) instanceof Number numericScore
                    ? numericScore.doubleValue()
                    : Double.parseDouble(String.valueOf(document.getFieldValue(FIELD_SCORE))),
                includeVector ? (List<Float>) document.getFieldValue(AiLLMSolrCoreInitializer.FIELD_VECTOR) : null
            ))
            .toList();
    }

    /**
     * Converts a double array to a string representation.
     *
     * @param array the array to convert
     * @return the string representation of the array
     */
    private String arrayToString(double[] array)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(RANGE_START);
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(array[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
