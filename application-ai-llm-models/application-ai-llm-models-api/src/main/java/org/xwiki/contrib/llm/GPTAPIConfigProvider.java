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
 * Interface to retrieve Configuration of the LLM AI extension in XWiki instances.
 * @version $Id$
 */
@Component
@Role
public interface GPTAPIConfigProvider 
{
    /**
     * @param currentWiki The identifier of the wiki from which the request
     *                    originated.
     * @param userName The user making the request.
     * @return A map containing all the available {@link GPTAPIConfig} objects in
     *         the specified wiki or an empty map if none exist.
     * @throws GPTAPIException if something goes wrong. Will return an empty map as
     *                         well in such case.
     */
    Map<String, GPTAPIConfig> getConfigObjects(String currentWiki, String userName) throws GPTAPIException;
}
