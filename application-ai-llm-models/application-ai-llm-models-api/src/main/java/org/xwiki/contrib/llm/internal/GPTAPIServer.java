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
package org.xwiki.contrib.llm.internal;

import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.component.wiki.WikiComponent;
import org.xwiki.contrib.llm.RequestError;

/**
 * A wiki component representing a server configured by a GPT API configuration.
 *
 * @version $Id$
 * @since 0.5
 */
@Role
public interface GPTAPIServer extends WikiComponent
{
    /**
     * Embed the given texts with the given model.
     *
     * @param model the embedding model
     * @param texts the texts to embed
     * @return the embeddings of the given texts
     * @throws RequestError if there is any problem calling the embedding model
     */
    List<double[]> embed(String model, List<String> texts) throws RequestError;
}
