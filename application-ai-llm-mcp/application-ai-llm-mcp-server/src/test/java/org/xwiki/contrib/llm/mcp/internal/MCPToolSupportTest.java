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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the declarative parameter descriptor side of {@link MCPToolSupport} (the static coercion
 * helpers are covered through the tool test classes).
 *
 * @version $Id$
 */
class MCPToolSupportTest
{
    private static final String REF = "reference";

    private static final String LIMIT = "limit";

    private static final String HIDDEN = "hidden";

    private static final String TYPE = "type";

    private static final String DESCRIPTION = "description";

    private static final MCPToolSupport PARAMS = MCPToolSupport.builder()
        .requiredString(REF, "The reference.")
        .integer(LIMIT, "The limit.")
        .bool(HIDDEN, "Include hidden.")
        .build();

    @Test
    void inputSchemaContainsDeclaredParametersWithTypesAndDescriptions()
    {
        McpSchema.JsonSchema schema = PARAMS.inputSchema();

        assertEquals("object", schema.type());
        assertEquals(Map.of(TYPE, "string", DESCRIPTION, "The reference."), schema.properties().get(REF));
        assertEquals(Map.of(TYPE, "integer", DESCRIPTION, "The limit."), schema.properties().get(LIMIT));
        assertEquals(Map.of(TYPE, "boolean", DESCRIPTION, "Include hidden."), schema.properties().get(HIDDEN));
        assertEquals(List.of(REF), schema.required());
    }

    @Test
    void inputSchemaPreservesDeclarationOrder()
    {
        McpSchema.JsonSchema schema = PARAMS.inputSchema();

        assertEquals(List.of(REF, LIMIT, HIDDEN), List.copyOf(schema.properties().keySet()));
    }

    @Test
    void inputSchemaMergesExtraHandBuiltProperties()
    {
        Map<String, Object> arrayProperty = Map.of(TYPE, "array", DESCRIPTION, "Bespoke edits.");

        McpSchema.JsonSchema schema = PARAMS.inputSchema(Map.of("edits", arrayProperty));

        assertEquals(arrayProperty, schema.properties().get("edits"));
        assertTrue(schema.properties().containsKey(REF), "Declared parameters must be kept");
    }

    @Test
    void accessorsReadDeclaredParameters()
    {
        Map<String, Object> args = Map.of(REF, " Some.Page ", LIMIT, 5, HIDDEN, true);

        assertEquals("Some.Page", PARAMS.string(args, REF));
        assertEquals(5, PARAMS.integer(args, LIMIT, 10));
        assertTrue(PARAMS.bool(args, HIDDEN));
    }

    @Test
    void accessorsApplyDefaultsForAbsentParameters()
    {
        Map<String, Object> args = Map.of();

        assertNull(PARAMS.string(args, REF));
        assertNull(PARAMS.integer(args, LIMIT));
        assertEquals(10, PARAMS.integer(args, LIMIT, 10));
        assertFalse(PARAMS.bool(args, HIDDEN));
    }

    @Test
    void requireStringEnforcesPresenceWithAgentFacingError()
    {
        assertEquals("Some.Page", PARAMS.requireString(Map.of(REF, "Some.Page"), REF));

        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> PARAMS.requireString(Map.of(), REF));
        assertEquals("Error: 'reference' parameter is required.", thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PARAMS.requireString(Map.of(REF, "  "), REF));
    }

    @Test
    void extraSchemaPropertyCollidingWithDeclaredParameterIsAProgrammerError()
    {
        Map<String, Object> colliding = Map.of(TYPE, "array", DESCRIPTION, "Collides with the scalar.");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> PARAMS.inputSchema(Map.of(REF, colliding)));
        assertTrue(thrown.getMessage().contains(REF), thrown.getMessage());
    }

    @Test
    void readingUndeclaredParameterIsAProgrammerError()
    {
        IllegalStateException thrown =
            assertThrows(IllegalStateException.class, () -> PARAMS.string(Map.of(), "unheardOf"));
        assertTrue(thrown.getMessage().contains("unheardOf"), thrown.getMessage());
    }

    @Test
    void readingParameterWithWrongTypeAccessorIsAProgrammerError()
    {
        assertThrows(IllegalStateException.class, () -> PARAMS.string(Map.of(), LIMIT));
        assertThrows(IllegalStateException.class, () -> PARAMS.integer(Map.of(), REF));
        assertThrows(IllegalStateException.class, () -> PARAMS.bool(Map.of(), REF));
    }

    @Test
    void typeMismatchInArgumentsThrowsAgentFacingError()
    {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> PARAMS.integer(Map.of(LIMIT, "lots"), LIMIT));
        assertTrue(thrown.getMessage().contains("must be an integer"), thrown.getMessage());
    }
}
