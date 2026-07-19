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

import org.apache.commons.lang3.StringUtils;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.BooleanClass;
import com.xpn.xwiki.objects.classes.DateClass;
import com.xpn.xwiki.objects.classes.ListClass;
import com.xpn.xwiki.objects.classes.NumberClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.classes.StaticListClass;
import com.xpn.xwiki.objects.classes.TextAreaClass;

/**
 * Renders a {@link BaseClass} (an XClass definition) into the plain-text field grammar of the
 * {@code get_schema} tool: one line per enabled field,
 * {@code name: Type(detail) "Pretty Name" modifiers [validation: regexp "message"]}, plus a
 * {@code DISABLED FIELDS} line naming the switched-off fields. Not a component: a plain holder of static
 * helpers, deliberately owning the whole {@code PropertyClass} type family so the dispatch table never
 * bloats the tool's own class fan-out (the same seam as {@link MCPRenderedHtml} and {@link MCPWriteSupport}).
 *
 * <p>Everything the grammar echoes is wiki-authored (field names, list values, pretty names, validation
 * regexps and messages), so every fragment is neutralized with {@link MCPToolSupport#stripLineBreaks(String)}
 * and length-capped before landing in a line: the tools treat line structure as trusted, so a newline
 * smuggled into a pretty name must not forge extra field lines, and a crafted oversized fragment (or static
 * list) must not dominate a line or blow up the response.</p>
 *
 * <p>Two deliberate omissions: there is no required-field flag (the XWiki class model has none), and the
 * admin-authored scripts behind {@code DBList}/{@code DBTreeList} queries and {@code ComputedField} fields
 * are never echoed - those fields carry a fixed trailing note instead.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
final class MCPSchemaText
{
    private static final String FIELDS_HEADER = "FIELDS (display order)";

    private static final String DISABLED_HEADER = "DISABLED FIELDS: ";

    private static final String INDENT = "  ";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String LIST_SEPARATOR = ", ";

    private static final String VALUE_SEPARATOR = "|";

    private static final String OPEN_PAREN = "(";

    private static final String CLOSE_PAREN = ")";

    private static final String SPACE = " ";

    /**
     * The {@link PropertyClass#getClassType()} values carrying a fixed trailing note instead of a detail
     * parenthetical: {@code Password} values never come back readable, {@code DBList}/{@code DBTreeList}
     * values come from an admin-authored database query (never echoed) and {@code ComputedField} values are
     * produced by an admin-authored script (never echoed, not writable).
     */
    private static final String PASSWORD_TYPE = "Password";

    private static final String DBLIST_TYPE = "DBList";

    private static final String DBTREELIST_TYPE = "DBTreeList";

    private static final String COMPUTED_FIELD_TYPE = "ComputedField";

    private static final String PASSWORD_NOTE = "(values masked in query results)";

    private static final String DATABASE_NOTE = "(values come from a database query)";

    private static final String COMPUTED_NOTE = "(computed by a script; read-only)";

    private static final String EXECUTABLE_NOTE = "(executable script)";

    private static final String DEFAULT_PREFIX = " default=";

    /**
     * Marker appended to a static list's {@code default=} modifier when the stored default is not among
     * the list's values (compared case-sensitively: a stored value only matches an allowed value when it
     * is byte-identical, so e.g. {@code How-To} against a {@code howto} value is a real mismatch the
     * agent must know about before echoing the default back in a write).
     */
    private static final String DEFAULT_NOT_AMONG_VALUES = " (not among the values)";

    /**
     * Cap on the joined static-list values detail: the values parenthetical is the only place one field
     * folds an unbounded wiki-authored collection into a single line, so a hostile editor storing megabytes
     * of list values must not blow up the response. Values are joined at value boundaries until the budget
     * is hit, then the list ends with {@link #VALUES_TRUNCATED_SUFFIX} instead of a cut value.
     */
    private static final int MAX_VALUES_CHARS = 300;

    private static final String VALUES_TRUNCATED_SUFFIX = MCPTextGuards.ELLIPSIS + " (values truncated)";

    /**
     * The rendered details of a {@code TextArea}'s content types: the stored values
     * ({@code FullyRenderedText}, {@code PureText}, {@code VelocityCode}, {@code VelocityWiki}) would read
     * as noise in the grammar, so each {@link TextAreaClass.ContentType} renders as a short name of what
     * the field actually holds.
     */
    private static final String WIKI_CONTENT_DETAIL = "wiki";

    /**
     * See {@link #WIKI_CONTENT_DETAIL}.
     */
    private static final String PLAIN_CONTENT_DETAIL = "plain";

    /**
     * See {@link #WIKI_CONTENT_DETAIL}.
     */
    private static final String VELOCITY_CODE_DETAIL = "velocityCode";

    /**
     * See {@link #WIKI_CONTENT_DETAIL}.
     */
    private static final String VELOCITY_WIKI_DETAIL = "velocityWiki";

    /**
     * The placeholder the class editor stores in a {@code TextArea}'s {@code contenttype} and
     * {@code editor} meta-properties when nothing is selected: the platform's {@code TextAreaMetaClass}
     * defines {@code ---} as the first (default) value of both select lists, so an untouched field stores
     * the literal placeholder rather than an empty string.
     */
    private static final String UNSET_META_VALUE = "---";

    private MCPSchemaText()
    {
    }

    /**
     * @param xclass the class definition to probe
     * @return whether the class defines no fields at all (neither enabled nor disabled), i.e. the document
     *     is not a class definition - {@code XWikiDocument#getXClass()} always returns an instance, so an
     *     ordinary page is detected by its emptiness
     */
    static boolean definesNoFields(BaseClass xclass)
    {
        return xclass.getEnabledProperties().isEmpty() && xclass.getDisabledProperties().isEmpty();
    }

    /**
     * Renders the class's field definitions: the {@code FIELDS} block (one grammar line per enabled field,
     * in display order - {@link BaseClass#getEnabledProperties()} already sorts them) and, when the class
     * has disabled fields, a blank line and the {@code DISABLED FIELDS} line (names only).
     *
     * @param xclass the class definition to render
     * @param context the XWiki context, needed to expand a static list's values
     * @return the rendered block, without a trailing newline
     */
    static String render(BaseClass xclass, XWikiContext context)
    {
        StringBuilder sb = new StringBuilder(FIELDS_HEADER);
        for (PropertyClass property : xclass.getEnabledProperties()) {
            sb.append(NEW_LINE).append(INDENT).append(fieldLine(property, context));
        }
        List<PropertyClass> disabled = xclass.getDisabledProperties();
        if (!disabled.isEmpty()) {
            List<String> names = new ArrayList<>(disabled.size());
            for (PropertyClass property : disabled) {
                names.add(strip(property.getName()));
            }
            sb.append(NEW_LINE).append(NEW_LINE).append(DISABLED_HEADER)
                .append(String.join(LIST_SEPARATOR, names));
        }
        return sb.toString();
    }

    /**
     * Renders one field definition into the grammar line ({@link #fieldLine(PropertyClass, XWikiContext)}),
     * for the schema-writing tool's success output: the same line the {@code get_schema} FIELDS block would
     * show, without indentation.
     *
     * @param property the field definition to render
     * @param context the XWiki context, needed to expand a static list's values
     * @return the composed grammar line, without indentation or a trailing newline
     */
    static String renderField(PropertyClass property, XWikiContext context)
    {
        return fieldLine(property, context);
    }

    /**
     * Composes one field line of the grammar: the name, the type with its optional detail parenthetical,
     * the quoted pretty name (only when non-blank and different from the name), the modifiers, the fixed
     * trailing note of the opaque types, and the validation block.
     *
     * @param property the field definition
     * @param context the XWiki context, needed to expand a static list's values
     * @return the composed line, without indentation
     */
    private static String fieldLine(PropertyClass property, XWikiContext context)
    {
        String name = strip(property.getName());
        String type = property.getClassType();
        StringBuilder line = new StringBuilder(name).append(": ").append(type);

        String detail = detail(property, context);
        if (detail != null) {
            line.append(OPEN_PAREN).append(detail).append(CLOSE_PAREN);
        }

        String pretty = strip(property.getPrettyName());
        if (StringUtils.isNotBlank(pretty) && !pretty.equals(name)) {
            line.append(SPACE).append(QUOTE).append(pretty).append(QUOTE);
        }

        appendModifiers(line, property, context);

        String note = note(property, type);
        if (note != null) {
            line.append(SPACE).append(note);
        }

        appendValidation(line, property);
        return line.toString();
    }

    /**
     * The detail parenthetical of the types that carry one: a static list's values, a number's storage
     * type, a boolean's display type, a date's format and a text area's content kind. Dispatching on
     * {@link PropertyClass#getClassType()} (not {@code instanceof}) keeps subtypes out: {@code Timezone}
     * extends the string family and {@code DBList} extends the list family, and neither must inherit a
     * parenthetical meant for its parent type. Each per-type helper still guards its cast with a pattern,
     * so a third-party {@code PropertyClass} subclass whose class type collides with a platform name
     * degrades to no detail instead of a {@link ClassCastException}.
     *
     * @param property the field definition
     * @param context the XWiki context, needed to expand a static list's values
     * @return the detail, or {@code null} when the type carries none
     */
    private static String detail(PropertyClass property, XWikiContext context)
    {
        return switch (property.getClassType()) {
            case "StaticList" -> staticListDetail(property, context);
            case "Number" -> numberDetail(property);
            case "Boolean" -> booleanDetail(property);
            case "Date" -> dateDetail(property);
            case "TextArea" -> textAreaDetail(property);
            default -> null;
        };
    }

    /**
     * @param property the field definition, guarded to really be a {@link StaticListClass}
     * @param context the XWiki context passed to {@link StaticListClass#getList(XWikiContext)}
     * @return the list's value ids joined with {@code |}, budgeted at {@link #MAX_VALUES_CHARS} (joined at
     *     value boundaries; a list that does not fit ends with the truncation suffix); the platform call
     *     already returns the ids in display order (honoring the sort setting) and stripped of their
     *     display labels
     */
    private static String staticListDetail(PropertyClass property, XWikiContext context)
    {
        if (!(property instanceof StaticListClass staticList)) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (String value : staticList.getList(context)) {
            String clean = strip(value);
            int separatorLength = joined.length() == 0 ? 0 : VALUE_SEPARATOR.length();
            if (joined.length() + separatorLength + clean.length() > MAX_VALUES_CHARS) {
                joined.append(VALUES_TRUNCATED_SUFFIX);
                break;
            }
            if (separatorLength > 0) {
                joined.append(VALUE_SEPARATOR);
            }
            joined.append(clean);
        }
        return joined.toString();
    }

    /**
     * @param property the field definition, guarded to really be a {@link NumberClass}
     * @return the number's storage type
     */
    private static String numberDetail(PropertyClass property)
    {
        return property instanceof NumberClass number ? strip(number.getNumberType()) : null;
    }

    /**
     * @param property the field definition, guarded to really be a {@link BooleanClass}
     * @return the boolean's display type
     */
    private static String booleanDetail(PropertyClass property)
    {
        return property instanceof BooleanClass bool ? strip(bool.getDisplayType()) : null;
    }

    /**
     * @param property the field definition, guarded to really be a {@link DateClass}
     * @return the date's format
     */
    private static String dateDetail(PropertyClass property)
    {
        return property instanceof DateClass date ? strip(date.getDateFormat()) : null;
    }

    /**
     * The content-kind detail of a text area, resolved the way the platform resolves what the field
     * holds. A stored content type in the platform vocabulary maps to its short name
     * ({@link #contentTypeDetail(TextAreaClass.ContentType)}). The class editor's unset placeholder
     * ({@link #UNSET_META_VALUE}) is resolved through the {@code editor} meta-property with the
     * platform's own compatibility rule ({@link TextAreaClass#getContentType(TextAreaClass.EditorType,
     * TextAreaClass.ContentType)}): every editor except pure text implies wiki content - matching
     * {@code TextAreaClass#isWikiContent}, which treats the placeholder as wiki content - while a pure
     * text editor is compatible with several content kinds, so the detail is omitted entirely rather than
     * guessed. A custom stored value outside the platform vocabulary is echoed as-is (lowercased by the
     * platform getter).
     *
     * @param property the field definition, guarded to really be a {@link TextAreaClass}
     * @return the content-kind detail, or {@code null} when the content kind is genuinely undecidable
     */
    private static String textAreaDetail(PropertyClass property)
    {
        if (!(property instanceof TextAreaClass textArea)) {
            return null;
        }
        String stored = textArea.getContentType();
        TextAreaClass.ContentType contentType = TextAreaClass.ContentType.getByValue(stored);
        if (contentType == null) {
            if (!UNSET_META_VALUE.equals(stored)) {
                return strip(stored);
            }
            contentType = TextAreaClass.getContentType(
                TextAreaClass.EditorType.getByValue(textArea.getEditor()), null);
        }
        return contentType == null ? null : contentTypeDetail(contentType);
    }

    /**
     * @param contentType the resolved content type
     * @return the short detail name of the content type (see {@link #WIKI_CONTENT_DETAIL})
     */
    private static String contentTypeDetail(TextAreaClass.ContentType contentType)
    {
        return switch (contentType) {
            case WIKI_TEXT -> WIKI_CONTENT_DETAIL;
            case PURE_TEXT -> PLAIN_CONTENT_DETAIL;
            case VELOCITY_CODE -> VELOCITY_CODE_DETAIL;
            case VELOCITYWIKI -> VELOCITY_WIKI_DETAIL;
        };
    }

    /**
     * Maps a {@code contentType} attribute value of the schema-writing tool to the canonical stored
     * platform value ({@link TextAreaClass.ContentType#toString()}, the exact form the platform's own
     * {@code TextAreaClass#setContentType(TextAreaClass.ContentType)} stores). Both vocabularies are
     * accepted, case-insensitively: the display tokens this class renders in a schema line ({@code plain},
     * {@code wiki}, {@code velocityCode}, {@code velocityWiki} - what {@code get_schema} shows, so an agent
     * echoing a schema line back is understood) and the platform's stored tokens ({@code PureText},
     * {@code FullyRenderedText}, {@code VelocityCode}, {@code VelocityWiki}). Anything else maps to
     * {@code null} and must be refused by the caller: an out-of-vocabulary stored value matches no
     * {@link TextAreaClass.ContentType} on read and the field silently renders as wiki content.
     *
     * @param raw the attribute value
     * @return the canonical stored value, or {@code null} when the token is in neither vocabulary
     */
    static String storedContentType(String raw)
    {
        TextAreaClass.ContentType contentType = displayedContentType(raw);
        if (contentType == null) {
            contentType = TextAreaClass.ContentType.getByValue(raw);
        }
        return contentType == null ? null : contentType.toString();
    }

    /**
     * @return the accepted {@code contentType} tokens for a teaching error, in display vocabulary (the
     *     form {@code get_schema} shows), in the order this class renders them
     */
    static String acceptedContentTypes()
    {
        return String.join(LIST_SEPARATOR, PLAIN_CONTENT_DETAIL, WIKI_CONTENT_DETAIL, VELOCITY_CODE_DETAIL,
            VELOCITY_WIKI_DETAIL);
    }

    /**
     * The reverse of {@link #contentTypeDetail(TextAreaClass.ContentType)}: resolves a display token,
     * case-insensitively.
     *
     * @param raw the attribute value
     * @return the content type the display token names, or {@code null} when it names none
     */
    private static TextAreaClass.ContentType displayedContentType(String raw)
    {
        if (PLAIN_CONTENT_DETAIL.equalsIgnoreCase(raw)) {
            return TextAreaClass.ContentType.PURE_TEXT;
        }
        if (WIKI_CONTENT_DETAIL.equalsIgnoreCase(raw)) {
            return TextAreaClass.ContentType.WIKI_TEXT;
        }
        if (VELOCITY_CODE_DETAIL.equalsIgnoreCase(raw)) {
            return TextAreaClass.ContentType.VELOCITY_CODE;
        }
        if (VELOCITY_WIKI_DETAIL.equalsIgnoreCase(raw)) {
            return TextAreaClass.ContentType.VELOCITYWIKI;
        }
        return null;
    }

    /**
     * Maps an {@code editor} attribute value of the schema-writing tool to the canonical stored platform
     * value ({@link TextAreaClass.EditorType#toString()}), matched case-insensitively against the
     * platform's editor vocabulary ({@code PureText}, {@code Text}, {@code Wysiwyg}).
     *
     * @param raw the attribute value
     * @return the canonical stored value, or {@code null} when the token names no editor type
     */
    static String storedEditor(String raw)
    {
        TextAreaClass.EditorType editor = TextAreaClass.EditorType.getByValue(raw);
        return editor == null ? null : editor.toString();
    }

    /**
     * @return the accepted {@code editor} tokens for a teaching error, from the platform's own vocabulary
     */
    static String acceptedEditors()
    {
        List<String> tokens = new ArrayList<>();
        for (TextAreaClass.EditorType editor : TextAreaClass.EditorType.values()) {
            tokens.add(editor.toString());
        }
        return String.join(LIST_SEPARATOR, tokens);
    }

    /**
     * Appends the space-separated modifiers: {@code multiselect} for a multi-select list, and
     * {@code default=<value>} for a list with a non-blank default or a boolean with an explicit default
     * ({@link BooleanClass#getDefaultValue()} returns {@code -1} for "no default"). A static list's
     * default additionally carries the {@link #DEFAULT_NOT_AMONG_VALUES} marker when it does not match
     * any allowed value exactly (case-sensitive - the stored default must be byte-identical to a value
     * to be usable), so the agent is never taught an unusable default. Only static lists get the check:
     * a database list's values come from an admin-authored query that is never executed here.
     *
     * @param line the line being composed
     * @param property the field definition
     * @param context the XWiki context, needed to expand a static list's values for the default check
     */
    private static void appendModifiers(StringBuilder line, PropertyClass property, XWikiContext context)
    {
        if (property instanceof ListClass list) {
            if (list.isMultiSelect()) {
                line.append(" multiselect");
            }
            if (StringUtils.isNotBlank(list.getDefaultValue())) {
                line.append(DEFAULT_PREFIX).append(strip(list.getDefaultValue()));
                if (property instanceof StaticListClass staticList
                    && !staticList.getList(context).contains(list.getDefaultValue())) {
                    line.append(DEFAULT_NOT_AMONG_VALUES);
                }
            }
        } else if (property instanceof BooleanClass bool && bool.getDefaultValue() != -1) {
            line.append(DEFAULT_PREFIX).append(bool.getDefaultValue());
        }
    }

    /**
     * The fixed trailing note of the opaque types, replacing the detail their stored definition would
     * leak: a password's values are masked, a database list's query and a computed field's script are
     * admin-authored code and are never echoed, and a Velocity-content text area executes as script.
     *
     * @param property the field definition
     * @param type the field's class type
     * @return the note, or {@code null} when the type carries none
     */
    private static String note(PropertyClass property, String type)
    {
        if (PASSWORD_TYPE.equals(type)) {
            return PASSWORD_NOTE;
        }
        if (DBLIST_TYPE.equals(type) || DBTREELIST_TYPE.equals(type)) {
            return DATABASE_NOTE;
        }
        if (COMPUTED_FIELD_TYPE.equals(type)) {
            return COMPUTED_NOTE;
        }
        if (property instanceof TextAreaClass textArea && isVelocityContent(textArea)) {
            return EXECUTABLE_NOTE;
        }
        return null;
    }

    /**
     * @param textArea the text area definition
     * @return whether the stored content type indicates Velocity content ({@code VelocityCode} or
     *     {@code VelocityWiki}, matched through {@link TextAreaClass.ContentType#getByValue(String)})
     */
    private static boolean isVelocityContent(TextAreaClass textArea)
    {
        TextAreaClass.ContentType contentType =
            TextAreaClass.ContentType.getByValue(textArea.getContentType());
        return contentType == TextAreaClass.ContentType.VELOCITY_CODE
            || contentType == TextAreaClass.ContentType.VELOCITYWIKI;
    }

    /**
     * Appends the validation block when the field has a validation regexp: {@code [validation: <regexp>]},
     * with the validation message quoted inside the brackets when one is set.
     *
     * @param line the line being composed
     * @param property the field definition
     */
    private static void appendValidation(StringBuilder line, PropertyClass property)
    {
        String regexp = property.getValidationRegExp();
        if (StringUtils.isBlank(regexp)) {
            return;
        }
        line.append(" [validation: ").append(strip(regexp));
        String message = property.getValidationMessage();
        if (StringUtils.isNotBlank(message)) {
            line.append(SPACE).append(QUOTE).append(strip(message)).append(QUOTE);
        }
        line.append(']');
    }

    /**
     * @param value the wiki-authored fragment, possibly {@code null}
     * @return the fragment neutralized and length-capped by the shared guard
     *     ({@link MCPTextGuards#fragment(String)}); {@code null} stays {@code null}
     */
    private static String strip(String value)
    {
        return MCPTextGuards.fragment(value);
    }
}
