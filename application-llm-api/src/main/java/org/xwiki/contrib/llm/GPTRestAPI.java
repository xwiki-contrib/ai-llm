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

import com.github.openjson.JSONObject;
import org.xwiki.stability.Unstable;

import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.core.Response;

import org.xwiki.component.manager.ComponentManager;

import org.xwiki.component.annotation.Component;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.rest.XWikiRestComponent;
import org.xwiki.rest.internal.resources.pages.ModifiablePageResource;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import org.xwiki.csrf.CSRFToken;

@Component
@Named("org.xwiki.contrib.llm.GPTRestAPI")
@Path("/v1")
@Unstable
@Singleton
public class GPTRestAPI extends ModifiablePageResource implements XWikiRestComponent 
{

    @Inject
    @Named("context")
    protected ComponentManager componentManager;
    @Inject
    protected Logger logger;

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
    private Boolean isCsrfValid(List<String> csrfTokenList) {
        if (csrfTokenList.isEmpty()) {
            return false;
        }
        String token = csrfToken.getToken();
        String csrfClient = csrfTokenList.get(0);
        if (!csrfClient.equals(token)) {
            logger.info(token);
            logger.info(csrfClient);
            return false;
        }
        return true;
    }

    /**
     * @param data    Map<String, Object> representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return {@link javax.ws.rs.core.Response} A Response containing JSON data of
     *         the LLM model response or a stream connection if streaming request.
     * @throws XWikiRestException if something goes wrong.
     */
    @POST
    @Path("/chat/completions")
    @Consumes("application/json")
    public Response getContents(Map<String, Object> data, @Context HttpHeaders headers) throws XWikiRestException {
        try {
            if (!isCsrfValid(headers.getRequestHeader(csrfKey))) {
                return Response.status(Response.Status.FORBIDDEN).entity(invalidRequestMsg).build();
            }
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                logger.info("key: " + entry.getKey() + "; value: " + entry.getValue());
            }
            boolean isMapEmpty = data.get("text") == null || data.get("modelType") == null || data.get("model") == null
                    || data.get("prompt") == null;
            if (isMapEmpty) {
                logger.info("Invalid error data");
                return Response.status(Response.Status.BAD_REQUEST).entity("Invalid input data.").build();
            }

            String resStr;
            if (!data.get("stream").equals("true")) {
                try {
                    logger.info("thread request 1");
                    resStr = gptApi.getLLMChatCompletion(data);
                    logger.info("end oof thread request");
                    JSONObject res = new JSONObject(resStr);
                    byte[] resByte = res.toString().getBytes(StandardCharsets.UTF_8);
                    return Response.ok(resByte, MediaType.APPLICATION_JSON).build();
                } catch (GPTAPIException e) {
                    logger.error("An error occured" + e);
                    throw new Exception(e);
                }
            } else {
                StreamingOutput stream = gptApi.getLLMChatCompletionAsStream(data);
                return Response.ok(stream, MediaType.TEXT_PLAIN).build();
            }
        } catch (Exception e) {
            logger.error("An error occured in REST method", e);
            JSONObject builder = new JSONObject();
            JSONObject root = new JSONObject();
            root.put("error", "An error occured. " + e.getMessage());
            builder.put("", root);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(builder.toString())
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }

    /**
     * @param data    Map<String, Object> representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return A {@link javax.ws.rs.core.Response} A Response containing JSON data
     *         of every LLM models properties.
     * @throws XWikiRestException if something goes wrong.
     */
    @POST
    @Path("/models")
    public Response getModels(Map<String, Object> data, @Context HttpHeaders headers) throws XWikiRestException {
        if (!isCsrfValid(headers.getRequestHeader(csrfKey))) {
            return Response.status(Response.Status.FORBIDDEN).entity(invalidRequestMsg).build();
        }
        try {
            byte[] resByte = gptApi.getModels(data).getBytes(StandardCharsets.UTF_8);
            return Response.ok(resByte, MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            logger.error("Error processing request: " + e);
            JSONObject builder = new JSONObject();
            builder.put("", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(builder.toString())
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }

    /**
     * @param data    Map<String, Object> representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return {@link javax.ws.rs.core.Response} A Response containing JSON data
     *         with the wanted prompt properties.
     * @throws XWikiRestException if something goes wrong.
     */
    @POST
    @Path("/prompt")
    @Consumes("application/json")
    public Response getPrompt(Map<String, Object> data, @Context HttpHeaders headers) throws XWikiRestException {
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
     * @param data    Map<String, Object> representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return {@link javax.ws.rs.core.Response} A Response containing JSON data
     *         with the properties of every prompt available.
     * @throws XWikiRestException if something goes wrong.
     */
    @POST
    @Path("/prompts")
    @Consumes("application/json")
    public Response getPromptDB(Map<String, Object> data, @Context HttpHeaders headers) throws XWikiRestException {
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
     * @param data    Map<String, Object> representing the body parameter of the
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
    public Response check(Map<String, Object> data, @Context HttpHeaders headers) throws XWikiRestException {
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
     * @param data    Map<String, Object> representing the body parameter of the
     *                request.
     * @param headers The http headers of the request.
     * @return {@link javax.ws.rs.core.Response} A Response containing JSON data
     *         with "isAdmin" properties. isAdmin is true if the user making the
     *         request is an admin on the current intsance, else false.
     */
    @POST
    @Path("/permission")
    @Consumes("application/json")
    public Response isUserAdmin(Map<String, Object> data, @Context HttpHeaders headers) {
        if (!isCsrfValid(headers.getRequestHeader(csrfKey))) {
            return Response.status(Response.Status.FORBIDDEN).entity(invalidRequestMsg).build();
        }
        try {
            Boolean isAdmin = gptApi.isUserAdmin((String) data.get("currentWiki"));
            logger.info("isAdmin user:" + isAdmin);
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
