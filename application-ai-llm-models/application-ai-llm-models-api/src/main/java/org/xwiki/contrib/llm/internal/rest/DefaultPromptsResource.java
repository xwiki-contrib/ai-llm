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
package org.xwiki.contrib.llm.internal.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIPrompt;
import org.xwiki.contrib.llm.GPTAPIPromptDBProvider;
import org.xwiki.contrib.llm.rest.PromptsResource;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;

/**
 * Default implementation of {@link PromptsResource}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named("org.xwiki.contrib.llm.internal.rest.DefaultPromptsResource")
@Singleton
public class DefaultPromptsResource extends XWikiResource implements PromptsResource
{
    @Inject
    private GPTAPIPromptDBProvider dbProvider;

    @Override
    public Response getPrompts(String wikiName) throws XWikiRestException
    {
        List<GPTAPIPrompt> promptsList = new ArrayList<>();
        try {
            Map<String, GPTAPIPrompt> dbMap = dbProvider.getPrompts(wikiName);
            promptsList.addAll(dbMap.values());
            GenericEntity<List<GPTAPIPrompt>> entity = new GenericEntity<List<GPTAPIPrompt>>(promptsList) { };
            return Response.ok(entity, MediaType.APPLICATION_JSON)
                    .header("Access-Control-Allow-Origin", "http://localhost:3000")
                    .header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Content-Type")
                    .build();
        } catch (Exception e) {
            throw new XWikiRestException("Error loading the list of prompts.", e);
        }
    }    
}
