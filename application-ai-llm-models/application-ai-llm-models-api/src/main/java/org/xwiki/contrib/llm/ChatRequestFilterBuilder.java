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
import org.xwiki.model.reference.EntityReference;
import org.xwiki.stability.Unstable;

import com.xpn.xwiki.objects.BaseObject;

/**
 * Builds a {@link ChatRequestFilter} from an object that contains its configuration.
 *
 * @version $Id$
 * @since 0.3
 */
@Role
@Unstable
public interface ChatRequestFilterBuilder
{
    /**
     * Builds a {@link ChatRequestFilter} from the given object.
     *
     * @param object the object to build the filter from
     * @return the built filter(s)
     */
    List<ChatRequestFilter> build(BaseObject object);

    /**
     * @return the class reference of the objects that this builder can build filters from
     */
    EntityReference getClassReference();

    /**
     * @return the reference of the sheet to display the object of the class
     */
    EntityReference getSheetReference();
}
