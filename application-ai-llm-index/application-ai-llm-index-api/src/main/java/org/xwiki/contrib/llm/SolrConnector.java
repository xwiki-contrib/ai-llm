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

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrInputDocument;

/**
 * Connects to the Solr server.
 *
 * @version $Id$
 */
public final class SolrConnector
{
    private static final String SOLR_CORE_URL = "http://localhost:8983/solr/gettingstarted";

    /**
     * Private constructor to hide the implicit public one.
     */
    private SolrConnector()
    {
        // private constructor to prevent instantiation
    }

    /**
     * Connects to the Solr server and adds a document.
     * 
     * @param document the document to add
     */
    public static void addDocument(Document document)
    {
        try (SolrClient client = new HttpSolrClient.Builder(SOLR_CORE_URL).build()) {
            SolrInputDocument solrDocument = new SolrInputDocument();
            solrDocument.addField("id", document.getID());
            solrDocument.addField("title", document.getTitle());
            solrDocument.addField("language", document.getLanguage());
            solrDocument.addField("url", document.getURL());
            solrDocument.addField("mimetype", document.getMimetype());
            client.add(solrDocument);
            client.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
