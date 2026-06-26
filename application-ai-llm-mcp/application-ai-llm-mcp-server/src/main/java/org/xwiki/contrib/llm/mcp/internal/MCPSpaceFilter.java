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
import org.xwiki.model.reference.DocumentReference;

/**
 * Applies the per-wiki MCP space whitelist/blacklist configured in {@link MCPServerConfiguration}.
 *
 * <p>This filter narrows the set of documents the wiki's MCP tools may reach; it is a content-visibility
 * restriction layered <em>on top of</em> the regular rights checks, never a replacement for them. A document
 * that the space filter allows must still pass the usual {@code VIEW}/{@code EDIT} authorization. A
 * legitimately empty configuration or {@code mode=none} imposes no restriction; on a configuration-read error
 * the filter fails closed (denies the document / yields no search results).</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Role
public interface MCPSpaceFilter
{
    /**
     * Returns whether the given document may be accessed under its wiki's space filter. This answers only
     * the space-filter question; callers must still perform the regular rights check.
     *
     * @param target the document being accessed
     * @return {@code true} when the wiki's space filter allows the document (or imposes no restriction)
     */
    boolean isAllowed(DocumentReference target);

    /**
     * Returns the Solr filter-query clauses that restrict a document search to the wiki's allowed spaces and
     * documents. Each returned clause is an independent {@code fq} entry to be ANDed into the search. The list
     * is empty when the wiki imposes no restriction.
     *
     * @param wikiId the wiki whose space filter to translate into filter queries
     * @return the filter-query clauses to AND into a search, or an empty list when unrestricted
     */
    List<String> filterQueries(String wikiId);
}
