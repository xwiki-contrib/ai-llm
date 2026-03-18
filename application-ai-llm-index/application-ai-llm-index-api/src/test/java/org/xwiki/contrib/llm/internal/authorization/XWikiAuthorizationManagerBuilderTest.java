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
package org.xwiki.contrib.llm.internal.authorization;

import org.junit.jupiter.api.Test;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.objects.BaseObject;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Component test for {@link XWikiAuthorizationManagerBuilder}.
 *
 * @version $Id$
 */
@ComponentTest
class XWikiAuthorizationManagerBuilderTest
{
    @InjectMockComponents
    private XWikiAuthorizationManagerBuilder builder;

    @Test
    void getConfigurationTypeIsNull()
    {
        assertNull(this.builder.getConfigurationType());
    }

    @Test
    void getConfigurationReturnsNull()
    {
        BaseObject baseObject = mock();
        assertNull(this.builder.getConfiguration(baseObject));
        verifyNoInteractions(baseObject);
    }

    @Test
    void setConfigurationDoesNothing()
    {
        BaseObject baseObject = mock();
        this.builder.setConfiguration(baseObject, new Object());
        verifyNoInteractions(baseObject);
    }
}
