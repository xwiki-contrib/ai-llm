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
package org.xwiki.contrib.llm.mcp.script;

import org.xwiki.stability.Unstable;

/**
 * Outcome of a bulk MCP enabled-state apply: how many wikis had their flag written and how many were
 * skipped (e.g. for missing admin rights or a failed write).
 *
 * @version $Id$
 * @since 0.9
 */
@Unstable
public class BulkResult
{
    private final int changed;

    private final int skipped;

    /**
     * @param changed the number of wikis whose flag was actually written
     * @param skipped the number of wikis that were skipped
     */
    public BulkResult(int changed, int skipped)
    {
        this.changed = changed;
        this.skipped = skipped;
    }

    /**
     * @return the number of wikis whose flag was actually written
     * @since 0.9
     */
    public int getChanged()
    {
        return this.changed;
    }

    /**
     * @return the number of wikis that were skipped
     * @since 0.9
     */
    public int getSkipped()
    {
        return this.skipped;
    }
}
