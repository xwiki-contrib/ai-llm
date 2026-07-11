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
package org.xwiki.contrib.llm.mcp.internal.tool;

import org.xwiki.contrib.llm.mcp.MCPToolSupport;

/**
 * The shared fragment guard of the line-oriented tools: one place holding the per-fragment cap and the
 * neutralize-and-clamp step every wiki-authored fragment goes through before landing in a single output
 * line, so the tools cannot drift apart on either the cap or the ellipsis convention.
 *
 * @version $Id$
 * @since 0.9.1
 */
final class MCPTextGuards
{
    /**
     * Cap on one neutralized wiki-authored fragment (a name, reference, title, stored value, list value,
     * date format, validation regexp or message), so a single crafted fragment cannot dominate an output
     * line; a longer fragment is cut and marked with {@link #ELLIPSIS}.
     */
    static final int MAX_FRAGMENT_CHARS = 200;

    /**
     * Marks a fragment cut at {@link #MAX_FRAGMENT_CHARS} (the horizontal ellipsis, U+2026).
     */
    static final String ELLIPSIS = "…";

    private MCPTextGuards()
    {
    }

    /**
     * @param value the wiki-authored or agent-supplied fragment, possibly {@code null}
     * @return the fragment with the newline/control family removed (so it cannot forge a line of a tool's
     *     output grammar) and cut at {@link #MAX_FRAGMENT_CHARS} with an ellipsis mark (so it cannot
     *     dominate one); {@code null} stays {@code null}
     */
    static String fragment(String value)
    {
        String stripped = MCPToolSupport.stripLineBreaks(value);
        if (stripped != null && stripped.length() > MAX_FRAGMENT_CHARS) {
            stripped = stripped.substring(0, MAX_FRAGMENT_CHARS) + ELLIPSIS;
        }
        return stripped;
    }
}
