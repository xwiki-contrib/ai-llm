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

import org.xwiki.component.annotation.Role;
import org.xwiki.component.wiki.WikiComponent;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectReference;

/**
 * Internal role for GPT API server wiki components.
 *
 * @version $Id$
 * @since 0.5.1
 */
@Role
public interface GPTAPIServerWikiComponent extends GPTAPIServer, WikiComponent
{
    /**
     * Initialize the wiki component with the given parameters.
     *
     * @param config the configuration of the server
     * @param objectReference the reference of the object that contained the configuration
     * @param authorReference the reference of the author of the configuration
     */
    void initialize(GPTAPIConfig config, ObjectReference objectReference, DocumentReference authorReference);
}
