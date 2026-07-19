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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.QueryException;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.ComputedFieldClass;
import com.xpn.xwiki.objects.classes.PropertyClass;

/**
 * Shared schema-write machinery of the {@code write_schema} MCP tool: creating, modifying and removing one
 * XClass field, plus the authorized-only orphan-count disclosure a field removal carries. Not a component: a
 * plain holder of static helpers, deliberately owning the whole {@code BaseClass}/{@code PropertyClass}
 * family so the type dispatch never bloats the tool's own class fan-out (the same seam as
 * {@link MCPObjectWriteSupport} on the object-write side and {@link MCPSchemaText} on the read side).
 *
 * <p>Field creation calls the platform's own {@code BaseClass.add*Field} convenience methods (or, for the
 * one type they do not cover, constructs a {@link ComputedFieldClass}) with structural defaults, then
 * applies the caller's attributes. Attributes are set generically as the property's own meta-properties
 * ({@code size}, {@code numberType}, {@code values}, {@code sql}, {@code script}, {@code multiSelect}, ...),
 * so no per-type subclass is imported: a per-type allowed-attribute table (data, not branches) names the
 * attributes each type accepts and the stored form each coerces to.</p>
 *
 * <p>Removing a field or changing its type does not delete the object values keyed by that field name: class
 * definitions live in XML, object values in separate tables, so the values persist as orphaned rows and
 * reappear if the field is re-added with a compatible type. The removal is therefore reversible, and the
 * orphan-count disclosure says so. The count is authorized-only - each owning document is resolved and
 * authorized through the {@link MCPRowQuery} door before it is counted - so it never reveals the cardinality
 * of documents the caller cannot view, consistent with {@code query_objects}.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
final class MCPSchemaWriteSupport
{
    private static final String STRING_TYPE = "String";

    private static final String TEXTAREA_TYPE = "TextArea";

    private static final String PASSWORD_TYPE = "Password";

    private static final String EMAIL_TYPE = "Email";

    private static final String NUMBER_TYPE = "Number";

    private static final String BOOLEAN_TYPE = "Boolean";

    private static final String DATE_TYPE = "Date";

    private static final String STATICLIST_TYPE = "StaticList";

    private static final String DBLIST_TYPE = "DBList";

    private static final String DBTREELIST_TYPE = "DBTreeList";

    private static final String PAGE_TYPE = "Page";

    private static final String GROUPS_TYPE = "Groups";

    private static final String USERS_TYPE = "Users";

    private static final String LEVELS_TYPE = "Levels";

    private static final String COMPUTED_TYPE = "ComputedField";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String LIST_SEPARATOR = ", ";

    private static final String CONTENT_TYPE_STORED = "contenttype";

    private static final String EMPTY = "";

    private static final String FIELD_LABEL = "Field ";

    private static final String ATTRIBUTE_LABEL = "Attribute ";

    private static final String DEFAULT_NUMBER_TYPE = "long";

    private static final String DEFAULT_BOOLEAN_DISPLAY = "yesno";

    private static final int DEFAULT_STRING_SIZE = 30;

    private static final int DEFAULT_NUMBER_SIZE = 30;

    private static final int DEFAULT_TEXTAREA_COLS = 40;

    private static final int DEFAULT_TEXTAREA_ROWS = 5;

    private static final Attr SIZE = new Attr("size", AttrKind.INT);

    private static final Attr ROWS = new Attr("rows", AttrKind.INT);

    private static final Attr MULTISELECT = new Attr("multiSelect", AttrKind.BOOL);

    private static final Attr RELATIONAL = new Attr("relationalStorage", AttrKind.BOOL);

    private static final Attr EMPTY_IS_TODAY = new Attr("emptyIsToday", AttrKind.BOOL);

    private static final Attr DISPLAY_TYPE = new Attr("displayType", AttrKind.STRING);

    private static final Attr NUMBER_TYPE_ATTR = new Attr("numberType", AttrKind.STRING);

    private static final Attr VALUES = new Attr("values", AttrKind.STRING);

    private static final Attr SEPARATOR = new Attr("separator", AttrKind.STRING);

    private static final Attr EDITOR = new Attr("editor", AttrKind.EDITOR);

    private static final Attr DATE_FORMAT = new Attr("dateFormat", AttrKind.STRING);

    private static final Attr CONTENT_TYPE = new Attr("contentType", CONTENT_TYPE_STORED, AttrKind.CONTENT_TYPE);

    private static final Attr SQL = new Attr("sql", AttrKind.LARGE_STRING);

    private static final Attr SCRIPT = new Attr("script", AttrKind.LARGE_STRING);

    /**
     * The bound HQL of the orphan count: the distinct owning documents whose objects of the class hold a
     * stored value under the removed field name. It queries the mapped {@code BaseProperty} base entity, so
     * a value in any property table (of any type the field ever had) is counted. The class name and field
     * name are binds, never interpolated.
     */
    private static final String ORPHAN_STATEMENT =
        "select distinct doc.fullName from XWikiDocument doc, BaseObject obj, BaseProperty prop "
            + "where doc.fullName = obj.name and doc.translation = 0 and obj.className = :className "
            + "and prop.id.id = obj.id and prop.id.name = :field";

    /**
     * The field creators, keyed by type token (the {@link PropertyClass#getClassType()} vocabulary the
     * {@code get_schema} tool renders, so the write input mirrors the read output). The insertion order is
     * the order the accepted-types list is shown in. Each entry is data, not a branch, so this table does
     * not inflate the method complexity a 15-way switch would.
     */
    private static final Map<String, FieldFactory> FIELD_FACTORIES = buildFactories();

    /**
     * The attributes each type accepts, keyed by type token, each mapping the agent-facing attribute name to
     * the meta-property it sets and the stored form it coerces to. An attribute outside a type's map is a
     * validation error naming the type's accepted attributes.
     */
    private static final Map<String, Map<String, Attr>> ALLOWED = buildAllowed();

    private MCPSchemaWriteSupport()
    {
    }

    private static Map<String, FieldFactory> buildFactories()
    {
        Map<String, FieldFactory> factories = new LinkedHashMap<>();
        factories.put(STRING_TYPE, (xclass, field, pretty) -> xclass.addTextField(field, pretty,
            DEFAULT_STRING_SIZE));
        factories.put(TEXTAREA_TYPE, (xclass, field, pretty) -> xclass.addTextAreaField(field, pretty,
            DEFAULT_TEXTAREA_COLS, DEFAULT_TEXTAREA_ROWS));
        factories.put(PASSWORD_TYPE, (xclass, field, pretty) -> xclass.addPasswordField(field, pretty,
            DEFAULT_STRING_SIZE));
        factories.put(EMAIL_TYPE, (xclass, field, pretty) -> xclass.addEmailField(field, pretty,
            DEFAULT_STRING_SIZE));
        factories.put(NUMBER_TYPE, (xclass, field, pretty) -> xclass.addNumberField(field, pretty,
            DEFAULT_NUMBER_SIZE, DEFAULT_NUMBER_TYPE));
        factories.put(BOOLEAN_TYPE, (xclass, field, pretty) -> xclass.addBooleanField(field, pretty,
            DEFAULT_BOOLEAN_DISPLAY));
        factories.put(DATE_TYPE, (xclass, field, pretty) -> xclass.addDateField(field, pretty));
        factories.put(STATICLIST_TYPE, (xclass, field, pretty) -> xclass.addStaticListField(field, pretty,
            EMPTY));
        factories.put(DBLIST_TYPE, (xclass, field, pretty) -> xclass.addDBListField(field, pretty, EMPTY));
        factories.put(DBTREELIST_TYPE, (xclass, field, pretty) -> xclass.addDBTreeListField(field, pretty,
            EMPTY));
        factories.put(PAGE_TYPE, (xclass, field, pretty) -> xclass.addPageField(field, pretty,
            DEFAULT_STRING_SIZE));
        factories.put(GROUPS_TYPE, (xclass, field, pretty) -> xclass.addGroupsField(field, pretty));
        factories.put(USERS_TYPE, (xclass, field, pretty) -> xclass.addUsersField(field, pretty));
        factories.put(LEVELS_TYPE, (xclass, field, pretty) -> xclass.addLevelsField(field, pretty));
        factories.put(COMPUTED_TYPE, MCPSchemaWriteSupport::addComputedField);
        return factories;
    }

    private static Map<String, Map<String, Attr>> buildAllowed()
    {
        Map<String, Map<String, Attr>> allowed = new LinkedHashMap<>();
        allowed.put(STRING_TYPE, attrMap(SIZE));
        allowed.put(TEXTAREA_TYPE, attrMap(SIZE, ROWS, EDITOR, CONTENT_TYPE));
        allowed.put(PASSWORD_TYPE, attrMap(SIZE));
        allowed.put(EMAIL_TYPE, attrMap(SIZE));
        allowed.put(NUMBER_TYPE, attrMap(SIZE, NUMBER_TYPE_ATTR));
        allowed.put(BOOLEAN_TYPE, attrMap(DISPLAY_TYPE));
        allowed.put(DATE_TYPE, attrMap(SIZE, DATE_FORMAT, EMPTY_IS_TODAY));
        allowed.put(STATICLIST_TYPE, attrMap(SIZE, VALUES, MULTISELECT, RELATIONAL, DISPLAY_TYPE, SEPARATOR));
        allowed.put(DBLIST_TYPE, attrMap(SIZE, SQL, MULTISELECT, RELATIONAL, DISPLAY_TYPE));
        allowed.put(DBTREELIST_TYPE, attrMap(SIZE, SQL, MULTISELECT, RELATIONAL, DISPLAY_TYPE));
        allowed.put(PAGE_TYPE, attrMap(SIZE, SQL, MULTISELECT, DISPLAY_TYPE));
        allowed.put(GROUPS_TYPE, attrMap(SIZE, MULTISELECT));
        allowed.put(USERS_TYPE, attrMap(SIZE, MULTISELECT));
        allowed.put(LEVELS_TYPE, attrMap(SIZE));
        allowed.put(COMPUTED_TYPE, attrMap(SCRIPT));
        return allowed;
    }

    private static Map<String, Attr> attrMap(Attr... attrs)
    {
        Map<String, Attr> map = new LinkedHashMap<>();
        for (Attr attr : attrs) {
            map.put(attr.name(), attr);
        }
        return map;
    }

    /**
     * @return the accepted type tokens joined for an agent-facing message, in a stable order
     */
    static String acceptedTypes()
    {
        return String.join(LIST_SEPARATOR, FIELD_FACTORIES.keySet());
    }

    /**
     * Adds one field to the class definition on the given (already cloned-for-edit) document, creating the
     * class itself if the document defined none yet, then applies the caller's attributes and returns the
     * resulting field rendered in the {@code get_schema} grammar.
     *
     * @param editable the tool's editable copy of the class document
     * @param field the field name to add
     * @param type the field type token (one of {@link #acceptedTypes()})
     * @param prettyName the display name, or {@code null}/blank to default to the field name
     * @param attributes the type-specific attributes, possibly empty
     * @param localClassName the wiki-local serialized class name, for the error messages
     * @param context the XWiki context, needed to render a static list's values
     * @return the resulting field rendered as one grammar line
     * @throws IllegalArgumentException with an agent-facing message when the field already exists, the type
     *     is unknown or an attribute is not valid for the type
     */
    static String addField(XWikiDocument editable, String field, String type, String prettyName,
        Map<String, String> attributes, String localClassName, XWikiContext context)
    {
        BaseClass xclass = editable.getXClass();
        if (xclass.get(field) instanceof PropertyClass) {
            throw new IllegalArgumentException(FIELD_LABEL + QUOTE + strip(field) + QUOTE + " already exists on "
                + "class " + QUOTE + strip(localClassName) + QUOTE + ". Use operation=modify_field to change "
                + "it, or remove_field then add_field to change its type.");
        }
        FieldFactory factory = FIELD_FACTORIES.get(type);
        if (factory == null) {
            throw unknownType(type);
        }
        String pretty = StringUtils.isBlank(prettyName) ? field : prettyName;
        factory.create(xclass, field, pretty);
        if (!(xclass.get(field) instanceof PropertyClass property)) {
            throw new IllegalArgumentException(FIELD_LABEL + QUOTE + strip(field) + QUOTE
                + " could not be created.");
        }
        applyAttributes(property, type, attributes);
        return MCPSchemaText.renderField(property, context);
    }

    /**
     * Modifies one existing field's display name and/or attributes in place (the type is never changed
     * here) and returns the resulting field rendered in the {@code get_schema} grammar.
     *
     * @param editable the tool's editable copy of the class document
     * @param field the field name to modify
     * @param prettyName the new display name, or {@code null}/blank to leave it
     * @param attributes the attributes to set, possibly empty
     * @param localClassName the wiki-local serialized class name, for the error messages
     * @param context the XWiki context, needed to render a static list's values
     * @return the resulting field rendered as one grammar line
     * @throws IllegalArgumentException with an agent-facing message when the document is not a class, the
     *     field is unknown, an attribute is not valid for its type, or nothing was given to change
     */
    static String modifyField(XWikiDocument editable, String field, String prettyName,
        Map<String, String> attributes, String localClassName, XWikiContext context)
    {
        MCPObjectWriteSupport.requireClassFields(editable, localClassName);
        BaseClass xclass = editable.getXClass();
        if (!(xclass.get(field) instanceof PropertyClass property)) {
            throw MCPObjectQuerySupport.unknownField(xclass, field);
        }
        boolean changed = false;
        if (StringUtils.isNotBlank(prettyName)) {
            property.setPrettyName(prettyName);
            changed = true;
        }
        if (attributes != null && !attributes.isEmpty()) {
            applyAttributes(property, property.getClassType(), attributes);
            changed = true;
        }
        if (!changed) {
            throw new IllegalArgumentException("Nothing to change on field " + QUOTE + strip(field) + QUOTE
                + ": provide pretty_name and/or attributes.");
        }
        return MCPSchemaText.renderField(property, context);
    }

    /**
     * Removes one existing field from the class definition. The object values keyed by the field name are
     * not deleted (see the orphan-count disclosure); only the definition is removed.
     *
     * @param editable the tool's editable copy of the class document
     * @param field the field name to remove
     * @param localClassName the wiki-local serialized class name, for the error messages
     * @throws IllegalArgumentException with an agent-facing message when the document is not a class or the
     *     field is unknown
     */
    static void removeField(XWikiDocument editable, String field, String localClassName)
    {
        MCPObjectWriteSupport.requireClassFields(editable, localClassName);
        BaseClass xclass = editable.getXClass();
        if (!(xclass.get(field) instanceof PropertyClass)) {
            throw MCPObjectQuerySupport.unknownField(xclass, field);
        }
        xclass.removeField(field);
    }

    /**
     * Builds the reversibility disclosure of a field removal: an authorized-only count of the documents
     * whose objects of the class still hold a stored value under the removed field name. The count is a
     * best-effort informational note, so a failed count query is swallowed (logged at debug) and reported as
     * uncounted rather than failing the removal, which has already been applied.
     *
     * @param rowQuery the authorized HQL door
     * @param wiki the wiki the class lives in
     * @param localClassName the wiki-local serialized class name, bound (never interpolated)
     * @param field the removed field name, bound (never interpolated)
     * @param logger the calling tool's logger, for the debug trace of a failed count
     * @return the disclosure sentence
     */
    static String orphanDisclosure(MCPRowQuery rowQuery, WikiReference wiki, String localClassName,
        String field, Logger logger)
    {
        int viewable;
        boolean hitCeiling;
        try {
            Map<String, Object> binds = new LinkedHashMap<>();
            binds.put("className", localClassName);
            binds.put("field", field);
            List<?> rows = rowQuery.rows(ORPHAN_STATEMENT, wiki.getName(), binds,
                MCPRowQuery.MAX_FETCH_PER_QUERY);
            hitCeiling = rows.size() >= MCPRowQuery.MAX_FETCH_PER_QUERY;
            viewable = countViewable(rowQuery, rows, wiki);
        } catch (QueryException e) {
            logger.debug("MCP write_schema could not count orphaned values for class [{}] field [{}]",
                localClassName, field, e);
            return "Any values objects held under this field are hidden but not deleted - re-add the field "
                + "with a compatible type to restore them.";
        }
        if (viewable == 0 && !hitCeiling) {
            return "No object held a value under this field.";
        }
        return viewable + (hitCeiling ? "+" : "") + " object(s) held values under this field; they are now "
            + "hidden but not deleted - re-add the field with a compatible type to restore them.";
    }

    private static int countViewable(MCPRowQuery rowQuery, List<?> rows, WikiReference wiki)
    {
        int viewable = 0;
        for (Object row : rows) {
            String fullName = fullNameOf(row);
            if (fullName != null && rowQuery.authorizedDocument(fullName, wiki) != null) {
                viewable++;
            }
        }
        return viewable;
    }

    /**
     * @param row one raw result row: a single-column select shapes each row as the scalar itself, but the
     *     door's declared row type is erased, so the {@code Object[]} shape is handled defensively too
     * @return the row's document full name, or {@code null} when the row shape is unexpected
     */
    private static String fullNameOf(Object row)
    {
        if (row instanceof String fullName) {
            return fullName;
        }
        if (row instanceof Object[] columns && columns.length > 0 && columns[0] instanceof String fullName) {
            return fullName;
        }
        return null;
    }

    private static void addComputedField(BaseClass xclass, String field, String pretty)
    {
        ComputedFieldClass computed = new ComputedFieldClass();
        computed.setName(field);
        computed.setPrettyName(pretty);
        computed.setObject(xclass);
        xclass.addField(field, computed);
    }

    /**
     * Validates and applies the caller's attributes onto the property, each set as the meta-property named
     * by the type's allowed-attribute table and coerced to its stored form.
     *
     * @param property the field definition to configure
     * @param type the field type token, keying the allowed-attribute table
     * @param attributes the attributes to set, possibly {@code null} or empty
     * @throws IllegalArgumentException with an agent-facing message when the type accepts no attributes, an
     *     attribute is not valid for the type, or a value does not coerce to its stored form
     */
    private static void applyAttributes(PropertyClass property, String type, Map<String, String> attributes)
    {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        Map<String, Attr> allowed = ALLOWED.get(type);
        if (allowed == null) {
            throw new IllegalArgumentException("Field type " + QUOTE + strip(type) + QUOTE
                + " does not accept attributes.");
        }
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            Attr attr = allowed.get(entry.getKey());
            if (attr == null) {
                throw unknownAttribute(type, entry.getKey(), allowed);
            }
            setAttr(property, attr, entry.getValue());
        }
    }

    private static void setAttr(PropertyClass property, Attr attr, String raw)
    {
        switch (attr.kind()) {
            case INT -> property.setIntValue(attr.stored(), coerceInt(attr.name(), raw));
            case BOOL -> property.setIntValue(attr.stored(), coerceBool(attr.name(), raw));
            case STRING -> property.setStringValue(attr.stored(), raw);
            case LARGE_STRING -> property.setLargeStringValue(attr.stored(), raw);
            case CONTENT_TYPE -> property.setStringValue(attr.stored(), coerceContentType(attr.name(), raw));
            case EDITOR -> property.setStringValue(attr.stored(), coerceEditor(attr.name(), raw));
            default -> {
            }
        }
    }

    private static int coerceInt(String attrName, String raw)
    {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ATTRIBUTE_LABEL + QUOTE + strip(attrName) + QUOTE
                + " must be a whole number, got " + QUOTE + strip(raw) + QUOTE + PERIOD, e);
        }
    }

    private static int coerceBool(String attrName, String raw)
    {
        if ("1".equals(raw) || "true".equalsIgnoreCase(raw)) {
            return 1;
        }
        if ("0".equals(raw) || "false".equalsIgnoreCase(raw)) {
            return 0;
        }
        throw unknownTokenError(attrName, raw, "0, 1, true, false");
    }

    /**
     * Coerces a {@code contentType} attribute to the canonical stored platform vocabulary
     * ({@link MCPSchemaText#storedContentType(String)}). Storing the raw token would break the read side:
     * a display token like {@code plain} matches no platform content type, so the field would silently
     * render as wiki content.
     *
     * @param attrName the agent-facing attribute name, for the error message
     * @param raw the attribute value
     * @return the canonical stored value
     * @throws IllegalArgumentException listing the accepted display tokens when the value is in neither
     *     vocabulary
     */
    private static String coerceContentType(String attrName, String raw)
    {
        String stored = MCPSchemaText.storedContentType(raw);
        if (stored == null) {
            throw unknownTokenError(attrName, raw, MCPSchemaText.acceptedContentTypes());
        }
        return stored;
    }

    /**
     * Coerces an {@code editor} attribute to the canonical stored platform vocabulary
     * ({@link MCPSchemaText#storedEditor(String)}), for the same reason as
     * {@link #coerceContentType(String, String)}: an out-of-vocabulary stored editor matches no platform
     * editor type on read.
     *
     * @param attrName the agent-facing attribute name, for the error message
     * @param raw the attribute value
     * @return the canonical stored value
     * @throws IllegalArgumentException listing the accepted tokens when the value names no editor type
     */
    private static String coerceEditor(String attrName, String raw)
    {
        String stored = MCPSchemaText.storedEditor(raw);
        if (stored == null) {
            throw unknownTokenError(attrName, raw, MCPSchemaText.acceptedEditors());
        }
        return stored;
    }

    private static IllegalArgumentException unknownTokenError(String attrName, String raw, String accepted)
    {
        return new IllegalArgumentException(ATTRIBUTE_LABEL + QUOTE + strip(attrName) + QUOTE
            + " must be one of " + accepted + ", got " + QUOTE + strip(raw) + QUOTE + PERIOD);
    }

    private static IllegalArgumentException unknownType(String type)
    {
        return new IllegalArgumentException("Unknown field type " + QUOTE + strip(type)
            + QUOTE + ". Accepted types: " + acceptedTypes() + PERIOD);
    }

    private static IllegalArgumentException unknownAttribute(String type, String name, Map<String, Attr> allowed)
    {
        return new IllegalArgumentException(ATTRIBUTE_LABEL + QUOTE + strip(name) + QUOTE + " is not valid for a "
            + strip(type) + " field. Accepted attributes: " + String.join(LIST_SEPARATOR, allowed.keySet())
            + PERIOD);
    }

    private static String strip(String value)
    {
        return MCPTextGuards.fragment(value);
    }

    /**
     * The stored form of a property meta-property, deciding which typed setter carries an attribute value.
     *
     * @version $Id$
     */
    private enum AttrKind
    {
        /** An integer-valued meta-property (e.g. {@code size}, {@code rows}). */
        INT,

        /** A boolean-valued meta-property stored as {@code 0}/{@code 1} (e.g. {@code multiSelect}). */
        BOOL,

        /** A short-string meta-property (e.g. {@code numberType}, {@code displayType}). */
        STRING,

        /** A long-string meta-property (e.g. {@code sql}, {@code script}). */
        LARGE_STRING,

        /**
         * The text area's {@code contenttype} meta-property, normalized to the platform's stored
         * vocabulary (display and platform tokens accepted, anything else refused).
         */
        CONTENT_TYPE,

        /**
         * The text area's {@code editor} meta-property, normalized to the platform's stored vocabulary.
         */
        EDITOR
    }

    /**
     * One accepted attribute: the agent-facing name, the meta-property it sets (the same name unless the
     * platform stores it under a different key) and the stored form its value coerces to.
     *
     * @param name the agent-facing attribute name
     * @param stored the meta-property name the value is stored under
     * @param kind the stored form
     * @version $Id$
     */
    private record Attr(String name, String stored, AttrKind kind)
    {
        Attr(String name, AttrKind kind)
        {
            this(name, name, kind);
        }
    }

    /**
     * Creates one field of a given type on a class, with structural defaults, through the platform's own
     * {@code BaseClass} adders.
     *
     * @version $Id$
     */
    @FunctionalInterface
    private interface FieldFactory
    {
        /**
         * @param xclass the class definition to add the field to
         * @param field the field name
         * @param pretty the display name
         */
        void create(BaseClass xclass, String field, String pretty);
    }
}
