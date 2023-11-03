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

import org.apache.commons.httpclient.methods.PostMethod;
import org.codehaus.janino.Java;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.Role;

import java.util.Map;

import javax.ws.rs.core.StreamingOutput;

/**
 * The GPTAPI interface defines methods for interacting with the XWiki instances
 * for the LLM AI extension needs. Implementations of this interface provide
 * ways to retrieve LLM AI configurations, prompts, and other data from a
 * specific wiki.
 * @version $Id$
 */
@Component
@Role
public interface GPTAPI 
{
    /**
     * This method is used for test purpose only. It is a basic representation of
     * the
     * {@link GPTRestAPI#getContents(Java.util.Map, javax.ws.rs.core.HttpHeaders)}
     * method.
     * 
     * @param data Map representing the body parameter of the
     *             request.
     * @return A string representation of the JSON object resulting from the
     *         request.
     * @throws GPTAPIException if something goes wrong.
     */
    String getLLMChatCompletion(Map<String, Object> data) throws GPTAPIException;

    /**
     * @param data Map representing the body parameter of the
     *             request.
     * @return A {@link javax.ws.rs.core.StreamingOutput} to stream the result.
     * @throws GPTAPIException if something goes wrong.
     */
    StreamingOutput getLLMChatCompletionAsStream(Map<String, Object> data) throws GPTAPIException;

    /**
     * @param data Map representing the body parameter of the
     *             request.
     * @return The {@link org.apache.commons.httpclient.methods.PostMethod} object
     *         corresponding to the request.
     * @throws GPTAPIException if something goes wrong.
     */
    PostMethod requestBuilder(Map<String, Object> data) throws GPTAPIException;

    /**
     * @param data Map representing the body parameter of the
     *             request.
     * @return A String representation of a JSON Array containing LLM models
     *         available.
     * @throws GPTAPIException if something goes wrong.
     */
    String getModels(Map<String, Object> data) throws GPTAPIException;

    /**
     * @param id          The key used to retrieve the corresponding configuration.
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @param userName    The user the request came from.
     * @return The corresponding {@link GPTAPIConfig} object.
     * @throws GPTAPIException if something goes wrong. Will return default
     *                         {@link GPTAPIConfig} in such case.
     */
    GPTAPIConfig getConfig(String id, String currentWiki, String userName) throws GPTAPIException;

    /**
     * @param data Map representing the body parameter of the
     *             request.
     * @return The corresponding {@link GPTAPIPrompt} object or default
     *         {@link GPTAPIPrompt} object if not found.
     * @throws GPTAPIException if something goes wrong.
     */
    String getPrompt(Map<String, Object> data) throws GPTAPIException;

    /**
     * @param data Map representing the body parameter of the
     *             request.
     * @return A String representation of a JSON Array containing prompts properties.
     * @throws GPTAPIException if something goes wrong.
     */
    String getPrompts(Map<String, Object> data) throws GPTAPIException;

    /**
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @return true if the user in the XWikiContext is admin in the specified wiki,
     *         else return false.
     * @throws GPTAPIException if something goes wrong.
     */
    Boolean isUserAdmin(String currentWiki) throws GPTAPIException;

    /**
     * @param data Map representing the body parameter of the
     *             request.
     * @return A Boolean, true if the user making the request is allowed to use the extension, else false.
     * @throws GPTAPIException if something goes wrong.
     */
    Boolean checkAllowance(Map<String, Object> data) throws GPTAPIException;
}
