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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.rest.XWikiRestComponent;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.rest.internal.resources.pages.ModifiablePageResource;
import org.xwiki.stability.Unstable;

import com.github.openjson.JSONObject;

/**
 * REST API for the LLM AI extension.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Named("org.xwiki.contrib.llm.GPTRestAPI")
@Path("/v1")
@Unstable
@Singleton
public class GPTRestAPI extends ModifiablePageResource implements XWikiRestComponent 
{
    @Inject
    private Logger logger;

    @Inject
    private GPTAPI gptApi;

    @Inject
    private CSRFToken csrfToken;

    private String csrfKey = "X-CSRFToken";
    private String invalidRequestMsg = "Request is not coming from a valid instance.";

    /**
     * @param csrfTokenList List containing the client token to verify.
     * @return true if the csrf token is valid, else false.
     */
    private boolean isCsrfValid(List<String> csrfTokenList)
    {
        return !(csrfTokenList.isEmpty() || !csrfTokenList.get(0).equals(csrfToken.getToken()));
    }

    /**
     * @param data    Map representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return {@link javax.ws.rs.core.Response} A Response containing JSON data
     *         with the wanted prompt properties.
     * @throws XWikiRestException if something goes wrong.
     */
    @POST
    @Path("/prompt")
    @Consumes("application/json")
    public Response getPrompt(Map<String, Object> data, @Context HttpHeaders headers) throws XWikiRestException
    {
        if (!isCsrfValid(headers.getRequestHeader(csrfKey))) {
            return Response.status(Response.Status.FORBIDDEN).entity(invalidRequestMsg).build();
        }
        try {
            byte[] resByte = gptApi.getPrompt(data).getBytes(StandardCharsets.UTF_8);
            return Response.ok(resByte, MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            logger.error("An error occured trying to get the wanted prompt: ", e);
            JSONObject builder = new JSONObject();
            builder.put("", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(builder.toString())
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }

    /**
     * @param data    Map representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return {@link javax.ws.rs.core.Response} A Response containing JSON data
     *         with the properties of every prompt available.
     * @throws XWikiRestException if something goes wrong.
     */
    @POST
    @Path("/prompts")
    @Consumes("application/json")
    public Response getPromptDB(Map<String, Object> data, @Context HttpHeaders headers) throws XWikiRestException
    {
        if (!isCsrfValid(headers.getRequestHeader(csrfKey))) {
            return Response.status(Response.Status.FORBIDDEN).entity(invalidRequestMsg).build();
        }
        try {
            byte[] finalResponseBytes = gptApi.getPrompts(data).getBytes(StandardCharsets.UTF_8);
            return Response.ok(finalResponseBytes, MediaType.APPLICATION_JSON).build();
        } catch (GPTAPIException e) {
            logger.error("An error occured trying to get the prompts: ", e);
            JSONObject builder = new JSONObject();
            builder.put("", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(builder.toString())
                    .type(MediaType.APPLICATION_JSON).build();
        }

    }

    /**
     * @param data    Map representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return {@link javax.ws.rs.core.Response} A Response containing JSON data
     *         with the the "check" property. check can be true if the user making
     *         the request is allowed to use at least one configuration, else false.
     * @throws XWikiRestException if something goes wrong.
     */
    @POST
    @Path("/check-access")
    @Consumes("application/json")
    public Response check(Map<String, Object> data, @Context HttpHeaders headers) throws XWikiRestException
    {
        if (!isCsrfValid(headers.getRequestHeader(csrfKey))) {
            return Response.status(Response.Status.FORBIDDEN).entity(invalidRequestMsg).build();
        }
        try {
            JSONObject response = new JSONObject();
            response.put("check", gptApi.checkAllowance(data));
            byte[] responseByte = response.toString().getBytes(StandardCharsets.UTF_8);
            return Response.ok(responseByte, MediaType.APPLICATION_JSON).build();
        } catch (GPTAPIException e) {
            logger.error("An error occured in the access checking: ", e);
            JSONObject builder = new JSONObject();
            builder.put("", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(builder.toString())
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }

    /**
     * @param data    Map representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return {@link javax.ws.rs.core.Response} A Response containing JSON data
     *         with "isAdmin" properties. isAdmin is true if the user making the
     *         request is an admin on the current intsance, else false.
     */
    @POST
    @Path("/permission")
    @Consumes("application/json")
    public Response isUserAdmin(Map<String, Object> data, @Context HttpHeaders headers)
    {
        if (!isCsrfValid(headers.getRequestHeader(csrfKey))) {
            return Response.status(Response.Status.FORBIDDEN).entity(invalidRequestMsg).build();
        }
        try {
            Boolean isAdmin = gptApi.isUserAdmin((String) data.get("currentWiki"));
            JSONObject res = new JSONObject();
            res.put("isAdmin", isAdmin);
            byte[] resByte = res.toString().getBytes(StandardCharsets.UTF_8);
            return Response.ok(resByte, MediaType.APPLICATION_JSON).build();
        } catch (GPTAPIException e) {
            logger.error("An error occured while trying to get user permission.", e);
            JSONObject builder = new JSONObject();
            builder.put("", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(builder.toString())
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }
}
