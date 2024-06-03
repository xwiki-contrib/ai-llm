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

import org.apache.solr.client.solrj.SolrServerException;
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
     * The name of the field that stores the vector embedding of the chunk.
     */
    public static final String FIELD_VECTOR = "vector";

    private static final String FIELD_TYPE_KNN_VECTOR = "knn_vector";

    // Last version that required a re-index, after that there are currently only field additions
    private static final long REINDEX_VERSION = 121000002;

    private static final long CURRENT_VERSION = 121000003;

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
        migrateSchema(REINDEX_VERSION);
    }

    @Override
    protected void migrateSchema(long cversion) throws SolrException
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

        if (cversion < CURRENT_VERSION) {
            this.addStringField(FIELD_WIKI, false, false);
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
