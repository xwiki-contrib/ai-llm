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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.DateClass;
import com.xpn.xwiki.objects.classes.NumberClass;
import com.xpn.xwiki.objects.classes.PropertyClass;

/**
 * Compiles the declarative parameters of the {@code query_objects} tool into one bound HQL statement and
 * renders each surviving row into the tool's line grammar. Not a component: a plain holder of static
 * helpers, deliberately owning the whole {@code BaseObject}/{@code BaseProperty}/
 * {@code PropertyClass} family so the type dispatch never bloats the tool's own class fan-out (the same
 * seam as {@link MCPSchemaText} and {@link MCPWriteSupport}).
 *
 * <p>Every dynamic piece of statement text comes from a closed map owned by this class: the operator
 * tokens map agent ops to fixed HQL operators, the joined entity names come from the platform's own
 * property-storage mapping ({@link PropertyClass#newProperty()}'s runtime type matches the Hibernate
 * entity of the stored value), and the join aliases and bind-parameter names are generated ({@code p0},
 * {@code :v0}, {@code :p0name}). Agent-supplied field names and values only ever travel as bind values,
 * so no input fragment can reach the statement text.</p>
 *
 * <p>Filter values are coerced with the platform's own semantics ({@link PropertyClass#fromString(String)},
 * which returns {@code null} on an unparseable Number or Date), with two deliberate additions: Boolean
 * vocabulary is pre-validated here because the platform parse cannot signal failure for it, and Date
 * values additionally accept ISO-8601 instants and dates. Refusals are validation errors: Password fields
 * (values must never be matchable by probing), computed fields (no stored values) and list-typed storage
 * (multi-select values) can be neither filtered nor sorted on.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
final class MCPObjectQuerySupport
{
    /**
     * The bind-parameter name of the wiki-local serialized class name, shared with the tool's statement
     * documentation.
     */
    static final String CLASSNAME_BIND = "className";

    /**
     * The {@link PropertyClass#getClassType()} values with special handling: Password fields are refused
     * as filter/sort targets and masked in output; computed fields have no stored values at all; Boolean
     * fields get their vocabulary pre-validated because the platform parse cannot signal failure for them.
     * Package-private because {@link MCPObjectWriteSupport} applies the same type dispatch on the write
     * side.
     */
    static final String PASSWORD_TYPE = "Password";

    /**
     * See {@link #PASSWORD_TYPE}.
     */
    static final String COMPUTED_TYPE = "ComputedField";

    /**
     * See {@link #PASSWORD_TYPE}.
     */
    static final String BOOLEAN_TYPE = "Boolean";

    private static final String EQUALS_OP = "=";

    private static final String GREATER_OP = ">";

    private static final String LESS_OP = "<";

    private static final String CONTAINS_OP = "contains";

    /**
     * The closed operator map: agent op token to HQL operator. Both sides are owned here; an op outside
     * the map is a validation error, so no agent token ever lands in the statement text.
     */
    private static final Map<String, String> OPERATORS = Map.of(
        EQUALS_OP, EQUALS_OP,
        "!=", "<>",
        GREATER_OP, GREATER_OP,
        LESS_OP, LESS_OP,
        CONTAINS_OP, "like");

    private static final String STRING_PROPERTY = "StringProperty";

    private static final String LARGE_STRING_PROPERTY = "LargeStringProperty";

    /**
     * The stored-value entities this compiler can join and compare on: the scalar single-valued property
     * entities of the platform's Hibernate mapping, all exposing their stored value as {@code value}.
     */
    private static final List<String> SCALAR_ENTITIES = List.of(STRING_PROPERTY, LARGE_STRING_PROPERTY,
        "IntegerProperty", "LongProperty", "FloatProperty", "DoubleProperty", "DateProperty");

    /**
     * The string-backed entities, the only ones the {@code contains} op applies to.
     */
    private static final List<String> STRING_ENTITIES = List.of(STRING_PROPERTY, LARGE_STRING_PROPERTY);

    /**
     * The list-typed stored entities (multi-select values), refused as filter and sort targets: their
     * stored shape (a serialized blob or a relational element table) does not compare like a scalar.
     */
    private static final List<String> LIST_ENTITIES = List.of("StringListProperty", "DBStringListProperty");

    private static final String BASE_FROM = "from XWikiDocument doc, BaseObject obj";

    /**
     * The document-object join shared by every statement: the class-scoped {@link #BASE_WHERE} adds the
     * class bind, the document inventory ({@link #compileDocumentInventory(String)}) adds the document
     * bind instead.
     */
    private static final String JOIN_WHERE = "doc.fullName = obj.name and doc.translation = 0";

    private static final String BASE_WHERE = JOIN_WHERE + " and obj.className = :" + CLASSNAME_BIND;

    private static final String SELECT_COLUMNS = "doc.fullName, obj.number";

    private static final String CLASSNAME_COLUMN = "obj.className";

    private static final String DEFAULT_ORDER = "doc.fullName asc, obj.number asc";

    private static final String DOC_BIND = "docFullName";

    private static final String DOC_MATCH = "doc.fullName = :";

    private static final String SELECT_DISTINCT = "select distinct ";

    private static final String WHERE = " where ";

    private static final String ORDER_BY = " order by ";

    private static final String SORT_ALIAS = "ps";

    private static final String NAME_BIND_SUFFIX = "name";

    private static final String FILTER_ALIAS_PREFIX = "p";

    private static final String VALUE_BIND_PREFIX = "v";

    private static final String ID_JOIN = ".id.id = obj.id";

    private static final String NAME_MATCH = ".id.name = :";

    private static final String VALUE_FIELD = ".value";

    private static final String AND = " and ";

    private static final String AS = " as ";

    private static final String COMMA_SPACE = ", ";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String SPACE = " ";

    private static final String WHITESPACE = "\\s+";

    private static final String ASCENDING = "asc";

    private static final String DESCENDING = "desc";

    private static final String FILTER_VERB = "filter on";

    private static final String SORT_VERB = "sort by";

    private static final String CANNOT_PREFIX = "Cannot ";

    private static final String FIELD_INFIX = " field " + QUOTE;

    private static final String OPS_HINT = " (ops: =, !=, >, <, contains)";

    private static final String MASKED_VALUE = "(masked)";

    private static final String COMPUTED_VALUE = "(computed; not shown)";

    private static final String UNSET_VALUE = "(unset)";

    /**
     * Cap on the field names shown per match, so a select list cannot multiply the response size
     * unboundedly.
     */
    private static final int MAX_SELECT_FIELDS = 10;

    /**
     * Cap on the filter entries of one call: every filter joins one more property entity, so an unbounded
     * filter list would hand the query planner an arbitrarily wide join tree.
     */
    private static final int MAX_FILTER_ENTRIES = 10;

    private MCPObjectQuerySupport()
    {
    }

    /**
     * The compiled statement of one call: selects {@code doc.fullName} and {@code obj.number} (plus the
     * sort value when sorting), deterministically ordered, with its named bind values. There is
     * deliberately NO companion count statement: a database-side count would tally rights-denied matches
     * under agent-chosen field predicates, turning count differences into a value-probing oracle over
     * documents the caller cannot view. Totals come from counting the rows that survive per-row
     * authorization.
     *
     * @param statement the page statement
     * @param binds the named bind values
     * @version $Id$
     */
    record CompiledObjectQuery(String statement, Map<String, Object> binds)
    {
    }

    /**
     * @param classDoc the loaded class document
     * @return whether the document defines no class fields at all, i.e. it is not a class definition
     */
    static boolean definesNoFields(XWikiDocument classDoc)
    {
        return MCPSchemaText.definesNoFields(classDoc.getXClass());
    }

    /**
     * Validates the {@code select} field names against the class: each must be a defined field (any kind:
     * Password and computed fields are selectable, they just render masked or as a fixed note), and the
     * list is capped at {@value #MAX_SELECT_FIELDS} names.
     *
     * @param classDoc the loaded class document
     * @param select the requested field names, possibly {@code null}
     * @throws IllegalArgumentException with an agent-facing message on an unknown field or an oversized
     *     list
     */
    static void validateSelect(XWikiDocument classDoc, List<String> select)
    {
        if (select == null) {
            return;
        }
        if (select.size() > MAX_SELECT_FIELDS) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + "select' accepts at most "
                + MAX_SELECT_FIELDS + " field names.");
        }
        BaseClass xclass = classDoc.getXClass();
        for (String name : select) {
            if (!(xclass.get(name) instanceof PropertyClass)) {
                throw unknownField(xclass, name);
            }
        }
    }

    /**
     * Compiles the declarative filter/sort/document parameters into the bound statement. Every filter
     * entry is parsed as {@code "<field> <op> <value>"} (the value is the remainder and may contain
     * spaces), validated against the class, coerced with the field's own type semantics and joined through
     * its stored-value entity; the filter list is capped at {@value #MAX_FILTER_ENTRIES} entries. The
     * optional sort field gets its own entity join and a fullName/number tiebreak for stable paging. When
     * sorting, the sort value is added to the select list: with {@code distinct}, databases require every
     * ordering expression to appear among the selected columns.
     *
     * @param classDoc the loaded class document
     * @param localClassName the wiki-local serialized class name (classes are wiki-local, composing with
     *     the door's wiki scoping)
     * @param filterEntries the raw filter entries, possibly {@code null}
     * @param sort the raw sort parameter ({@code "<field> asc"} or {@code "<field> desc"}), possibly
     *     {@code null}
     * @param documentFullName the wiki-local full name of the already-authorized document to restrict to,
     *     or {@code null} for no restriction
     * @return the compiled statement with its binds
     * @throws IllegalArgumentException with an agent-facing message on any validation failure
     */
    static CompiledObjectQuery compile(XWikiDocument classDoc, String localClassName,
        List<String> filterEntries, String sort, String documentFullName)
    {
        BaseClass xclass = classDoc.getXClass();
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put(CLASSNAME_BIND, localClassName);
        StringBuilder joins = new StringBuilder(BASE_FROM);
        StringBuilder where = new StringBuilder(BASE_WHERE);
        if (filterEntries != null) {
            if (filterEntries.size() > MAX_FILTER_ENTRIES) {
                throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + "filters' accepts at most "
                    + MAX_FILTER_ENTRIES + " entries.");
            }
            for (int i = 0; i < filterEntries.size(); i++) {
                appendFilter(joins, where, binds, xclass, filterEntries.get(i), i);
            }
        }
        if (documentFullName != null) {
            where.append(AND).append(DOC_MATCH).append(DOC_BIND);
            binds.put(DOC_BIND, documentFullName);
        }
        String select = SELECT_COLUMNS;
        String order = DEFAULT_ORDER;
        if (sort != null) {
            SortSpec spec = parseSort(xclass, sort);
            String nameBind = SORT_ALIAS + NAME_BIND_SUFFIX;
            appendPropertyJoin(joins, where, spec.entity(), SORT_ALIAS, nameBind);
            binds.put(nameBind, spec.field());
            select = SELECT_COLUMNS + COMMA_SPACE + SORT_ALIAS + VALUE_FIELD;
            order = SORT_ALIAS + VALUE_FIELD + SPACE + spec.direction() + COMMA_SPACE + DEFAULT_ORDER;
        }
        return new CompiledObjectQuery(
            SELECT_DISTINCT + select + SPACE + joins + WHERE + where + ORDER_BY + order,
            binds);
    }

    /**
     * Compiles the document-inventory statement of a document-only call (no {@code class}): every object
     * row of one document across classes, selecting the class name as a third column. The statement is
     * the class-scoped one minus the class bind - same join, same translation exclusion, the
     * already-authorized document bound like the class-scoped document restriction - ordered by class
     * name then object number so the rendering can group by class. No property entity is ever joined:
     * a document-only call carries no filters, select or sort, so no stored field value is read.
     *
     * @param documentFullName the wiki-local full name of the already-authorized document to inventory
     * @return the compiled statement with its bind
     */
    static CompiledObjectQuery compileDocumentInventory(String documentFullName)
    {
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put(DOC_BIND, documentFullName);
        return new CompiledObjectQuery(
            SELECT_DISTINCT + SELECT_COLUMNS + COMMA_SPACE + CLASSNAME_COLUMN + SPACE + BASE_FROM
                + WHERE + JOIN_WHERE + AND + DOC_MATCH + DOC_BIND
                + ORDER_BY + CLASSNAME_COLUMN + SPACE + ASCENDING + COMMA_SPACE + DEFAULT_ORDER,
            binds);
    }

    /**
     * Renders one surviving row: loads the object by class reference and number (a vanished object -
     * deleted between the query and the read - yields {@code null} so the caller skips the row), then
     * composes the header line ({@code <reference> (object <number>, v<version>)} plus the quoted title
     * when non-blank) and one indented {@code name: value} line per selected field. Every wiki-authored
     * fragment is neutralized and length-capped before landing in a line.
     *
     * @param resultDoc the loaded, already-authorized result document
     * @param classDoc the loaded class document (the field definitions drive the value rendering)
     * @param classRef the resolved class document reference
     * @param number the object number within the document
     * @param serializedRef the serialized result-document reference to show
     * @param select the validated field names to show, possibly {@code null}
     * @return the rendered block, or {@code null} when the object no longer exists on the document
     */
    static String renderResult(XWikiDocument resultDoc, XWikiDocument classDoc, DocumentReference classRef,
        int number, String serializedRef, List<String> select)
    {
        BaseObject object = resultDoc.getXObject(classRef, number);
        if (object == null) {
            return null;
        }
        StringBuilder block = new StringBuilder(strip(serializedRef)).append(" (object ").append(number)
            .append(", v").append(strip(resultDoc.getVersion())).append(')');
        String title = strip(resultDoc.getTitle());
        if (StringUtils.isNotBlank(title)) {
            block.append(SPACE).append(QUOTE).append(title).append(QUOTE);
        }
        if (select != null) {
            BaseClass xclass = classDoc.getXClass();
            for (String name : select) {
                block.append('\n').append("  ").append(strip(name)).append(": ")
                    .append(fieldValue(xclass, object, name));
            }
        }
        return block.toString();
    }

    /**
     * Renders one page of a document inventory (the document-only mode of {@code query_objects}): the
     * {@code (doc.fullName, obj.number, obj.className)} rows grouped by class in row order (the
     * inventory statement already orders by class then number), one class-name header line per class and
     * one indented {@code object <N>} line per object. Deliberately NO field values: an inventory spans
     * every class on the document, including classes whose per-class output would mask values (Password,
     * computed fields), so showing only class names and numbers keeps this mode free of any value
     * exposure. The class names are wiki-authored and are neutralized like every other fragment.
     *
     * @param page the authorized rows of this page
     * @return the rendered inventory block, without a trailing newline
     */
    static String renderDocumentInventory(List<Object[]> page)
    {
        StringBuilder block = new StringBuilder();
        String currentClass = null;
        for (Object[] columns : page) {
            String className = (String) columns[2];
            if (!className.equals(currentClass)) {
                if (block.length() > 0) {
                    block.append('\n');
                }
                block.append(strip(className));
                currentClass = className;
            }
            block.append('\n').append("  object ").append(((Number) columns[1]).intValue());
        }
        return block.toString();
    }

    /**
     * Renders one selected field's value. The field's class definition decides the opaque cases before any
     * stored value is read: a Password value is never read at all (masked even from this code path) and a
     * computed field has no stored value. Everything else reads the stored property: dates render as
     * ISO-8601 instants, list values join with a separator, and a missing property or {@code null} value
     * renders as the fixed unset marker.
     *
     * @param xclass the class definition
     * @param object the object holding the values
     * @param name the validated field name
     * @return the rendered value fragment
     */
    private static String fieldValue(BaseClass xclass, BaseObject object, String name)
    {
        String type = xclass.get(name) instanceof PropertyClass definition ? definition.getClassType() : null;
        if (PASSWORD_TYPE.equals(type)) {
            return MASKED_VALUE;
        }
        if (COMPUTED_TYPE.equals(type)) {
            return COMPUTED_VALUE;
        }
        Object value = object.safeget(name) instanceof BaseProperty stored ? stored.getValue() : null;
        return value == null ? UNSET_VALUE : renderValue(value);
    }

    /**
     * @param value the non-{@code null} stored value
     * @return the rendered value fragment: dates as ISO-8601 instants, list values joined with a
     *     separator, everything else as its neutralized string form
     */
    private static String renderValue(Object value)
    {
        if (value instanceof Date) {
            return MCPToolSupport.isoInstant(value);
        }
        if (value instanceof List<?> values) {
            return values.stream().map(element -> strip(String.valueOf(element)))
                .collect(Collectors.joining(COMMA_SPACE));
        }
        return strip(String.valueOf(value));
    }

    /**
     * Parses, validates and appends one filter entry: entity join, name/value clauses and binds.
     *
     * @param joins the from-clause being composed
     * @param where the where-clause being composed
     * @param binds the bind map being filled
     * @param xclass the class definition
     * @param entry the raw filter entry
     * @param index the filter's position, the source of its generated alias and bind names
     * @throws IllegalArgumentException with an agent-facing message on any validation failure
     */
    private static void appendFilter(StringBuilder joins, StringBuilder where, Map<String, Object> binds,
        BaseClass xclass, String entry, int index)
    {
        String[] parts = entry.trim().split(WHITESPACE, 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX
                + "filters' entries must each be \"<field> <op> <value>\"" + OPS_HINT + PERIOD);
        }
        PropertyClass property = storedField(xclass, parts[0], FILTER_VERB);
        String hqlOperator = OPERATORS.get(parts[1]);
        if (hqlOperator == null) {
            throw new IllegalArgumentException("Error: unknown op " + QUOTE + strip(parts[1]) + QUOTE
                + OPS_HINT + PERIOD);
        }
        String entity = entityName(property, FILTER_VERB);
        Object bindValue;
        if (CONTAINS_OP.equals(parts[1])) {
            if (!STRING_ENTITIES.contains(entity)) {
                throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX
                    + "contains' only applies to text fields;" + FIELD_INFIX + strip(parts[0]) + QUOTE
                    + " is not text-valued.");
            }
            // The raw text, unescaped: the door escapes the LIKE wildcards when binding a Contains.
            bindValue = new MCPRowQuery.Contains(parts[2]);
        } else {
            bindValue = coerce(property, parts[2]);
        }
        String alias = FILTER_ALIAS_PREFIX + index;
        String nameBind = alias + NAME_BIND_SUFFIX;
        String valueBind = VALUE_BIND_PREFIX + index;
        appendPropertyJoin(joins, where, entity, alias, nameBind);
        where.append(AND).append(alias).append(VALUE_FIELD).append(SPACE).append(hqlOperator)
            .append(" :").append(valueBind);
        binds.put(nameBind, property.getName());
        binds.put(valueBind, bindValue);
    }

    /**
     * Appends one stored-value entity join: the from-clause entry and the id/name matching clauses tying
     * the property row to the object and to its (bound) field name.
     *
     * @param joins the from-clause being composed
     * @param where the where-clause being composed
     * @param entity the entity name, from the closed entity mapping
     * @param alias the generated alias
     * @param nameBind the generated bind name of the field-name match
     */
    private static void appendPropertyJoin(StringBuilder joins, StringBuilder where, String entity,
        String alias, String nameBind)
    {
        joins.append(COMMA_SPACE).append(entity).append(AS).append(alias);
        where.append(AND).append(alias).append(ID_JOIN)
            .append(AND).append(alias).append(NAME_MATCH).append(nameBind);
    }

    /**
     * Parses and validates the sort parameter: exactly a field name and a direction token, the field
     * subject to the same stored-field validation as a filter.
     *
     * @param xclass the class definition
     * @param sort the raw sort parameter
     * @return the validated sort specification
     * @throws IllegalArgumentException with an agent-facing message on any validation failure
     */
    private static SortSpec parseSort(BaseClass xclass, String sort)
    {
        String[] parts = sort.trim().split(WHITESPACE);
        if (parts.length != 2 || !(ASCENDING.equalsIgnoreCase(parts[1])
            || DESCENDING.equalsIgnoreCase(parts[1]))) {
            throw new IllegalArgumentException(
                MCPToolSupport.ERROR_PREFIX + "sort' must be \"<field> asc\" or \"<field> desc\".");
        }
        PropertyClass property = storedField(xclass, parts[0], SORT_VERB);
        String entity = entityName(property, SORT_VERB);
        // A large-text sort would put SELECT DISTINCT over a CLOB column, which several DBMS reject.
        if (LARGE_STRING_PROPERTY.equals(entity)) {
            throw new IllegalArgumentException(CANNOT_PREFIX + SORT_VERB + FIELD_INFIX + strip(parts[0])
                + QUOTE + ": sorting on large text fields is not supported.");
        }
        // The direction token comes from our own pair of constants, never from the input.
        String direction = ASCENDING.equalsIgnoreCase(parts[1]) ? ASCENDING : DESCENDING;
        return new SortSpec(entity, property.getName(), direction);
    }

    /**
     * Resolves a field name to its class definition and applies the class-type refusals shared by filters
     * and sorts.
     *
     * @param xclass the class definition
     * @param name the field name
     * @param verb the action for the error messages ({@code "filter on"} or {@code "sort by"})
     * @return the field's definition
     * @throws IllegalArgumentException with an agent-facing message when the field is unknown or refused
     */
    private static PropertyClass storedField(BaseClass xclass, String name, String verb)
    {
        if (!(xclass.get(name) instanceof PropertyClass property)) {
            throw unknownField(xclass, name);
        }
        String type = property.getClassType();
        if (PASSWORD_TYPE.equals(type)) {
            throw new IllegalArgumentException(CANNOT_PREFIX + verb + " Password fields (field "
                + QUOTE + strip(name) + QUOTE + ").");
        }
        if (COMPUTED_TYPE.equals(type)) {
            throw new IllegalArgumentException(CANNOT_PREFIX + verb + FIELD_INFIX + strip(name) + QUOTE
                + ": computed fields have no stored values.");
        }
        return property;
    }

    /**
     * Maps a field definition to the Hibernate entity of its stored value, applying the storage refusals:
     * {@link PropertyClass#newProperty()} returns the exact stored property type, whose simple name is the
     * entity name in the platform's mapping.
     *
     * @param property the field's definition
     * @param verb the action for the error messages
     * @return the entity name, guaranteed to be one of {@link #SCALAR_ENTITIES}
     * @throws IllegalArgumentException with an agent-facing message when the storage is list-typed or
     *     unknown
     */
    private static String entityName(PropertyClass property, String verb)
    {
        BaseProperty stored = property.newProperty();
        String entity = stored != null ? stored.getClass().getSimpleName() : null;
        if (entity == null || !SCALAR_ENTITIES.contains(entity)) {
            String reason = LIST_ENTITIES.contains(entity)
                ? ": filtering on list fields is not supported"
                : ": its storage type is not queryable";
            throw new IllegalArgumentException(CANNOT_PREFIX + verb + FIELD_INFIX
                + strip(property.getName()) + QUOTE + reason + PERIOD);
        }
        return entity;
    }

    /**
     * Parses a value with the field's own type, returning {@code null} on any parse failure. On the 17.4
     * build target {@code fromString} returns {@code null} for unparseable Number/Date input; on the
     * 17.10+ runtime (XWIKI-20910) it throws {@code XWikiException} instead. Catching {@code Exception}
     * treats both the same, so the caller's null-check produces one teaching refusal regardless of
     * platform version. {@code XWikiException} cannot be caught narrowly here: it is not declared thrown
     * by {@code fromString} on the 17.4 compile target, so a narrow catch would not compile. Shared with
     * {@link MCPObjectWriteSupport} so filter values and written field values absorb the difference the
     * same way.
     *
     * @param property the field's definition
     * @param value the raw value text
     * @return the parsed property, or {@code null} on any parse failure
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    static BaseProperty parseOrNull(PropertyClass property, String value)
    {
        try {
            return property.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Coerces a filter value with the field's own type semantics. The platform parse
     * ({@link PropertyClass#fromString(String)}) returns {@code null} - not an exception - on an
     * unparseable Number or Date, so a {@code null} property or {@code null} parsed value is the
     * validation failure signal. Two type-specific layers sit on top: Boolean vocabulary is pre-validated
     * (the platform parse stores {@code null} for garbage instead of failing) and Date values fall back to
     * ISO-8601 when the class's own format does not match.
     *
     * <p>Version note: on the 17.4 build target {@code fromString} returns {@code null} on unparseable
     * Number or Date input; on the 17.10+ runtime (XWIKI-20910) it throws {@code XWikiException} instead.
     * The shared {@link #parseOrNull(PropertyClass, String)} helper absorbs that difference by treating a
     * throw the same as {@code null}, so this method's null check produces one teaching refusal on either
     * platform.</p>
     *
     * @param property the field's definition
     * @param raw the raw value text
     * @return the typed bind value
     * @throws IllegalArgumentException with an agent-facing message naming the field and its expected
     *     type or format when the value does not parse
     */
    private static Object coerce(PropertyClass property, String raw)
    {
        if (BOOLEAN_TYPE.equals(property.getClassType())) {
            return booleanBind(property, raw);
        }
        if (property instanceof DateClass date) {
            return dateBind(date, raw);
        }
        BaseProperty parsed = parseOrNull(property, raw);
        Object value = parsed != null ? parsed.getValue() : null;
        if (value == null) {
            throw invalidValue(property, raw, expectedDetail(property));
        }
        return value;
    }

    /**
     * Shared with {@link MCPObjectWriteSupport} so filter values and written field values are refused
     * with identical phrasing.
     *
     * @param property the field's definition
     * @param raw the rejected raw value
     * @param expected the expected type/format description
     * @return the agent-facing validation error
     */
    static IllegalArgumentException invalidValue(PropertyClass property, String raw, String expected)
    {
        return new IllegalArgumentException("Error: invalid value " + QUOTE + strip(raw) + QUOTE
            + " for" + FIELD_INFIX + strip(property.getName()) + QUOTE + ": expected " + expected + PERIOD);
    }

    /**
     * Shared with {@link MCPObjectWriteSupport} (see {@link #invalidValue}).
     *
     * @param property the field's definition
     * @return the expected-value description for a validation error, with the type detail a schema line
     *     would carry (e.g. the number's storage type)
     */
    static String expectedDetail(PropertyClass property)
    {
        if (property instanceof NumberClass number) {
            return "a number of type " + number.getNumberType();
        }
        return "a " + property.getClassType() + " value";
    }

    /**
     * Coerces a Boolean filter value. Booleans store as integers, and the platform parse cannot signal
     * failure for them (garbage stores as a {@code null} value), so the vocabulary is validated here:
     * {@code 0}, {@code 1}, {@code true} and {@code false} (case-insensitive). Shared with
     * {@link MCPObjectWriteSupport}, whose Boolean pre-validation accepts the same vocabulary.
     *
     * @param property the field's definition, for the error message
     * @param raw the raw value text
     * @return the integer bind value
     * @throws IllegalArgumentException with an agent-facing message on any other value
     */
    static Integer booleanBind(PropertyClass property, String raw)
    {
        if ("1".equals(raw) || "true".equalsIgnoreCase(raw)) {
            return 1;
        }
        if ("0".equals(raw) || "false".equalsIgnoreCase(raw)) {
            return 0;
        }
        throw invalidValue(property, raw, "one of 0, 1, true, false");
    }

    /**
     * Coerces a Date filter value: first with the class's own format (the platform parse), then as an
     * ISO-8601 instant or date.
     *
     * @param date the field's definition
     * @param raw the raw value text
     * @return the {@link Date} bind value
     * @throws IllegalArgumentException with an agent-facing message naming the class's format when nothing
     *     parses
     */
    private static Date dateBind(DateClass date, String raw)
    {
        BaseProperty parsed = parseOrNull(date, raw);
        if (parsed != null && parsed.getValue() instanceof Date value) {
            return value;
        }
        Date iso = isoDate(raw);
        if (iso != null) {
            return iso;
        }
        throw invalidValue(date, raw, "the class's date format " + QUOTE + strip(date.getDateFormat())
            + QUOTE + " or an ISO-8601 date or instant");
    }

    /**
     * @param raw the raw value text
     * @return the value parsed as an ISO-8601 instant, or as an ISO-8601 local date at UTC midnight, or
     *     {@code null} when it is neither
     */
    private static Date isoDate(String raw)
    {
        try {
            return Date.from(Instant.parse(raw));
        } catch (DateTimeParseException e) {
            // Not an instant; a plain ISO date is tried next.
        }
        try {
            return Date.from(LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Shared with {@link MCPObjectWriteSupport} (see {@link #invalidValue}).
     *
     * @param xclass the class definition
     * @param name the unknown field name
     * @return the agent-facing validation error, listing the class's enabled field names
     */
    static IllegalArgumentException unknownField(BaseClass xclass, String name)
    {
        String fields = xclass.getEnabledProperties().stream()
            .map(PropertyClass::getName)
            .map(MCPObjectQuerySupport::strip)
            .collect(Collectors.joining(COMMA_SPACE));
        return new IllegalArgumentException("Error: unknown field " + QUOTE + strip(name) + QUOTE
            + ". Fields of this class: " + fields + PERIOD);
    }

    /**
     * @param value the wiki-authored or agent-supplied fragment, possibly {@code null}
     * @return the fragment neutralized and length-capped by the shared guard
     *     ({@link MCPTextGuards#fragment(String)}); {@code null} stays {@code null}
     */
    private static String strip(String value)
    {
        return MCPTextGuards.fragment(value);
    }

    /**
     * A validated sort: the stored-value entity to join, the field name to bind and the direction token
     * (one of the two constants, never input text).
     *
     * @param entity the stored-value entity name
     * @param field the field name, bound as the name match
     * @param direction the direction token
     * @version $Id$
     */
    private record SortSpec(String entity, String field, String direction)
    {
    }
}
