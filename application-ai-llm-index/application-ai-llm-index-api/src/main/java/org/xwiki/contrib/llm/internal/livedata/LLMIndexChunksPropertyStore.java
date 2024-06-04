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
package org.xwiki.contrib.llm.internal.livedata;

import java.util.Collection;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.livedata.LiveDataPropertyDescriptor;
import org.xwiki.livedata.LiveDataPropertyDescriptorStore;

/**
 * The store for the properties of the LLM index chunk entries.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Singleton
@Named("llmIndexChunks")
public class LLMIndexChunksPropertyStore implements LiveDataPropertyDescriptorStore, Initializable
{
    @Inject
    private Provider<Map<String, LLMIndexProperty>> propertyProvider;

    private Collection<LiveDataPropertyDescriptor> propertyDescriptors;

    @Override
    public void initialize()
    {
        this.propertyDescriptors =
            this.propertyProvider.get().values().stream()
                .map(LLMIndexProperty::getPropertyDescriptor)
                .toList();
    }

    @Override
    public Collection<LiveDataPropertyDescriptor> get()
    {
        return this.propertyDescriptors;
    }

}
