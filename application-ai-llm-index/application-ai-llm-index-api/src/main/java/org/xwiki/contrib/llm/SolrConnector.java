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
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.search.solr.Solr;
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
            SolrInputDocument solrDocument = new SolrInputDocument();
            solrDocument.addField(FIELD_ID, id);
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_WIKI, chunk.getWiki());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_DOC_ID, chunk.getDocumentID());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_COLLECTION, chunk.getCollection());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_DOC_URL, chunk.getDocumentURL());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_LANGUAGE, chunk.getLanguage());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_INDEX, chunk.getChunkIndex());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_POS_FIRST_CHAR, chunk.getPosFirstChar());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_POS_LAST_CHAR, chunk.getPosLastChar());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_ERROR_MESSAGE, chunk.getErrorMessage());
            solrDocument.addField(AiLLMSolrCoreInitializer.FIELD_CONTENT, chunk.getContent());
            double[] embeddings = chunk.getEmbeddings();
            List<Float> embeddingsList = Arrays.stream(embeddings)
                                            .mapToObj(d -> (float) d)
                                            .toList();
            solrDocument.setField(AiLLMSolrCoreInitializer.FIELD_VECTOR, embeddingsList);
            client.add(solrDocument);
            client.commit();
            
        } catch (Exception e) {
            this.logger.error("Failed to store chunk with id [{}]", id, e);
        }
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
        try (SolrClient client = solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            client.deleteByQuery(query);
            client.commit();
        } catch (Exception e) {
            this.logger.error("Failed to delete chunks of document [{}] in collection [{}] in wiki [{}]",
                documentId, collectionId, wiki, e);
        }
    }

    private String buildQuery(String wiki, String collectionId, String documentId)
    {
        return String.join(" AND ",
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
        try (SolrClient client = solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE).getClient()) {
            client.deleteByQuery("*:*");
            client.commit();
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
                                                                            CurrentUserReference.INSTANCE);
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
            logger.error("Similarity search failed: {}", e.getMessage());
        }
        return resultsList;
    }

    private SolrQuery prepareQuery(String embeddingsAsString, List<String> collections, int limit)
    {
        SolrQuery query = new SolrQuery();
        query.addFilterQuery(buildWikiQuery(this.contextProvider.get().getWikiId()));
        query.setQuery(String.format("{!knn f=vector topK=%s}%s", limit, embeddingsAsString));

        // Constructing the filter query from the collections list
        if (collections != null && !collections.isEmpty()) {
            String filterQuery = collections.stream()
                                    .map(collection -> AiLLMSolrCoreInitializer.FIELD_COLLECTION
                                                    + SOLR_SEPARATOR
                                                    + solrUtils.toCompleteFilterQueryString(collection)
                                        )
                                    .collect(Collectors.joining(" OR "));
            query.addFilterQuery(filterQuery);
        }

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
        return query;
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
        sb.append("[");
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
