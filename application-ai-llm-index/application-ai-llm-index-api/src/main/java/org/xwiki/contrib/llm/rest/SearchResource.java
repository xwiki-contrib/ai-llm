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
package org.xwiki.contrib.llm.rest;

import java.util.List;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.stability.Unstable;

/**
 * Exposes the context search API.
 *
 * @version $Id$
 * @since 0.8
 */
@Unstable
@Path("/wikis/{wikiName}/aiLLM/search")
public interface SearchResource
{
    /**
     * Default limit for keyword and semantic search results.
     */
    String DEFAULT_LIMIT = "10";

    /**
     * Searches for context chunks similar to the given query.
     *
     * @param wikiName the name of the wiki
     * @param query the query to search for
     * @param collections the collections to search in, when empty all accessible collections are searched
     * @param limitKeywordResults the maximum number of results to return for the keyword search
     * @param limitSemanticResults the maximum number of results to return for the semantic search
     * @return a list of context chunks that are similar to the query
     * @throws XWikiRestException if an error occurs
     */
    @POST
    List<Context> search(
        @PathParam("wikiName") String wikiName,
        @QueryParam("query") String query,
        @QueryParam("collection") List<String> collections,
        @QueryParam("limitKeywordResults") @DefaultValue(DEFAULT_LIMIT) int limitKeywordResults,
        @QueryParam("limitSemanticResults") @DefaultValue(DEFAULT_LIMIT) int limitSemanticResults
    ) throws XWikiRestException;
}
