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

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.xwiki.stability.Unstable;

/**
 * Parameters for a chat request.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
public class ChatRequestParameters
{
    private final double temperature;

    /**
     * Creates new chat request parameters.
     *
     * @param temperature the temperature to use for the completion, a higher temperature results in more randomness
     *     but also more creativity
     */
    public ChatRequestParameters(double temperature)
    {
        this.temperature = temperature;
    }

    /**
     * @return the temperature to use for the completion, a higher temperature results in more randomness but also more
     *     creativity
     */
    public double getTemperature()
    {
        return temperature;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ChatRequestParameters that = (ChatRequestParameters) o;

        return new EqualsBuilder().append(temperature, that.temperature).isEquals();
    }

    @Override
    public int hashCode()
    {
        return new HashCodeBuilder(17, 37).append(temperature).toHashCode();
    }
}
