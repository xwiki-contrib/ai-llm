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

import org.xwiki.component.annotation.Role;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.stability.Unstable;

import com.xpn.xwiki.objects.BaseObject;

/**
 * Builds an {@link AuthorizationManager} from an object that contains its configuration.
 *
 * @version $Id$
 * @since 0.1
 */
@Role
@Unstable
public interface AuthorizationManagerBuilder
{
    /**
     * Builds an {@link AuthorizationManager} from the given object.
     *
     * @param configurationObject the object to build the authorization manager from
     * @return the built authorization manager
     */
    AuthorizationManager build(BaseObject configurationObject) throws IndexException;

    /**
     * @return the class reference of the objects that this builder can build authorization managers from, use
     * {@link Collection#XCLASS_REFERENCE} if this builder doesn't require any specific configuration.
     */
    EntityReference getConfigurationClassReference();

    /**
     * @return the reference of the sheet to display the object of the class, or {@code null} if no specific sheet
     * should be displayed for the configuration
     */
    EntityReference getConfigurationSheetReference();

    /**
     * @return the type of the configuration that this builder can build authorization managers from, this type should
     * be serializable and deserializable by Jackson, or {@code null} if this builder doesn't require any specific
     * configuration.
     */
    Class<?> getConfigurationType();

    /**
     * @param object the object to get the configuration from
     * @return the configuration of the authorization manager, an instance of the type returned by
     * {@link #getConfigurationType()}.
     */
    Object getConfiguration(BaseObject object);

    /**
     * @param object the object to set the configuration to
     * @param configuration the configuration to set, an instance of the type returned by {@link #getConfigurationType()}.
     */
    void setConfiguration(BaseObject object, Object configuration);

}
