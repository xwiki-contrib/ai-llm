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

import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.stability.Unstable;
import org.xwiki.user.UserReference;

/**
 * An embedding model that can be used to embed a text.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@Role
public interface EmbeddingModel
{
    /**
     * @param text the text to embed
     * @return the embedding
     */
    double[] embed(String text) throws RequestError;

    /**
     * @param texts the texts to embed
     * @return an embedding for each text
     * @throws RequestError when the API request fails
     */
    List<double[]> embed(List<String> texts) throws RequestError;

    /**
     * @return the descriptor of the model
     */
    EmbeddingModelDescriptor getDescriptor();

    /**
     * @param user the user for whom to check the access
     * @return {@code true} if the user has access to the model, {@code false} otherwise
     */
    boolean hasAccess(UserReference user);
}
