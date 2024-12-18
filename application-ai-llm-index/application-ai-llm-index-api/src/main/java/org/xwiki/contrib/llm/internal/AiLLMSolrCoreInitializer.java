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

import java.io.IOException;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.xwiki.component.annotation.Component;
import org.xwiki.search.solr.AbstractSolrCoreInitializer;
import org.xwiki.search.solr.SolrException;

/**
 * Solr core initializer for the aillm application.
 *
 * @version $Id$
 * @since 12.9RC1
 */
@Component
@Singleton
@Named(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE)
public class AiLLMSolrCoreInitializer extends AbstractSolrCoreInitializer
{
    /**
     * Name of the Solr core for aillm application.
     */
    public static final String DEFAULT_AILLM_SOLR_CORE = "aillm";

    /**
     * The number of dimensions of the dense vector field.
     */
    public static final int NUMBER_OF_DIMENSIONS = 1024;

    /**
     * The name of the field that stores the wiki of the chunk.
     */
    public static final String FIELD_WIKI = "wiki";

    /**
     * The name of the field that stores the collection of the chunk.
     */
    public static final String FIELD_COLLECTION = "collection";

    /**
     * The name of the field that stores the document id this chunk is part of.
     */
    public static final String FIELD_DOC_ID = "docId";

    /**
     * The name of the field that stores the URL of the document.
     */
    public static final String FIELD_DOC_URL = "docURL";

    /**
     * The name of the field that stores the language of the chunk.
     */
    public static final String FIELD_LANGUAGE = "language";

    /**
     * The name of the field that stores the index of the chunk, i.e., the how-many-th chunk of the document this is.
     */
    public static final String FIELD_INDEX = "index";

    /**
     * The name of the field that stores the position of the first character of the chunk in the document.
     */
    public static final String FIELD_POS_FIRST_CHAR = "posFirstChar";

    /**
     * The name of the field that stores the position of the last character of the chunk in the document.
     */
    public static final String FIELD_POS_LAST_CHAR = "posLastChar";

    /**
     * The name of the field that stores the content of the chunk.
     */
    public static final String FIELD_CONTENT = "content";

    /**
     * The name of the field that stores the content indexed for keyword search.
     */
    public static final String FIELD_CONTENT_INDEX = "content_index";

    /**
     * The name of the field that stores the vector embedding of the chunk.
     */
    public static final String FIELD_VECTOR = "vector";

    /**
     * The name of the field that stores the error message if there is any.
     */
    public static final String FIELD_ERROR_MESSAGE = "errorMessage";

    /**
     * The name of the field that stores the hint of the store of the collection.
     */
    public static final String FIELD_STORE_HINT = "storeHint";

    private static final String FIELD_TYPE_KNN_VECTOR = "knn_vector";

    // Last version that required a re-index, after that there are currently only field additions
    private static final long REINDEX_VERSION = 121000002;

    // The version that introduces the wiki field
    private static final long WIKI_FIELD_VERSION = 121000003;

    private static final long ERROR_MESSAGE_FILED_VERSION = 121000004;

    private static final long STORE_HINT_VERSION = 121000005;

    private static final long CURRENT_VERSION = 121000006;

    private static final String SOLR_DENSE_VECTOR_FIELD = "solr.DenseVectorField";

    private static final String VECTOR_DIMENSION = "vectorDimension";

    private static final String SIMILARITY_FUNCTION = "similarityFunction";

    private static final String COSINE = "cosine";

    @Override
    protected void createSchema() throws SolrException
    {
        this.addFieldType(FIELD_TYPE_KNN_VECTOR,
            SOLR_DENSE_VECTOR_FIELD,
            VECTOR_DIMENSION, NUMBER_OF_DIMENSIONS,
            SIMILARITY_FUNCTION, COSINE);
        this.addStringField(FIELD_DOC_ID, false, false);
        this.addStringField(FIELD_COLLECTION, false, false);
        this.addStringField(FIELD_DOC_URL, false, false);
        this.addStringField(FIELD_LANGUAGE, false, false);
        this.addPIntField(FIELD_INDEX, false, false);
        this.addPIntField(FIELD_POS_FIRST_CHAR, false, false);
        this.addPIntField(FIELD_POS_LAST_CHAR, false, false);
        this.addTextGeneralField(FIELD_CONTENT, false, false);
        this.addField(FIELD_VECTOR, FIELD_TYPE_KNN_VECTOR, false, false);
        migrateSchemaInternal(REINDEX_VERSION, true);
    }

    @Override
    protected void migrateSchema(long cversion) throws SolrException
    {
        migrateSchemaInternal(cversion, false);
    }

    private void migrateSchemaInternal(long cversion, boolean create) throws SolrException
    {
        if (cversion < REINDEX_VERSION) {
            this.setFieldType(FIELD_TYPE_KNN_VECTOR,
                SOLR_DENSE_VECTOR_FIELD,
                false,
                VECTOR_DIMENSION, NUMBER_OF_DIMENSIONS,
                SIMILARITY_FUNCTION, COSINE);

            this.setStringField(FIELD_COLLECTION, false, false);
            try {
                this.core.getClient().deleteByQuery("*:*");
            } catch (SolrServerException | IOException e) {
                throw new SolrException("Failed to clean the index after changing the collection field.", e);
            }
        }

        if (cversion < WIKI_FIELD_VERSION) {
            this.addStringField(FIELD_WIKI, false, false);
        }

        if (cversion < ERROR_MESSAGE_FILED_VERSION) {
            this.addStringField(FIELD_ERROR_MESSAGE, false, false);
        }

        if (cversion < STORE_HINT_VERSION) {
            this.addStringField(FIELD_STORE_HINT, false, false);
        }

        if (cversion < CURRENT_VERSION) {
            // Add another version of the text field, but indexed as regular text.
            this.setTextGeneralField(FIELD_CONTENT_INDEX, false, false);

            if (!create) {
                indexOldContent();
            }
        }
    }

    /**
     * Index content that was added in old versions for keyword search.
     *
     * @throws SolrException if the indexing fails
     */
    private void indexOldContent() throws SolrException
    {
        // Query all documents and set the FIELD_CONTENT_INDEX from the CONTENT field.
        int batchSize = getMigrationBatchRows();
        int size;
        int start = 0;
        do {
            SolrQuery solrQuery = new SolrQuery();
            solrQuery.setRows(batchSize);
            solrQuery.setStart(start);
            // Add an explicit sort to prevent changing order because of updated documents.
            solrQuery.setSort(SolrQuery.SortClause.asc("id"));

            QueryResponse response;
            try {
                response = this.core.getClient().query(solrQuery);
            } catch (Exception e) {
                throw new SolrException("Failed to search for entries in the source client", e);
            }

            SolrDocumentList result = response.getResults();
            size = result.size();
            start += size;

            if (size > 0) {
                migrateData(response.getResults());
            }
        } while (size == batchSize);
    }

    private void migrateData(SolrDocumentList results) throws SolrException
    {
        for (SolrDocument document : results) {
            if (document.get(FIELD_CONTENT_INDEX) == null) {
                SolrInputDocument targetDocument = new SolrInputDocument();
                migrate(document, targetDocument);
                targetDocument.setField(FIELD_CONTENT_INDEX, document.get(FIELD_CONTENT));

                try {
                    this.core.getClient().add(targetDocument);
                } catch (SolrServerException | IOException e) {
                    throw new SolrException("Failed to update the document with the new field.", e);
                }
            }
        }
    }

    @Override
    protected long getVersion()
    {
        return CURRENT_VERSION;
    }

    @Override
    protected int getMigrationBatchRows()
    {
        return 10000;
    }
}
