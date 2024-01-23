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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrInputDocument;
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
    private static final String SOLR_INSTANCE_URL = "http://my_solr:8983/solr/";
    private static final String SOLR_CORE_NAME = "knowledgeIndex";
    private static final String SOLR_CORE_URL = SOLR_INSTANCE_URL + SOLR_CORE_NAME;

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
            solrDocument.addField("id", id);
            solrDocument.addField("docId", chunk.getDocumentID());
            solrDocument.addField("language", chunk.getLanguage());
            solrDocument.addField("index", chunk.getChunkIndex());
            solrDocument.addField("posFirstChar", chunk.getPosFirstChar());
            solrDocument.addField("posLastChar", chunk.getPosLastChar());
            ObjectMapper mapper = new ObjectMapper();
            String content = mapper.writeValueAsString(chunk.getContent());


            solrDocument.addField("content", content);
            double[] embeddings = chunk.getEmbeddings();
            List<Float> embeddingsList = Arrays.stream(embeddings)
                                            .mapToObj(d -> (float) d)
                                            .collect(Collectors.toList());
            solrDocument.setField("vector", Arrays.asList(embeddingsList));
            client.add(solrDocument);
            client.commit();
        } catch (Exception e) {
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }
}
