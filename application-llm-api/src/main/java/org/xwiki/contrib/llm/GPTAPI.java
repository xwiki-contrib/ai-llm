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

import org.codehaus.janino.Java;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.Role;
import java.util.Map;

/**
 * The GPTAPI interface defines methods for interacting with the XWiki instances
 * for the LLM AI extension needs.
 * Implementations of this interface provide ways to retrieve LLM AI
 * configurations,
 * prompts, and other data from a specific wiki.
 */
@Component
@Role
public interface GPTAPI {
    /**
     * This method is used for test purpose only. It is a basic representation of
     * the
     * {@link GPTRestAPI#getContents(Java.util.Map, javax.ws.rs.core.HttpHeaders)}
     * method.
     * 
     * @param data  Map<String, Object> representing the body parameter of the
     *              request.
     * @param token the token needed for the POST request to the server.
     * @return A string representation of the JSON object resulting from the
     *         request.
     * @throws GPTAPIException if something goes wrong.
     */
    public String getLLMChatCompletion(Map<String, Object> data, String token) throws GPTAPIException;

    /**
     * This method is used for test purpose only. It is a basic representation of
     * the {@link GPTRestAPI#getModels(Java.util.Map, javax.ws.rs.core.HttpHeaders)}
     * method.
     * 
     * @param token the token needed for the POST request to the server.
     * @return A string representation of the JSON object resulting from the
     *         request.
     * @throws GPTAPIException if something goes wrong.
     */
    public String getModels(String token) throws GPTAPIException;

    /**
     * @param id          The key used to retrieve the corresponding configuration.
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @param userName    The user the request came from.
     * @return The corresponding {@link GPTAPIConfig} object.
     * @throws GPTAPIException if something goes wrong. Will return default
     *                         {@link GPTAPIConfig} in such case.
     */
    public GPTAPIConfig getConfig(String id, String currentWiki, String userName) throws GPTAPIException;

    /**
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @param userName    The user the request came from.
     * @return A map containing all the available {@link GPTAPIConfig} objects in
     *         the specified wiki or an empty map if no configuration exist.
     * @throws GPTAPIException if something goes wrong. Will return an empty map in
     *                         such case.
     */
    public Map<String, GPTAPIConfig> getConfigs(String currentWiki, String userName) throws GPTAPIException;

    /**
     * @param promptName  The prompt page full name (like AI.PromptDB.*).
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @return The corresponding {@link GPTAPIPrompt} object or default
     *         {@link GPTAPIPrompt} object if not found.
     * @throws GPTAPIException if something goes wrong.
     */
    public GPTAPIPrompt getPrompt(String promptName, String currentWiki) throws GPTAPIException;

    /**
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @return A map containing all the available {@link GPTAPIPrompt} object in the
     *         specified wiki or an empty map if no object exist.
     * @throws GPTAPIException if something goes wrong. Will return an empty map in
     *                         this case.
     */
    public Map<String, GPTAPIPrompt> getPrompts(String currentWiki) throws GPTAPIException;

    /**
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @return true if the user in the XWikiContext is admin in the specified wiki, else return false.
     * @throws GPTAPIException if something goes wrong.
     */
    public Boolean isUserAdmin(String currentWiki) throws GPTAPIException;
}
