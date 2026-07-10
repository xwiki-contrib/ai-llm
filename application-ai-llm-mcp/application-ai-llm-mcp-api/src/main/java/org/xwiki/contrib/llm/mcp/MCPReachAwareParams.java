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

import java.util.Objects;
import java.util.function.Function;

import org.xwiki.stability.Unstable;

/**
 * Immutable holder of the two declared-parameter variants of a tool whose schema depends on whether the
 * endpoint has cross-wiki reach: the cross-wiki variant (the superset, surfacing the cross-wiki capability)
 * and the local variant (with every cross-wiki mention dropped). Centralizing the pair enforces the invariant
 * that argument parsing always uses the superset ({@link #parser()}) while only the advertised schema varies
 * ({@link #advertised(boolean)}), instead of each tool re-implementing that split by convention.
 *
 * @version $Id$
 * @since 0.9.1
 */
@Unstable
public final class MCPReachAwareParams
{
    /**
     * The shared schema-text sentence appended to reference-parameter descriptions by cross-wiki-capable
     * tools, in their cross-wiki variant only.
     *
     * @since 0.9.1
     */
    public static final String CROSS_WIKI_REFERENCE_SENTENCE =
        " A wiki-id prefix reaches another wiki (see list_wikis).";

    /**
     * The cross-wiki variant: the superset used both for parsing and as the schema advertised by a
     * reach-enabled endpoint.
     */
    private final MCPToolSupport crossWiki;

    /**
     * The local variant: the schema advertised by a reach-off endpoint, never used for parsing.
     */
    private final MCPToolSupport local;

    private MCPReachAwareParams(MCPToolSupport crossWiki, MCPToolSupport local)
    {
        this.crossWiki = crossWiki;
        this.local = local;
    }

    /**
     * Builds the holder from a tool's parameter-set builder, eagerly building both variants: the cross-wiki
     * variant from {@code paramsBuilder.apply(true)} and the local variant from
     * {@code paramsBuilder.apply(false)}. Eager building keeps the failure mode of a broken declaration at
     * class initialization, where tools hold their holder in a {@code static final} field.
     *
     * @param paramsBuilder builds one parameter-set variant; its argument is whether the variant advertises
     *     cross-wiki reach
     * @return the holder of both variants
     * @throws NullPointerException if the builder is {@code null} or returns a {@code null} variant
     * @since 0.9.1
     */
    public static MCPReachAwareParams of(Function<Boolean, MCPToolSupport> paramsBuilder)
    {
        Objects.requireNonNull(paramsBuilder, "The parameter-set builder must not be null.");
        MCPToolSupport crossWiki = Objects.requireNonNull(paramsBuilder.apply(true),
            "The parameter-set builder returned null for the cross-wiki variant.");
        MCPToolSupport local = Objects.requireNonNull(paramsBuilder.apply(false),
            "The parameter-set builder returned null for the local variant.");
        return new MCPReachAwareParams(crossWiki, local);
    }

    /**
     * The variant to expose in the tool definition: the cross-wiki variant when the endpoint has cross-wiki
     * reach, the local variant otherwise.
     *
     * @param reachEnabled whether the endpoint has cross-wiki reach
     * @return the parameter set to advertise
     * @since 0.9.1
     */
    public MCPToolSupport advertised(boolean reachEnabled)
    {
        return reachEnabled ? this.crossWiki : this.local;
    }

    /**
     * The variant to parse arguments with: always the cross-wiki variant, regardless of the advertised
     * schema. Parsing with the superset means a stray cross-wiki argument sent to a reach-off endpoint still
     * parses and then hits the reach gate's curated refusal, instead of being silently ignored.
     *
     * @return the parameter set to parse arguments with
     * @since 0.9.1
     */
    public MCPToolSupport parser()
    {
        return this.crossWiki;
    }
}
