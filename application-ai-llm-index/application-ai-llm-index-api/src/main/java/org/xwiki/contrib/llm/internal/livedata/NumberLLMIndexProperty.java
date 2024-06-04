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

import java.util.stream.Stream;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.livedata.LiveDataPropertyDescriptor;
import org.xwiki.livedata.LiveDataQuery;

/**
 * A number property of a chunk in the LLM index.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Named("number")
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class NumberLLMIndexProperty extends AbstractLLMIndexProperty
{
    private static final String NUMBER = "number";

    @Override
    public LiveDataPropertyDescriptor getPropertyDescriptor()
    {
        LiveDataPropertyDescriptor propertyDescriptor = super.getPropertyDescriptor();
        propertyDescriptor.setDisplayer(new LiveDataPropertyDescriptor.DisplayerDescriptor(NUMBER));
        propertyDescriptor.setFilter(new LiveDataPropertyDescriptor.FilterDescriptor(NUMBER));
        return propertyDescriptor;
    }

    @Override
    protected Stream<String> getConstraintQuery(LiveDataQuery.Constraint constraint)
    {
        String stringValue = String.valueOf(constraint.getValue());
        if (StringUtils.isNotBlank(stringValue)) {
            // Make sure that the value is indeed a number
            constraint.setValue(Long.parseLong(stringValue));
            return super.getConstraintQuery(constraint);
        } else {
            return Stream.empty();
        }
    }
}
