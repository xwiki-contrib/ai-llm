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

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.common.SolrDocument;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.livedata.LiveDataPropertyDescriptor;
import org.xwiki.xml.XMLUtils;

/**
 * A long text property of a chunk in the LLM index that is displayed with line breaks.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Named("longText")
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class LongTextLLMIndexProperty extends AbstractLLMIndexProperty
{
    @Override
    public LiveDataPropertyDescriptor getPropertyDescriptor()
    {
        LiveDataPropertyDescriptor propertyDescriptor = super.getPropertyDescriptor();
        propertyDescriptor.setDisplayer(new LiveDataPropertyDescriptor.DisplayerDescriptor("html"));
        return propertyDescriptor;
    }

    @Override
    public Object getValue(SolrDocument document)
    {
        String value = String.valueOf(super.getValue(document));
        return StringUtils.replace(XMLUtils.escape(value), "\n", "<br>");
    }
}
