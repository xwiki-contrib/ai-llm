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

import java.util.HashMap;
import java.util.Map;

import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIConfig;

import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.PropertyInterface;

/**
 * Builds a GPTAPIConfig object from a BaseObject that stores the configuration.
 *
 * @version $Id$
 * @since 0.5
 */
@Component(roles = GPTAPIConfigBuilder.class)
@Singleton
public class GPTAPIConfigBuilder
{
    /**
     * Build a GPTAPIConfig object from a BaseObject that stores the configuration.
     *
     * @param configObject the BaseObject that stores the configuration
     * @return a GPTAPIConfig object
     */
    public GPTAPIConfig build(BaseObject configObject)
    {
        Map<String, Object> configObjMap = new HashMap<>();

        for (String fieldName : configObject.getPropertyList()) {
            PropertyInterface field = configObject.getField(fieldName);
            if (field instanceof BaseProperty) {
                configObjMap.put(fieldName, ((BaseProperty<?>) field).getValue());
            }
        }

        return new GPTAPIConfig(configObjMap);
    }
}
