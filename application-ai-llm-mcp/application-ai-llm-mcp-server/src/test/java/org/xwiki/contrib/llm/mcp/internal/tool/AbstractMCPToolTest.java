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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.WikiReference;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Base class for MCP tool tests: calling the tool under test and shared reference fakes. Deliberately
 * unannotated - the concrete class carries {@code @ComponentTest}/{@code @OldcoreTest} and the
 * {@code @InjectMockComponents} field.
 *
 * @version $Id$
 */
public abstract class AbstractMCPToolTest
{
    /**
     * @return the tool under test
     */
    protected abstract MCPTool getTool();

    /**
     * Calls the tool under test with the given arguments.
     *
     * @param args the call arguments
     * @return the tool result
     */
    protected McpSchema.CallToolResult call(Map<String, Object> args)
    {
        return getTool().execute(MCPToolTestUtils.request(getTool().getToolDefinition().name(), args));
    }

    /**
     * Calls the tool under test and returns the text of the result's first content block.
     *
     * @param args the call arguments
     * @return the result text
     */
    protected String callText(Map<String, Object> args)
    {
        return MCPToolTestUtils.textOf(call(args));
    }

    /**
     * @return the advertised description of the tool's {@code reference} parameter
     */
    protected String referenceDescription()
    {
        Map<?, ?> properties = (Map<?, ?>) getTool().getToolDefinition().inputSchema().get("properties");
        Map<?, ?> reference = (Map<?, ?>) properties.get("reference");
        return (String) reference.get("description");
    }

    /**
     * Mirrors the {@code current} resolver with a wiki parameter: the dotted full name is parsed into spaces
     * plus a page name, landing in the given wiki (the reverse of the serializer answer).
     *
     * @param fullName the dotted local full name
     * @param wikiParameter the {@link WikiReference} the resolver was called with
     * @return the parsed document reference
     */
    protected static DocumentReference parseRef(String fullName, Object wikiParameter)
    {
        String wiki = ((WikiReference) wikiParameter).getName();
        List<String> parts = List.of(fullName.split("\\.", -1));
        return new DocumentReference(wiki, parts.subList(0, parts.size() - 1), parts.get(parts.size() - 1));
    }

    /**
     * Serializes an entity reference into its dotted local name, dropping the wiki part.
     *
     * @param reference the reference to serialize
     * @return the dotted local name
     */
    protected static String localName(EntityReference reference)
    {
        List<String> parts = new ArrayList<>();
        EntityReference current = reference;
        while (current != null && current.getType() != EntityType.WIKI) {
            parts.add(0, current.getName());
            current = current.getParent();
        }
        return String.join(".", parts);
    }
}
