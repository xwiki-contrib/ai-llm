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

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.Role;
import java.util.Map;

/**
 * Interface to retrieve Prompt from the Prompt Database of the LLM AI extension in XWiki
 * instances.
 * @version $Id$
 */
@Component
@Role
public interface GPTAPIPromptDBProvider 
{
    /**
     * @param promptName  The prompt page full name (like AI.PromptDB.*).
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @return The corresponding {@link GPTAPIPrompt} object or the default
     *         construction of {@link GPTAPIPrompt} if the object is not found.
     */
    GPTAPIPrompt getPrompt(String promptName, String currentWiki);

    /**
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @return A map containing all the available {@link GPTAPIPrompt} object in the
     *         specified wiki or an empty map if nothing is found or an exception
     *         happen.
     */
    Map<String, GPTAPIPrompt> getPrompts(String currentWiki);
}
