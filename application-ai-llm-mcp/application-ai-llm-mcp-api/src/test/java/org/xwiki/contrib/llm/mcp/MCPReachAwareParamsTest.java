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
package org.xwiki.contrib.llm.mcp;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link MCPReachAwareParams}: eager variant building, variant selection and the always-superset
 * parsing invariant.
 *
 * @version $Id$
 */
class MCPReachAwareParamsTest
{
    private static final MCPToolSupport CROSS_WIKI_VARIANT = MCPToolSupport.builder()
        .string("reference", "The reference, optionally wiki-prefixed.")
        .string("wiki", "The wiki id.")
        .build();

    private static final MCPToolSupport LOCAL_VARIANT = MCPToolSupport.builder()
        .string("reference", "The reference.")
        .build();

    private static MCPToolSupport variant(boolean crossWiki)
    {
        return crossWiki ? CROSS_WIKI_VARIANT : LOCAL_VARIANT;
    }

    @Test
    void ofBuildsEachVariantExactlyOnce()
    {
        AtomicInteger crossWikiBuilds = new AtomicInteger();
        AtomicInteger localBuilds = new AtomicInteger();
        Function<Boolean, MCPToolSupport> countingBuilder = crossWiki -> {
            if (crossWiki) {
                crossWikiBuilds.incrementAndGet();
            } else {
                localBuilds.incrementAndGet();
            }
            return variant(crossWiki);
        };

        MCPReachAwareParams params = MCPReachAwareParams.of(countingBuilder);

        assertEquals(1, crossWikiBuilds.get());
        assertEquals(1, localBuilds.get());
        params.advertised(true);
        params.advertised(false);
        params.parser();
        assertEquals(1, crossWikiBuilds.get());
        assertEquals(1, localBuilds.get());
    }

    @Test
    void advertisedSelectsTheVariantMatchingReach()
    {
        MCPReachAwareParams params = MCPReachAwareParams.of(MCPReachAwareParamsTest::variant);

        assertSame(CROSS_WIKI_VARIANT, params.advertised(true));
        assertSame(LOCAL_VARIANT, params.advertised(false));
    }

    @Test
    void parserIsAlwaysTheCrossWikiVariant()
    {
        MCPReachAwareParams params = MCPReachAwareParams.of(MCPReachAwareParamsTest::variant);

        assertSame(CROSS_WIKI_VARIANT, params.parser());
        assertSame(params.advertised(true), params.parser());
    }

    @Test
    void ofRejectsNullBuilder()
    {
        assertThrows(NullPointerException.class, () -> MCPReachAwareParams.of(null));
    }

    @Test
    void ofRejectsBuilderReturningNullCrossWikiVariant()
    {
        NullPointerException failure = assertThrows(NullPointerException.class,
            () -> MCPReachAwareParams.of(crossWiki -> crossWiki ? null : LOCAL_VARIANT));

        assertEquals("The parameter-set builder returned null for the cross-wiki variant.", failure.getMessage());
    }

    @Test
    void ofRejectsBuilderReturningNullLocalVariant()
    {
        NullPointerException failure = assertThrows(NullPointerException.class,
            () -> MCPReachAwareParams.of(crossWiki -> crossWiki ? CROSS_WIKI_VARIANT : null));

        assertEquals("The parameter-set builder returned null for the local variant.", failure.getMessage());
    }
}
