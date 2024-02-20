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
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
/**
 * Connects to the Solr server.
 *
 * @version $Id$
 */
@Component(roles = SolrConnector.class)
@Singleton
public class SolrConnector
{
    //Connection method will be modifed after Solr integration in XWiki
    private static final String SOLR_INSTANCE_URL = "http://localhost:8983/solr/";
    private static final String SOLR_CORE_NAME = "knowledgeIndex";
    private static final String SOLR_CORE_URL = SOLR_INSTANCE_URL + SOLR_CORE_NAME;
    private static final String FIELD_ID = "id";
    private static final String FIELD_COLLECTION = "collection";
    private static final String FIELD_DOC_ID = "docId";
    private static final String FIELD_DOC_URL = "docURL";
    private static final String FIELD_LANGUAGE = "language";
    private static final String FIELD_INDEX = "index";
    private static final String FIELD_POS_FIRST_CHAR = "posFirstChar";
    private static final String FIELD_POS_LAST_CHAR = "posLastChar";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_SCORE = "score";

    @Inject
    private Logger logger;

    @Inject
    private EmbeddingsUtils embeddingsUtils;

    /**
     * Connects to the Solr server and stores a chunk.
     * If a chunk with the same id exists, it will be updated.
     * 
     * @param chunk the chunk to be storred
     * @param id the id of the chunk
     */
    public void storeChunk(Chunk chunk, String id) throws SolrServerException
    {
        try (SolrClient client = new HttpSolrClient.Builder(SOLR_CORE_URL).build()) {
            SolrInputDocument solrDocument = new SolrInputDocument();
            solrDocument.addField(FIELD_ID, id);
            solrDocument.addField(FIELD_DOC_ID, chunk.getDocumentID());
            solrDocument.addField(FIELD_COLLECTION, chunk.getCollection());
            solrDocument.addField(FIELD_DOC_URL, chunk.getDocumentURL());
            solrDocument.addField(FIELD_LANGUAGE, chunk.getLanguage());
            solrDocument.addField(FIELD_INDEX, chunk.getChunkIndex());
            solrDocument.addField(FIELD_POS_FIRST_CHAR, chunk.getPosFirstChar());
            solrDocument.addField(FIELD_POS_LAST_CHAR, chunk.getPosLastChar());
            ObjectMapper mapper = new ObjectMapper();
            String content = mapper.writeValueAsString(chunk.getContent());


            solrDocument.addField(FIELD_CONTENT, content);
            double[] embeddings = chunk.getEmbeddings();
            List<Float> embeddingsList = Arrays.stream(embeddings)
                                            .mapToObj(d -> (float) d)
                                            .collect(Collectors.toList());
            solrDocument.setField(FIELD_VECTOR, Arrays.asList(embeddingsList));
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
        try (SolrClient client = new HttpSolrClient.Builder(SOLR_CORE_URL).build()) {
            client.deleteById(id);
            client.commit();
        } catch (Exception e) {
            this.logger.error("Failed to delete chunk with id [{}]", id, e);
        }
    }

    /**
     * Connects to the Solr server and deletes all chunks of a document.
     * 
     * @param docId the id of the document
     */
    public void deleteChunksByDocId(String docId)
    {
        try (SolrClient client = new HttpSolrClient.Builder(SOLR_CORE_URL).build()) {
            client.deleteByQuery("docId:" + docId);
            client.commit();
        } catch (Exception e) {
            this.logger.error("Failed to delete chunks of document with id [{}]", docId, e);
        }
    }

    /**
     * Connects to the Solr server and deletes all documents.
     */
    public void clearIndexCore() throws SolrServerException
    {
        try (SolrClient client = new HttpSolrClient.Builder(SOLR_CORE_URL).build()) {
            client.deleteByQuery("*:*");
            client.commit();
        } catch (Exception e) {
            throw new SolrServerException("Failed to clear index core", e);
        }
    }


    /**
     * Simple similarity search in the Solr index.
     * 
     * @param textQuery the query to search for
     * @param collections the collections to search in
     * @return a list of document details
     */
    public List<List<String>> similaritySearch(String textQuery, List<String> collections) throws SolrServerException
    {
        List<List<String>> resultsList = new ArrayList<>();
        try (SolrClient client = new HttpSolrClient.Builder(SOLR_CORE_URL).build()) {
            double[] queryEmbeddings = embeddingsUtils.computeEmbeddings(textQuery);
            String embeddingsAsString = arrayToString(queryEmbeddings);

            SolrQuery query = new SolrQuery();
            query.setQuery("{!knn f=vector topK=3}" + embeddingsAsString);

            // Constructing the filter query from the collections list
            if (collections != null && !collections.isEmpty()) {
                String filterQuery = collections.stream()
                                        .map(collection -> FIELD_COLLECTION + ":\"" + collection + "\"")
                                        .collect(Collectors.joining(" OR "));
                query.addFilterQuery(filterQuery);
            }

            query.setFields(FIELD_ID,
                            FIELD_DOC_ID,
                            FIELD_COLLECTION,
                            FIELD_DOC_URL,
                            FIELD_LANGUAGE,
                            FIELD_INDEX,
                            FIELD_POS_FIRST_CHAR,
                            FIELD_POS_LAST_CHAR,
                            FIELD_CONTENT,
                            FIELD_SCORE);

            QueryResponse response = client.query(query);
            SolrDocumentList documents = response.getResults();
            for (SolrDocument document : documents) {
                List<String> documentDetails = new ArrayList<>();
                documentDetails.add(String.valueOf(document.getFieldValue(FIELD_DOC_ID)));
                documentDetails.add(String.valueOf(document.getFieldValue(FIELD_DOC_URL)));
                documentDetails.add(String.valueOf(document.getFieldValue(FIELD_CONTENT)));

                resultsList.add(documentDetails);
            }
        } catch (Exception e) {
            logger.error("Similarity search failed: {}", e.getMessage());
        }
        return resultsList;
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
