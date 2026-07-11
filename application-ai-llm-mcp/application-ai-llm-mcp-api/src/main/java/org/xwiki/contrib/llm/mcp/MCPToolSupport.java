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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.stability.Unstable;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tool-side support shared by the MCP tools: argument coercion, result building, and a
 * declarative parameter descriptor, so that all tools keep identical argument semantics and
 * error-message phrasing, and so that a new tool cannot drift by re-implementing them.
 *
 * <p>The static helpers coerce raw argument values and build results. An <em>instance</em>, created
 * with {@link #builder()}, is a tool's declared flat parameter set: it generates the MCP input
 * schema ({@link #inputSchema()}) and serves the typed accessors ({@link #string},
 * {@link #integer}, {@link #bool}, {@link #stringList}, {@link #stringMap}) from the same
 * declaration, so the advertised schema and the parsing code cannot disagree. Nested object
 * parameters are out of scope by design — a tool declares its scalars, flat string arrays and flat
 * string maps here and merges any bespoke schema parts via {@link #inputSchema(Map)}.</p>
 *
 * <p>All parsing helpers throw {@link IllegalArgumentException} with an agent-facing message on a
 * type mismatch; tools convert that uniformly into an error result. Accessing a parameter that was
 * not declared (or declared with another type) throws {@link IllegalStateException} — a programmer
 * error meant to be caught by the tool's tests.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Unstable
public final class MCPToolSupport
{
    /**
     * Prefix shared by the agent-facing parameter error messages, exposed so tools can build their
     * own parameter errors with consistent phrasing.
     *
     * @since 0.9.1
     */
    public static final String ERROR_PREFIX = "Error: '";

    private static final String REQUIRED_PARAM_ERROR_SUFFIX = "' parameter is required.";

    private static final String STRING_PARAM_ERROR_SUFFIX = "' parameter must be a string.";

    private static final String INTEGER_PARAM_ERROR_SUFFIX = "' parameter must be an integer.";

    private static final String BOOLEAN_PARAM_ERROR_SUFFIX = "' parameter must be a boolean.";

    private static final String STRING_ARRAY_PARAM_ERROR_SUFFIX = "' parameter must be an array of strings.";

    private static final String STRING_MAP_PARAM_ERROR_SUFFIX = "' parameter must be an object mapping names "
        + "to string values (write every value as a string, e.g. \"1\" rather than 1).";

    private static final String TYPE_KEY = "type";

    private static final String DESCRIPTION_KEY = "description";

    private static final String OBJECT_TYPE = "object";

    private static final String STRING_TYPE = "string";

    /**
     * Matches the full newline/control family a single agent-facing line must never contain: every Unicode
     * control character ({@code \p{Cc}}, covering CR, LF, TAB, VT, FF and NEL) plus the line separator
     * ({@code \p{Zl}}, U+2028) and paragraph separator ({@code \p{Zp}}, U+2029). Java's {@code \p{Cc}} is C0/C1
     * only and {@code \s} is ASCII-only, so both miss U+2028/U+2029; naming the categories explicitly closes
     * that blind spot for every tool that renders untrusted page text into a line grammar.
     */
    private static final Pattern LINE_BREAK_CHARS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    /**
     * The declared parameters, in declaration order (preserved so the advertised schema lists
     * parameters in the order the tool author chose).
     */
    private final Map<String, DeclaredParam> params;

    /**
     * The names of the required parameters, in declaration order.
     */
    private final List<String> requiredNames;

    private MCPToolSupport(Builder builder)
    {
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(builder.params));
        this.requiredNames = List.copyOf(builder.required);
    }

    /**
     * Starts the declaration of a tool's flat parameter set.
     *
     * @return a new builder
     * @since 0.9.1
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Reads an optional string argument, trimming it and mapping blank to {@code null}.
     *
     * @param args the tool call arguments
     * @param key the argument name
     * @return the trimmed value, or {@code null} when absent or blank
     * @throws IllegalArgumentException if the value is present but not a string
     */
    private static String optionalString(Map<String, Object> args, String key)
    {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException(ERROR_PREFIX + key + STRING_PARAM_ERROR_SUFFIX);
        }
        return StringUtils.trimToNull(str);
    }

    /**
     * Reads an optional integer argument, accepting an integral JSON number or a numeric string. A
     * fractional number is rejected rather than silently truncated.
     *
     * @param args the tool call arguments
     * @param key the argument name
     * @return the value, or {@code null} when absent
     * @throws IllegalArgumentException if the value is present but not an integer
     */
    private static Integer optionalInt(Map<String, Object> args, String key)
    {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            int intValue = number.intValue();
            if (number.doubleValue() != intValue) {
                throw new IllegalArgumentException(ERROR_PREFIX + key + INTEGER_PARAM_ERROR_SUFFIX);
            }
            return intValue;
        }
        if (value instanceof String str && StringUtils.isNotBlank(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(ERROR_PREFIX + key + INTEGER_PARAM_ERROR_SUFFIX, e);
            }
        }
        throw new IllegalArgumentException(ERROR_PREFIX + key + INTEGER_PARAM_ERROR_SUFFIX);
    }

    /**
     * Reads an optional boolean argument, accepting a JSON boolean or a boolean string and
     * defaulting to {@code false} when absent.
     *
     * @param args the tool call arguments
     * @param key the argument name
     * @return the value, or {@code false} when absent
     * @throws IllegalArgumentException if the value is present but not a boolean
     */
    private static boolean booleanParam(Map<String, Object> args, String key)
    {
        return booleanValue(args.get(key), key);
    }

    /**
     * Coerces an already-extracted value to a boolean, accepting a JSON boolean or the strings
     * {@code "true"}/{@code "false"} (case-insensitive) and defaulting to {@code false} when
     * {@code null}. Any other string is rejected rather than silently coerced to {@code false}, so a
     * caller sending e.g. {@code "yes"} learns about it instead of getting the opposite behavior.
     * Used directly for values nested inside structured arguments (e.g. a boolean field of an
     * object element in a hand-built array parameter).
     *
     * @param value the raw value, possibly {@code null}
     * @param key the argument name, used in the error message
     * @return the coerced value, or {@code false} when {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is present but not a boolean
     * @since 0.9.1
     */
    public static boolean booleanValue(Object value, String key)
    {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            if ("true".equalsIgnoreCase(str.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(str.trim())) {
                return false;
            }
        }
        throw new IllegalArgumentException(ERROR_PREFIX + key + BOOLEAN_PARAM_ERROR_SUFFIX);
    }

    /**
     * Formats a date value (as read from an untyped source such as a Solr document) as an ISO-8601
     * UTC instant, the unambiguous form for agent-facing output.
     *
     * @param value the raw value, possibly {@code null} or not a date
     * @return the ISO-8601 instant, or {@code null} when the value is not a {@link Date}
     * @since 0.9.1
     */
    public static String isoInstant(Object value)
    {
        return value instanceof Date date ? date.toInstant().toString() : null;
    }

    /**
     * Removes every newline/control-family character (see {@link #LINE_BREAK_CHARS}) from a string, so
     * untrusted page text cannot inject a line break into a single-line agent-facing rendering (forging a fake
     * row, banner or reference). Only the break characters are removed; all other characters, including
     * reference-syntax punctuation, are left intact so a value stays usable as-is.
     *
     * @param value the raw value, possibly {@code null}
     * @return the value with all newline/control-family characters removed, or {@code null} when {@code value}
     *     is {@code null}
     * @since 0.9.1
     */
    public static String stripLineBreaks(String value)
    {
        return value == null ? null : LINE_BREAK_CHARS.matcher(value).replaceAll("");
    }

    /**
     * Builds a successful text result.
     *
     * @param message the result text
     * @return the tool result
     * @since 0.9.1
     */
    public static McpSchema.CallToolResult result(String message)
    {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .build();
    }

    /**
     * Builds an error text result ({@code isError=true}).
     *
     * @param message the agent-facing error text
     * @return the tool result
     * @since 0.9.1
     */
    public static McpSchema.CallToolResult errorResult(String message)
    {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .isError(true)
            .build();
    }

    /**
     * Renders one declared parameter as its JSON Schema property entry: {@code type} and
     * {@code description} for scalars, plus the {@code items} sub-schema for a string array.
     *
     * @param param the declared parameter
     * @return the schema property entry
     */
    private static Map<String, Object> schemaEntry(DeclaredParam param)
    {
        if (param.type() == ParamType.STRING_ARRAY) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(TYPE_KEY, param.type().jsonType());
            entry.put("items", Map.of(TYPE_KEY, STRING_TYPE));
            entry.put(DESCRIPTION_KEY, param.description());
            return entry;
        }
        if (param.type() == ParamType.STRING_MAP) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(TYPE_KEY, param.type().jsonType());
            entry.put("additionalProperties", Map.of(TYPE_KEY, STRING_TYPE));
            entry.put(DESCRIPTION_KEY, param.description());
            return entry;
        }
        return Map.of(
            TYPE_KEY, param.type().jsonType(),
            DESCRIPTION_KEY, param.description());
    }

    /**
     * Generates the MCP input schema advertised for this parameter set, as the JSON Schema 2020-12
     * object map expected by {@code McpSchema.Tool.builder(String, Map)}.
     *
     * @return the input schema map
     * @since 0.9.1
     */
    public Map<String, Object> inputSchema()
    {
        return inputSchema(Map.of());
    }

    /**
     * Generates the MCP input schema map for this parameter set, merged with extra hand-built
     * properties (for the rare non-scalar parameter, e.g. an array of bespoke objects). The
     * {@code properties} map preserves declaration order (documentation generators can rely on
     * declaration order), and the {@code required} key is always present (an empty list is valid JSON
     * Schema 2020-12).
     *
     * @param extraProperties additional schema properties to merge in, keyed by parameter name
     * @return the input schema map
     * @since 0.9.1
     */
    public Map<String, Object> inputSchema(Map<String, Object> extraProperties)
    {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, DeclaredParam> entry : this.params.entrySet()) {
            properties.put(entry.getKey(), schemaEntry(entry.getValue()));
        }
        for (Map.Entry<String, Object> extra : extraProperties.entrySet()) {
            if (properties.put(extra.getKey(), extra.getValue()) != null) {
                throw new IllegalStateException("Extra schema property [" + extra.getKey()
                    + "] collides with a declared parameter.");
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE_KEY, OBJECT_TYPE);
        schema.put("properties", properties);
        schema.put("required", this.requiredNames);
        return schema;
    }

    /**
     * Reads a declared string parameter (see {@link #optionalString} for the coercion rules).
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @return the trimmed value, or {@code null} when absent or blank
     * @throws IllegalStateException if the parameter was not declared as a string
     * @since 0.9.1
     */
    public String string(Map<String, Object> args, String key)
    {
        assertDeclared(key, ParamType.STRING);
        return optionalString(args, key);
    }

    /**
     * Reads a declared string parameter and enforces its presence: the schema's {@code required} list is
     * only client-side advice, so a tool must still reject an absent or blank value at execution time.
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @return the trimmed value, never {@code null}
     * @throws IllegalArgumentException with the agent-facing message if the value is absent or blank
     * @throws IllegalStateException if the parameter was not declared as a string
     * @since 0.9.1
     */
    public String requireString(Map<String, Object> args, String key)
    {
        String value = string(args, key);
        if (value == null) {
            throw new IllegalArgumentException(ERROR_PREFIX + key + REQUIRED_PARAM_ERROR_SUFFIX);
        }
        return value;
    }

    /**
     * Reads a declared integer parameter (see {@link #optionalInt} for the coercion rules).
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @return the value, or {@code null} when absent
     * @throws IllegalStateException if the parameter was not declared as an integer
     * @since 0.9.1
     */
    public Integer integer(Map<String, Object> args, String key)
    {
        assertDeclared(key, ParamType.INTEGER);
        return optionalInt(args, key);
    }

    /**
     * Reads a declared integer parameter and enforces its presence: the schema's {@code required} list is
     * only client-side advice, so a tool must still reject an absent value at execution time.
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @return the value, never {@code null}
     * @throws IllegalArgumentException with the agent-facing message if the value is absent or not an
     *     integer
     * @throws IllegalStateException if the parameter was not declared as an integer
     * @since 0.9.1
     */
    public int requireInteger(Map<String, Object> args, String key)
    {
        Integer value = integer(args, key);
        if (value == null) {
            throw new IllegalArgumentException(ERROR_PREFIX + key + REQUIRED_PARAM_ERROR_SUFFIX);
        }
        return value;
    }

    /**
     * Reads a declared integer parameter, falling back to a default when absent.
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @param defaultValue the value to use when the parameter is absent
     * @return the value, or {@code defaultValue} when absent
     * @throws IllegalStateException if the parameter was not declared as an integer
     * @since 0.9.1
     */
    public int integer(Map<String, Object> args, String key, int defaultValue)
    {
        Integer value = integer(args, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Reads a declared boolean parameter (see {@link #booleanValue} for the coercion rules).
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @return the value, or {@code false} when absent
     * @throws IllegalStateException if the parameter was not declared as a boolean
     * @since 0.9.1
     */
    public boolean bool(Map<String, Object> args, String key)
    {
        assertDeclared(key, ParamType.BOOLEAN);
        return booleanParam(args, key);
    }

    /**
     * Reads a declared string-array parameter. Every element must be a string; anything else (a
     * non-array value or an array containing a non-string element) is rejected with the agent-facing
     * message. Each element is trimmed, and an element that is blank after trimming is dropped from
     * the returned list (mirroring the blank-to-{@code null} convention of {@link #string}), so an
     * all-blank array yields an empty list.
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @return the trimmed string list without its blank elements, or {@code null} when absent
     * @throws IllegalArgumentException if the value is present but not an array of strings
     * @throws IllegalStateException if the parameter was not declared as a string array
     * @since 0.9.1
     */
    public List<String> stringList(Map<String, Object> args, String key)
    {
        assertDeclared(key, ParamType.STRING_ARRAY);
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(ERROR_PREFIX + key + STRING_ARRAY_PARAM_ERROR_SUFFIX);
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String str)) {
                throw new IllegalArgumentException(ERROR_PREFIX + key + STRING_ARRAY_PARAM_ERROR_SUFFIX);
            }
            String trimmed = StringUtils.trimToNull(str);
            if (trimmed != null) {
                strings.add(trimmed);
            }
        }
        return strings;
    }

    /**
     * Reads a declared string-map parameter (a JSON object whose values are all strings). A non-object
     * value, or an object carrying a non-string value, is rejected with the agent-facing teaching
     * message. Keys and values are kept exactly as sent (no trimming): a map value may deliberately be
     * an empty string. Entry order is preserved.
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @return the entries in their original order, or an empty map when the parameter is absent
     * @throws IllegalArgumentException if the value is present but not an object with only string values
     * @throws IllegalStateException if the parameter was not declared as a string map
     * @since 0.9.1
     */
    public Map<String, String> stringMap(Map<String, Object> args, String key)
    {
        assertDeclared(key, ParamType.STRING_MAP);
        Object value = args.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(ERROR_PREFIX + key + STRING_MAP_PARAM_ERROR_SUFFIX);
        }
        Map<String, String> entries = new LinkedHashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException(ERROR_PREFIX + key + STRING_MAP_PARAM_ERROR_SUFFIX);
            }
            entries.put(name, text);
        }
        return entries;
    }

    /**
     * Reads a declared string-map parameter (see {@link #stringMap(Map, String)}) and enforces that it
     * was sent with at least one entry: the schema's {@code required} list is only client-side advice,
     * so a tool must still reject an absent or empty value at execution time.
     *
     * @param args the tool call arguments
     * @param key the parameter name
     * @return the entries in their original order, never empty
     * @throws IllegalArgumentException with the agent-facing message if the value is absent, empty or
     *     not an object with only string values
     * @throws IllegalStateException if the parameter was not declared as a string map
     * @since 0.9.1
     */
    public Map<String, String> requireStringMap(Map<String, Object> args, String key)
    {
        Map<String, String> entries = stringMap(args, key);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(ERROR_PREFIX + key
                + "' parameter is required and must contain at least one entry.");
        }
        return entries;
    }

    private void assertDeclared(String key, ParamType type)
    {
        DeclaredParam declared = this.params.get(key);
        if (declared == null || declared.type() != type) {
            throw new IllegalStateException(
                "Parameter [" + key + "] is not declared as a " + type + " parameter.");
        }
    }

    /**
     * The JSON schema type of a declared parameter.
     *
     * @version $Id$
     */
    private enum ParamType
    {
        /** A string parameter. */
        STRING(STRING_TYPE),

        /** An integer parameter. */
        INTEGER("integer"),

        /** A boolean parameter. */
        BOOLEAN("boolean"),

        /** A flat array-of-strings parameter. */
        STRING_ARRAY("array"),

        /** A flat string-to-string map parameter. */
        STRING_MAP(OBJECT_TYPE);

        private final String jsonType;

        ParamType(String jsonType)
        {
            this.jsonType = jsonType;
        }

        String jsonType()
        {
            return this.jsonType;
        }
    }

    /**
     * One declared parameter: its type and the description advertised in the schema.
     *
     * @param type the parameter type
     * @param description the agent-facing description
     * @version $Id$
     */
    private record DeclaredParam(ParamType type, String description)
    {
    }

    /**
     * Builder collecting a tool's parameter declarations in order.
     *
     * @version $Id$
     * @since 0.9.1
     */
    public static final class Builder
    {
        private final Map<String, DeclaredParam> params = new LinkedHashMap<>();

        private final List<String> required = new ArrayList<>();

        /**
         * Declares an optional string parameter.
         *
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder string(String name, String description)
        {
            this.params.put(name, new DeclaredParam(ParamType.STRING, description));
            return this;
        }

        /**
         * Declares an optional string parameter only when {@code condition} holds, so a variant of the
         * schema can omit a parameter without breaking the fluent chain. Preserves declaration order:
         * the parameter, when added, keeps the position of this call in the chain.
         *
         * @param condition whether to declare the parameter
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder stringIf(boolean condition, String name, String description)
        {
            if (condition) {
                string(name, description);
            }
            return this;
        }

        /**
         * Declares a required string parameter.
         *
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder requiredString(String name, String description)
        {
            string(name, description);
            this.required.add(name);
            return this;
        }

        /**
         * Declares an optional integer parameter.
         *
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder integer(String name, String description)
        {
            this.params.put(name, new DeclaredParam(ParamType.INTEGER, description));
            return this;
        }

        /**
         * Declares an optional boolean parameter.
         *
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder bool(String name, String description)
        {
            this.params.put(name, new DeclaredParam(ParamType.BOOLEAN, description));
            return this;
        }

        /**
         * Declares an optional flat array-of-strings parameter. When read back with
         * {@link MCPToolSupport#stringList}, each element is trimmed and elements that are blank
         * after trimming are dropped from the returned list.
         *
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder stringArray(String name, String description)
        {
            this.params.put(name, new DeclaredParam(ParamType.STRING_ARRAY, description));
            return this;
        }

        /**
         * Declares an optional flat string-to-string map parameter, advertised as a JSON object whose
         * values must all be strings ({@code "additionalProperties": {"type": "string"}}). Read back
         * with {@link MCPToolSupport#stringMap(Map, String)}.
         *
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder stringMap(String name, String description)
        {
            this.params.put(name, new DeclaredParam(ParamType.STRING_MAP, description));
            return this;
        }

        /**
         * Declares a required flat string-to-string map parameter (see {@link #stringMap(String, String)}),
         * enforced at read time by {@link MCPToolSupport#requireStringMap(Map, String)}.
         *
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder requiredStringMap(String name, String description)
        {
            stringMap(name, description);
            this.required.add(name);
            return this;
        }

        /**
         * Declares a required integer parameter, present in the advertised schema's {@code required}
         * list; the reading tool still enforces presence at execution time.
         *
         * @param name the parameter name
         * @param description the agent-facing description
         * @return this builder
         * @since 0.9.1
         */
        public Builder requiredInteger(String name, String description)
        {
            integer(name, description);
            this.required.add(name);
            return this;
        }

        /**
         * Finishes the declaration.
         *
         * @return the immutable parameter set
         * @since 0.9.1
         */
        public MCPToolSupport build()
        {
            return new MCPToolSupport(this);
        }
    }
}
