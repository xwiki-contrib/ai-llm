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

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MCPToolSupport}: the declarative parameter descriptor side and the public static
 * coercion/formatting helpers.
 *
 * @version $Id$
 */
class MCPToolSupportTest
{
    private static final String REF = "reference";

    private static final String LIMIT = "limit";

    private static final String HIDDEN = "hidden";

    private static final String TAGS = "tags";

    private static final String TYPE = "type";

    private static final String DESCRIPTION = "description";

    private static final String PROPERTIES = "properties";

    private static final String REQUIRED = "required";

    private static final String TAGS_DESCRIPTION = "The tags.";

    private static final String STRING_ARRAY_ERROR = "Error: 'tags' parameter must be an array of strings.";

    private static final MCPToolSupport PARAMS = MCPToolSupport.builder()
        .requiredString(REF, "The reference.")
        .integer(LIMIT, "The limit.")
        .bool(HIDDEN, "Include hidden.")
        .stringArray(TAGS, TAGS_DESCRIPTION)
        .build();

    @Test
    void inputSchemaContainsDeclaredParametersWithTypesAndDescriptions()
    {
        Map<String, Object> schema = PARAMS.inputSchema();

        assertEquals("object", schema.get(TYPE));
        Map<?, ?> properties = (Map<?, ?>) schema.get(PROPERTIES);
        assertEquals(Map.of(TYPE, "string", DESCRIPTION, "The reference."), properties.get(REF));
        assertEquals(Map.of(TYPE, "integer", DESCRIPTION, "The limit."), properties.get(LIMIT));
        assertEquals(Map.of(TYPE, "boolean", DESCRIPTION, "Include hidden."), properties.get(HIDDEN));
        assertEquals(Map.of(TYPE, "array", "items", Map.of(TYPE, "string"), DESCRIPTION, TAGS_DESCRIPTION),
            properties.get(TAGS));
        assertEquals(List.of(REF), schema.get(REQUIRED));
    }

    @Test
    void inputSchemaPreservesDeclarationOrder()
    {
        Map<String, Object> schema = PARAMS.inputSchema();

        Map<?, ?> properties = (Map<?, ?>) schema.get(PROPERTIES);
        assertEquals(List.of(REF, LIMIT, HIDDEN, TAGS), List.copyOf(properties.keySet()));
    }

    @Test
    void inputSchemaMergesExtraHandBuiltProperties()
    {
        Map<String, Object> arrayProperty = Map.of(TYPE, "array", DESCRIPTION, "Bespoke edits.");

        Map<String, Object> schema = PARAMS.inputSchema(Map.of("edits", arrayProperty));

        Map<?, ?> properties = (Map<?, ?>) schema.get(PROPERTIES);
        assertEquals(arrayProperty, properties.get("edits"));
        assertTrue(properties.containsKey(REF), "Declared parameters must be kept");
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
        assertThrows(IllegalStateException.class, () -> PARAMS.stringList(Map.of(), REF));
    }

    @Test
    void stringListReadsDeclaredStringArrayParameter()
    {
        Map<String, Object> args = Map.of(TAGS, List.of("alpha", "beta"));

        assertEquals(List.of("alpha", "beta"), PARAMS.stringList(args, TAGS));
    }

    @Test
    void stringListTrimsElements()
    {
        Map<String, Object> args = Map.of(TAGS, List.of(" a ", "b"));

        assertEquals(List.of("a", "b"), PARAMS.stringList(args, TAGS));
    }

    @Test
    void stringListDropsElementsThatAreBlankAfterTrimming()
    {
        Map<String, Object> args = Map.of(TAGS, List.of("a", "  "));

        assertEquals(List.of("a"), PARAMS.stringList(args, TAGS));
    }

    @Test
    void stringListReturnsEmptyListForAllBlankElements()
    {
        Map<String, Object> args = Map.of(TAGS, List.of(" ", "\t", ""));

        assertEquals(List.of(), PARAMS.stringList(args, TAGS));
    }

    @Test
    void stringListReturnsNullForAbsentParameter()
    {
        assertNull(PARAMS.stringList(Map.of(), TAGS));
    }

    @Test
    void stringListRejectsNonArrayValueWithAgentFacingError()
    {
        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> PARAMS.stringList(Map.of(TAGS, "alpha"), TAGS));
        assertEquals(STRING_ARRAY_ERROR, thrown.getMessage());
    }

    @Test
    void stringListRejectsNonStringElementWithAgentFacingError()
    {
        Map<String, Object> args = Map.of(TAGS, List.of("alpha", 7));

        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> PARAMS.stringList(args, TAGS));
        assertEquals(STRING_ARRAY_ERROR, thrown.getMessage());
    }

    @Test
    void typeMismatchInArgumentsThrowsAgentFacingError()
    {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> PARAMS.integer(Map.of(LIMIT, "lots"), LIMIT));
        assertTrue(thrown.getMessage().contains("must be an integer"), thrown.getMessage());
    }

    @Test
    void booleanValueAcceptsBooleansAndBooleanStrings()
    {
        assertTrue(MCPToolSupport.booleanValue(Boolean.TRUE, HIDDEN));
        assertFalse(MCPToolSupport.booleanValue(Boolean.FALSE, HIDDEN));
        assertTrue(MCPToolSupport.booleanValue("TRUE", HIDDEN));
        assertFalse(MCPToolSupport.booleanValue("False", HIDDEN));
        assertTrue(MCPToolSupport.booleanValue(" true ", HIDDEN));
    }

    @Test
    void booleanValueDefaultsToFalseWhenNull()
    {
        assertFalse(MCPToolSupport.booleanValue(null, HIDDEN));
    }

    @Test
    void booleanValueRejectsNonBooleanValuesWithAgentFacingError()
    {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> MCPToolSupport.booleanValue("yes", HIDDEN));
        assertEquals("Error: 'hidden' parameter must be a boolean.", thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> MCPToolSupport.booleanValue(1, HIDDEN));
    }

    @Test
    void isoInstantFormatsDateAsIsoUtcInstant()
    {
        Date date = Date.from(Instant.parse("2026-07-08T12:34:56Z"));

        assertEquals("2026-07-08T12:34:56Z", MCPToolSupport.isoInstant(date));
    }

    @Test
    void isoInstantReturnsNullForNullOrNonDateValue()
    {
        assertNull(MCPToolSupport.isoInstant(null));
        assertNull(MCPToolSupport.isoInstant("2026-07-08T12:34:56Z"));
    }

    @Test
    void stripLineBreaksRemovesNewlineAndUnicodeSeparatorCharacters()
    {
        assertEquals("abcde", MCPToolSupport.stripLineBreaks("a\nb\rc\u2028d\u2029e"));
    }

    @Test
    void stripLineBreaksReturnsNullForNullValue()
    {
        assertNull(MCPToolSupport.stripLineBreaks(null));
    }
}
