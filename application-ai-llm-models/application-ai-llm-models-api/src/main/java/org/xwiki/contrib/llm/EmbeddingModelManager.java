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
import org.xwiki.model.reference.WikiReference;
import org.xwiki.stability.Unstable;
import org.xwiki.user.UserReference;

/**
 * Provides access to the embedding models that are configured for the current wiki.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@Role
public interface EmbeddingModelManager
{
    /**
     * @param wiki the wiki to get the models for
     * @param id the id of the model to retrieve
     * @param userReference the user reference to use for access control
     * @return the model with the given id
     */
    EmbeddingModel getModel(WikiReference wiki, String id, UserReference userReference) throws GPTAPIException;

    /**
     * @param wiki the wiki to get the models for
     * @param userReference the user reference to use for access control
     * @return a list containing the descriptors of all configured models
     */
    List<EmbeddingModelDescriptor> getModelDescriptors(WikiReference wiki, UserReference userReference)
        throws GPTAPIException;
}
