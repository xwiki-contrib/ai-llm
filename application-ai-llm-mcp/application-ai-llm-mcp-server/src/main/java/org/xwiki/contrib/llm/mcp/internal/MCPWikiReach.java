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

/**
 * Decides how far an MCP endpoint may reach beyond its own wiki. Cross-wiki reach is a pure source-side power
 * grant: when the endpoint's own wiki has cross-wiki reach enabled, the endpoint may reach every wiki's content in
 * the farm, regardless of whether the target wiki has its own MCP endpoint enabled. This centralises the reach gate
 * so the document tools and the document search apply the same rule.
 *
 * @version $Id$
 * @since 0.9
 */
@Role
public interface MCPWikiReach
{
    /**
     * @return whether the current (context) wiki's endpoint has cross-wiki reach enabled
     */
    boolean isReachEnabled();

    /**
     * @param targetWiki the wiki a document tool wants to operate on
     * @return whether a document tool may operate on a reference in {@code targetWiki} from this endpoint. A
     *     reference in the current wiki is always reachable; another wiki is reachable whenever this endpoint has
     *     cross-wiki reach enabled.
     */
    boolean canReachWiki(String targetWiki);

    /**
     * Resolves the {@code query_documents} {@code wiki} parameter to the wiki scope of the search, enforcing the
     * reach gate. A blank value (or the current wiki id) resolves to just the current wiki. The special value
     * {@code "all"} resolves to {@code null}, meaning the whole farm (no wiki-scope restriction). Any other value
     * resolves to that single wiki when it is a real wiki in the farm.
     *
     * @param wikiParam the raw {@code wiki} parameter value, possibly blank
     * @return the wiki ids to search, or {@code null} to search the whole farm (no wiki-scope restriction)
     * @throws MCPAccessDeniedException if cross-wiki search is requested but not permitted for this endpoint, or the
     *     requested wiki does not exist
     */
    List<String> resolveSearchWikis(String wikiParam) throws MCPAccessDeniedException;
}
