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
 * The per-wiki state of one MCP tool, as surfaced to the MCP admin tool tree: the tool id, its category,
 * a one-line summary, whether it is mandatory, whether it performs writes and whether it is currently
 * enabled for the wiki.
 *
 * @version $Id$
 * @since 0.9
 */
@Unstable
public class MCPToolState
{
    private final String id;

    private final String category;

    private final String summary;

    private final boolean mandatory;

    private final boolean write;

    private final boolean enabled;

    /**
     * @param id the tool id (component hint and MCP tool name)
     * @param category the display category the tool groups under
     * @param summary the one-line tool summary
     * @param mandatory whether the tool is always enabled regardless of configuration
     * @param write whether the tool can modify wiki content
     * @param enabled whether the tool is currently enabled for the wiki
     * @since 0.9
     */
    public MCPToolState(String id, String category, String summary, boolean mandatory, boolean write,
        boolean enabled)
    {
        this.id = id;
        this.category = category;
        this.summary = summary;
        this.mandatory = mandatory;
        this.write = write;
        this.enabled = enabled;
    }

    /**
     * @return the tool id (component hint and MCP tool name)
     * @since 0.9
     */
    public String getId()
    {
        return this.id;
    }

    /**
     * @return the display category the tool groups under
     * @since 0.9
     */
    public String getCategory()
    {
        return this.category;
    }

    /**
     * @return the one-line tool summary
     * @since 0.9
     */
    public String getSummary()
    {
        return this.summary;
    }

    /**
     * @return whether the tool is always enabled regardless of configuration
     * @since 0.9
     */
    public boolean isMandatory()
    {
        return this.mandatory;
    }

    /**
     * @return whether the tool can modify wiki content
     * @since 0.9
     */
    public boolean isWrite()
    {
        return this.write;
    }

    /**
     * @return whether the tool is currently enabled for the wiki
     * @since 0.9
     */
    public boolean isEnabled()
    {
        return this.enabled;
    }
}
