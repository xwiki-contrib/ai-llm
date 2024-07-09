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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.test.TestEnvironment;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InternalGPTAPIServer}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList(TestEnvironment.class)
class InternalGPTAPIServerTest
{
    @InjectComponentManager
    private ComponentManager componentManager;

    @Test
    void embed() throws Exception
    {
        InternalGPTAPIServer server = new InternalGPTAPIServer(mock(), mock(), mock(), this.componentManager);
        List<double[]> embed = server.embed("sentence-transformers/all-MiniLM-L6-v2", List.of("XWiki is great!"));

        assertEquals(1, embed.size());
        assertEquals(384, embed.get(0).length);
        assertTrue(Arrays.stream(embed.get(0)).anyMatch(x -> x != 0));
    }
}
