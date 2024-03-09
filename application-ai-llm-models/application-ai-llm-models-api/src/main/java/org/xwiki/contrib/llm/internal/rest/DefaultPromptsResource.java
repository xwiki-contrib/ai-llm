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

import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatClientConfigProvider;
import org.xwiki.contrib.llm.GPTAPIPrompt;
import org.xwiki.contrib.llm.GPTAPIPromptDBProvider;
import org.xwiki.contrib.llm.internal.CORSUtils;
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
    private static final String CORS_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String CORS_ALLOW_METHODS = "Access-Control-Allow-Methods";

    @Inject
    private ChatClientConfigProvider configProvider;

    @Inject
    private GPTAPIPromptDBProvider dbProvider;

    @Override
    public Response getPrompts(String origin, String wikiName) throws XWikiRestException
    {
        try {
            List<GPTAPIPrompt> promptsList = dbProvider.getPrompts(wikiName).values().stream().toList();
            GenericEntity<List<GPTAPIPrompt>> entity = new GenericEntity<>(promptsList) { };
            String allowedOrigin = CORSUtils.matchOrigin(origin, configProvider, wikiName);
            Response.ResponseBuilder responseBuilder = Response.ok(entity, MediaType.APPLICATION_JSON);
            if (allowedOrigin != null) {
                responseBuilder.header(CORS_ALLOW_ORIGIN, allowedOrigin);
            }
            return responseBuilder.header(CORS_ALLOW_METHODS, "GET").build();
        } catch (Exception e) {
            throw new XWikiRestException("Error loading the list of prompts.", e);
        }
    }

    @Override
    public Response options(String origin, String wikiName) throws XWikiRestException
    {
        try {
            String allowedOrigin = CORSUtils.matchOrigin(origin, configProvider, wikiName);
            return CORSUtils.addCORSHeaders(allowedOrigin,
                                            "OPTIONS, GET",
                                            "Authorization, Content-Type, Origin")
                                            .build();
        } catch (Exception e) {
            throw new XWikiRestException("Error handling the preflight request.", e);
        }
    }
}
