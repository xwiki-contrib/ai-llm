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

import javax.inject.Named;
import javax.inject.Singleton;

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

    private static final String FIELD_COLLECTION = "collection";
    private static final String FIELD_DOC_ID = "docId";
    private static final String FIELD_DOC_URL = "docURL";
    private static final String FIELD_LANGUAGE = "language";
    private static final String FIELD_INDEX = "index";
    private static final String FIELD_POS_FIRST_CHAR = "posFirstChar";
    private static final String FIELD_POS_LAST_CHAR = "posLastChar";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_VECTOR = "vector";

    private static final String FIELD_TYPE_KNN_VECTOR = "knn_vector";

    private static final long CURRENT_VERSION = 121000000;

    @Override
    protected void createSchema() throws SolrException
    {
        this.addFieldType(FIELD_TYPE_KNN_VECTOR,
                          "solr.DenseVectorField",
                          "vectorDimension", "384",
                          "similarityFunction", "cosine");
        this.addStringField(FIELD_DOC_ID, false, false);
        this.addStringField(FIELD_COLLECTION, true, false);
        this.addStringField(FIELD_DOC_URL, false, false);
        this.addStringField(FIELD_LANGUAGE, false, false);
        this.addPIntField(FIELD_INDEX, false, false);
        this.addPIntField(FIELD_POS_FIRST_CHAR, false, false);
        this.addPIntField(FIELD_POS_LAST_CHAR, false, false);
        this.addTextGeneralField(FIELD_CONTENT, false, false);
        this.addField(FIELD_VECTOR, FIELD_TYPE_KNN_VECTOR, false, false);
    }

    @Override
    protected void migrateSchema(long cversion) throws SolrException
    {
        // No migration yet.
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
