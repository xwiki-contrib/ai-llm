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
package org.xwiki.contrib.llm.internal.livedata;

import org.apache.solr.common.SolrDocument;
import org.xwiki.component.annotation.Role;
import org.xwiki.livedata.LiveDataPropertyDescriptor;
import org.xwiki.livedata.LiveDataQuery;

/**
 * Represents a property of a chunk that is stored in the Solr index.
 *
 * @version $Id$
 * @since 0.4
 */
@Role
public interface LLMIndexProperty
{
    /**
     * Initialize the given property. Must be called before calling any other method.
     *
     * @param id the id of this property
     * @param solrProperty the property under which this property is stored in Solr
     */
    void initialize(String id, String solrProperty);

    /**
     * @param document the Solr document from which the value shall be extracted
     * @return the value of this property in the given document
     */
    Object getValue(SolrDocument document);

    /**
     * @return the {@link LiveDataPropertyDescriptor} of this property
     */
    LiveDataPropertyDescriptor getPropertyDescriptor();

    /**
     * @param liveDataFilter the LiveData filter condition to apply
     * @return the Solr filter query string
     */
    String getFilterQuery(LiveDataQuery.Filter liveDataFilter);

    /**
     * @return the id of this property
     */
    String getId();

    /**
     * @return the name that shall be displayed
     */
    String getDisplayName();

    /**
     * @return the name of the Solr property
     */
    String getSolrProperty();
}
