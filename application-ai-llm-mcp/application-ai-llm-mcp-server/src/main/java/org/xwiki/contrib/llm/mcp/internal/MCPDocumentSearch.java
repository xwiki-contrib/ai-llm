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
package org.xwiki.contrib.llm.mcp.internal;

import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;

/**
 * The single sanctioned way for an MCP tool to build a document search query.
 *
 * <p>The returned query is a secure Solr query already scoped to the current wiki and narrowed by the wiki's
 * space filter, with view-rights post-filtering enabled. Every tool that searches documents must obtain its
 * query here so the wiki scope and the space filter are always applied; the caller then adds its own non-fq
 * parameters ({@code qf}, highlighting, sort, limit, offset).</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Role
public interface MCPDocumentSearch
{
    /**
     * Builds a secure Solr search query whose filter queries are the wiki scope, the wiki's space filter, and
     * the given additional filter queries, with current-user view-rights post-filtering enabled.
     *
     * @param statement the Solr query statement
     * @param additionalFilterQueries extra {@code fq} clauses to AND in (e.g. type/hidden/date filters), or
     *     {@code null} for none
     * @return the secure query; the caller adds its own non-fq parameters before executing it
     * @throws QueryException if the query cannot be created
     */
    Query createQuery(String statement, List<String> additionalFilterQueries) throws QueryException;
}
