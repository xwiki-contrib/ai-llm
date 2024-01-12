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

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import org.xwiki.rest.XWikiRestException;
import org.xwiki.stability.Unstable;

/**
 * REST resource for managing collections.
 *
 * @version $Id$
 * @since 0.3
 */
@Path("/wikis/{wikiName}/aiLLM/collections")
@Unstable
public interface CollectionsResource
{
    /**
     * Retrieves a list of all collections in the wiki.
     *
     * @param wikiName the wiki name
     * @return the list of collections
     */
    @GET
    List<String> getCollections(@PathParam("wikiName") String wikiName) throws XWikiRestException;
}
