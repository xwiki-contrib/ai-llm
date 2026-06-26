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
package org.xwiki.contrib.llm.mcp.internal;

/**
 * Thrown when an MCP tool's request to reach a document is denied, either by the regular rights check or by
 * the per-wiki space filter. The message is agent-facing: it is meant to be returned to the calling agent as
 * the tool error text.
 *
 * @version $Id$
 * @since 0.9
 */
public class MCPAccessDeniedException extends Exception
{
    /**
     * @param message the agent-facing reason the access was denied
     */
    public MCPAccessDeniedException(String message)
    {
        super(message);
    }
}
