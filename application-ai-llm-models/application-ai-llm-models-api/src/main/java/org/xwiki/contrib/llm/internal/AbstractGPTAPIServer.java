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

import java.lang.reflect.Type;

import org.xwiki.component.wiki.WikiComponentScope;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.ObjectReference;

/**
 * Abstract class for GPT API servers that implement most of the {@link org.xwiki.component.wiki.WikiComponent} API.
 *
 * @version $Id$
 * @since 0.5
 */
public abstract class AbstractGPTAPIServer implements GPTAPIServerWikiComponent
{
    protected GPTAPIConfig config;

    private ObjectReference objectReference;

    private DocumentReference authorReference;

    @Override
    public void initialize(GPTAPIConfig config, ObjectReference objectReference, DocumentReference authorReference)
    {
        this.config = config;
        this.objectReference = objectReference;
        this.authorReference = authorReference;
    }

    @Override
    public DocumentReference getDocumentReference()
    {
        return this.objectReference.getDocumentReference();
    }

    @Override
    public DocumentReference getAuthorReference()
    {
        return this.authorReference;
    }

    @Override
    public Type getRoleType()
    {
        return GPTAPIServer.class;
    }

    @Override
    public String getRoleHint()
    {
        return this.config.getName();
    }

    @Override
    public WikiComponentScope getScope()
    {
        return WikiComponentScope.WIKI;
    }

    @Override
    public EntityReference getEntityReference()
    {
        return this.objectReference;
    }
}
