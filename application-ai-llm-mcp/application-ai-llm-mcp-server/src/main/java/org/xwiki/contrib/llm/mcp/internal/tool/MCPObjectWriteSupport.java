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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.DateClass;
import com.xpn.xwiki.objects.classes.PropertyClass;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Shared object-write machinery of the object-writing MCP tools ({@link MCPWriteObjectTool},
 * {@link MCPDeleteObjectTool}): class resolution into the target document's wiki, the sensitive-document
 * and sensitive-class refusals, object lookup by number (with null-hole handling and an existing-numbers
 * echo), and field validation, coercion and setting. Not a component: a plain holder of static helpers,
 * deliberately owning the whole {@code BaseObject}/{@code BaseProperty}/{@code PropertyClass} family so
 * the type dispatch never bloats the tools' own class fan-out (the same seam as
 * {@link MCPObjectQuerySupport} on the read side).
 *
 * <p>Field values arrive as strings and are coerced with the platform's own semantics
 * ({@link PropertyClass#fromString(String)}, which returns {@code null} on an unparseable Number or
 * Date), with the read side's Boolean pre-validation on top (the platform parse cannot signal failure
 * for Booleans: garbage would silently store a {@code null} value). Refusals are validation errors,
 * raised before anything is mutated: unknown fields (the error lists the class's fields), Password
 * fields (a prompt-injected password change is an account-takeover vector, so passwords are only
 * settable in the wiki UI) and computed fields (no stored value).</p>
 *
 * <p>A validated value is applied by wiring the {@link BaseProperty} that {@code fromString} returned
 * onto the object exactly the way the platform's own {@code BaseClass#fromMap} does
 * ({@code property.setObject(object)} then {@code object.safeput(name, property)}). This was chosen
 * over re-parsing the validated string through {@code BaseObject.set} because it stores the exact
 * property instance the validation step inspected - there is no second parse whose semantics could
 * diverge from what was validated.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
final class MCPObjectWriteSupport
{
    /**
     * The wiki-local full names of the classes whose objects ARE access rights, group membership, user
     * accounts or wiki configuration - each an indirect route to the privileges the tool must not grant:
     * page-level rights objects ({@code XWiki.XWikiRights} - a page's local rights live as objects on
     * that very page, so the sensitive-document denylist alone cannot protect them), wiki-level rights
     * objects ({@code XWiki.XWikiGlobalRights}), group membership objects ({@code XWiki.XWikiGroups} - a
     * membership is an object on the group document, so a write could add an account to a privileged
     * group), user account objects ({@code XWiki.XWikiUsers} - a write could provision an account or
     * flip its active/email fields), wiki descriptor objects ({@code XWiki.XWikiServerClass}) and the MCP
     * server's own configuration objects (which would let an agent reconfigure its own endpoint).
     */
    private static final Set<String> SENSITIVE_CLASSES = Set.of("XWiki.XWikiRights", "XWiki.XWikiGlobalRights",
        "XWiki.XWikiGroups", "XWiki.XWikiUsers", "XWiki.XWikiServerClass", "AI.MCP.Code.MCPServerConfigClass");

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String CANNOT_SET_PREFIX = "Cannot set";

    private static final String FIELD_INFIX = " field " + QUOTE;

    private static final String NO_OBJECT_PREFIX = "No object of class " + QUOTE;

    /**
     * The tail shared by the unsettable-field refusals, following the write-path convention: what
     * failed, that nothing was saved, and the corrective next action.
     */
    private static final String NOTHING_SAVED_TAIL = " Nothing was saved - omit the field and retry.";

    private MCPObjectWriteSupport()
    {
    }

    /**
     * Resolves and authorizes the {@code class} argument in the target document's wiki, for
     * {@link Right#VIEW} (the field definitions are read to validate the call). Classes are wiki-local
     * ({@code XWikiDocument.newXObject} strips any wiki part from a class reference), so the resolution
     * is anchored to the target document's wiki and the door's wiki-anchored variant refuses a reference
     * whose explicit wiki prefix contradicts it - a cross-wiki class is impossible, and the agent is told
     * to drop the prefix rather than getting a silently re-anchored class.
     *
     * @param documentAccess the resolution and authorization component of the calling tool
     * @param classReference the raw {@code class} argument
     * @param targetDocument the resolved reference of the document holding the objects
     * @return the resolved and authorized class reference, always in the target document's wiki
     * @throws IllegalArgumentException when the reference is malformed, carries a contradicting wiki
     *     prefix, is filtered out or is not viewable by the calling user, with the agent-facing message
     *     as the exception message
     */
    static DocumentReference resolveClass(MCPDocumentAccess documentAccess, String classReference,
        DocumentReference targetDocument)
    {
        try {
            return documentAccess.resolveAndAuthorize(classReference, Right.VIEW,
                targetDocument.getWikiReference());
        } catch (MCPAccessDeniedException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Builds the refusal shared by the object-writing tools, or returns {@code null} when the write may
     * proceed: first the sensitive-document denylist ({@link MCPWriteSupport#isSensitiveDocument} - the
     * decision is made from the reference alone, never from content), then the sensitive-class denylist
     * ({@link #SENSITIVE_CLASSES}, compared on the wiki-local serialized class name). The document's
     * manual-change URL is built only when the document check fires, never on the common allowed path.
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param ref the resolved reference of the document holding the objects
     * @param classRef the resolved class reference
     * @param serializer the calling tool's canonical reference serializer
     * @param localSerializer the calling tool's wiki-local reference serializer
     * @param documentUrl supplies the target document's view URL for the manual-change pointer, called
     *     only when the document is denylisted; may yield {@code null} when no URL could be built
     * @return the refusal result, or {@code null} when the write may proceed
     */
    static McpSchema.CallToolResult sensitiveRefusal(XWikiContext xcontext, DocumentReference ref,
        DocumentReference classRef, EntityReferenceSerializer<String> serializer,
        EntityReferenceSerializer<String> localSerializer, Supplier<String> documentUrl)
    {
        if (MCPWriteSupport.isSensitiveDocument(xcontext, ref, localSerializer)) {
            String url = documentUrl.get();
            return MCPToolSupport.errorResult("Refusing to change objects on " + QUOTE
                + MCPTextGuards.fragment(serializer.serialize(ref)) + QUOTE
                + ": this page defines access rights or wiki configuration. If you really intend to "
                + "change it, do it manually in the wiki UI" + (url != null ? ": " + url : PERIOD));
        }
        String localClassName = localSerializer.serialize(classRef);
        if (isSensitiveClass(localClassName)) {
            return MCPToolSupport.errorResult("Refusing to change objects of class " + QUOTE
                + MCPTextGuards.fragment(localClassName) + QUOTE
                + ": objects of this class define access rights, group membership, user accounts or wiki "
                + "configuration. Manage them in the wiki UI.");
        }
        return null;
    }

    /**
     * Decides the sensitive-class denylist ({@link #SENSITIVE_CLASSES}) from the wiki-local serialized
     * class name alone. Shared with {@code write_schema}, which refuses to redefine these classes at the
     * schema level for the same reason the object-writing tools refuse to write their objects: their
     * definitions govern access rights, group membership, user accounts or wiki configuration.
     *
     * @param localClassName the wiki-local serialized class name
     * @return whether the class is denylisted
     */
    static boolean isSensitiveClass(String localClassName)
    {
        return SENSITIVE_CLASSES.contains(localClassName);
    }

    /**
     * @param classDoc the loaded class document
     * @param localClassName the wiki-local serialized class name, for the error message
     * @throws IllegalArgumentException when the document defines no class fields at all, i.e. it is not
     *     a class definition
     */
    static void requireClassFields(XWikiDocument classDoc, String localClassName)
    {
        if (MCPObjectQuerySupport.definesNoFields(classDoc)) {
            throw new IllegalArgumentException("Document " + QUOTE + MCPTextGuards.fragment(localClassName)
                + QUOTE + " exists but defines no class fields. Use get_schema with no arguments to list "
                + "the classes of this wiki.");
        }
    }

    /**
     * Validates and applies one object write on the given (already cloned-for-edit) document: every
     * field entry is validated and coerced against the class definition BEFORE the object is located or
     * created, so a validation failure mutates nothing; then the object is created (number absent) or
     * looked up by number, and each coerced property is wired onto it.
     *
     * @param editable the tool's editable copy of the target document
     * @param classDoc the loaded class document
     * @param classRef the resolved class reference
     * @param localClassName the wiki-local serialized class name, for the error messages
     * @param objectNumber the number of the object to update, or {@code null} to create a new object
     * @param fields the field name to raw string value entries
     * @param xcontext the XWiki context, switched to the target wiki
     * @return what was applied: the object's number, whether it was created and the (neutralized) names
     *     of the fields set
     * @throws IllegalArgumentException with an agent-facing message on any validation failure
     * @throws XWikiException when creating the new object instance fails
     */
    static AppliedWrite applyFields(XWikiDocument editable, XWikiDocument classDoc, DocumentReference classRef,
        String localClassName, Integer objectNumber, Map<String, String> fields, XWikiContext xcontext)
        throws XWikiException
    {
        requireClassFields(classDoc, localClassName);
        BaseClass xclass = classDoc.getXClass();
        Map<String, BaseProperty> validated = new LinkedHashMap<>();
        List<String> fieldNames = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            validated.put(entry.getKey(), validateAndCoerce(xclass, entry.getKey(), entry.getValue()));
            fieldNames.add(MCPTextGuards.fragment(entry.getKey()));
        }
        boolean created = objectNumber == null;
        BaseObject object;
        if (created) {
            object = editable.newXObject(classRef, xcontext);
        } else {
            object = objectAt(editable, classRef, localClassName, objectNumber);
        }
        for (Map.Entry<String, BaseProperty> entry : validated.entrySet()) {
            BaseProperty property = entry.getValue();
            property.setObject(object);
            object.safeput(entry.getKey(), property);
        }
        return new AppliedWrite(object.getNumber(), created, fieldNames);
    }

    /**
     * Validates, read-only, that an object of the given class exists at the given number on the document,
     * so the caller can remove it through the {@link com.xpn.xwiki.api.Document} wrapper without first
     * mutating the (possibly cache-shared) document itself. Reads the document only: no clone, no removal.
     * The removal is performed by the caller through the api layer, whose own lazy clone is the
     * cache-safety barrier.
     *
     * @param xdoc the document holding the objects, read only
     * @param classRef the resolved class reference
     * @param localClassName the wiki-local serialized class name, for the error messages
     * @param objectNumber the number of the object to remove
     * @throws IllegalArgumentException with the non-negative refusal for a negative number, or the
     *     no-object message (listing the existing numbers) when no object of the class exists at that
     *     number
     */
    static void requireObjectExists(XWikiDocument xdoc, DocumentReference classRef, String localClassName,
        int objectNumber)
    {
        objectAt(xdoc, classRef, localClassName, objectNumber);
    }

    /**
     * Looks up the object of the given class at the given number. A negative number is refused before
     * the platform lookup (which would throw an {@link IndexOutOfBoundsException} for it), and a number
     * beyond the list or landing on a null hole (a previously removed object's slot) yields the
     * no-object message listing the numbers that do exist.
     *
     * @param xdoc the document holding the objects
     * @param classRef the resolved class reference
     * @param localClassName the wiki-local serialized class name, for the error messages
     * @param objectNumber the requested object number
     * @return the object, never {@code null}
     * @throws IllegalArgumentException with an agent-facing message when there is no such object
     */
    private static BaseObject objectAt(XWikiDocument xdoc, DocumentReference classRef, String localClassName,
        int objectNumber)
    {
        if (objectNumber < 0) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX
                + "object' must be a non-negative object number (query_objects shows the numbers).");
        }
        BaseObject object = xdoc.getXObject(classRef, objectNumber);
        if (object == null) {
            throw new IllegalArgumentException(noObjectMessage(xdoc, classRef, localClassName, objectNumber));
        }
        return object;
    }

    /**
     * Builds the no-object message, listing the numbers at which objects of the class DO exist (skipping
     * null holes) so the agent can correct the call without another query.
     *
     * @param xdoc the document holding the objects
     * @param classRef the resolved class reference
     * @param localClassName the wiki-local serialized class name
     * @param objectNumber the requested object number
     * @return the agent-facing message
     */
    static String noObjectMessage(XWikiDocument xdoc, DocumentReference classRef, String localClassName,
        int objectNumber)
    {
        List<String> numbers = new ArrayList<>();
        List<BaseObject> objects = xdoc.getXObjects(classRef);
        if (objects != null) {
            for (int i = 0; i < objects.size(); i++) {
                if (objects.get(i) != null) {
                    numbers.add(String.valueOf(i));
                }
            }
        }
        String base = NO_OBJECT_PREFIX + MCPTextGuards.fragment(localClassName) + QUOTE + " at number "
            + objectNumber + PERIOD;
        if (numbers.isEmpty()) {
            return base + " The document has no objects of this class.";
        }
        return base + " Existing object numbers: " + String.join(", ", numbers) + PERIOD;
    }

    /**
     * Validates one field entry against the class definition and coerces its value with the field's own
     * type semantics: an unknown field, a Password field, a computed field, an out-of-vocabulary Boolean
     * or a value the platform parse rejects ({@code fromString} returning {@code null} - the 17.4 failure
     * signal for Number and Date garbage) are all refused with a teaching message. Multi-select list
     * values are allowed: the platform parse handles the {@code |} and {@code ,} separators itself.
     *
     * @param xclass the class definition
     * @param name the field name
     * @param value the raw string value
     * @return the coerced property, ready to be wired onto the object
     * @throws IllegalArgumentException with an agent-facing message on any validation failure
     */
    private static BaseProperty validateAndCoerce(BaseClass xclass, String name, String value)
    {
        if (!(xclass.get(name) instanceof PropertyClass property)) {
            throw MCPObjectQuerySupport.unknownField(xclass, name);
        }
        String type = property.getClassType();
        if (MCPObjectQuerySupport.PASSWORD_TYPE.equals(type)) {
            throw new IllegalArgumentException(CANNOT_SET_PREFIX + " Password" + FIELD_INFIX
                + MCPTextGuards.fragment(name) + QUOTE + ": set passwords via the wiki UI."
                + NOTHING_SAVED_TAIL);
        }
        if (MCPObjectQuerySupport.COMPUTED_TYPE.equals(type)) {
            throw new IllegalArgumentException(CANNOT_SET_PREFIX + FIELD_INFIX + MCPTextGuards.fragment(name)
                + QUOTE + ": it is computed by the class's script and has no stored value."
                + NOTHING_SAVED_TAIL);
        }
        if (MCPObjectQuerySupport.BOOLEAN_TYPE.equals(type)) {
            // Booleans are the one type whose platform parse cannot fail (garbage stores a null value),
            // so the vocabulary is pre-validated; the coerced bind value itself is not needed here.
            MCPObjectQuerySupport.booleanBind(property, value);
        }
        BaseProperty parsed = MCPObjectQuerySupport.parseOrNull(property, value);
        if (parsed == null) {
            throw MCPObjectQuerySupport.invalidValue(property, value, expectedWriteDetail(property));
        }
        return parsed;
    }

    /**
     * @param property the field's definition
     * @return the expected-value description for a write validation error: the class's own date format
     *     for a Date field (the write parse accepts only that format), the read side's detail otherwise
     */
    private static String expectedWriteDetail(PropertyClass property)
    {
        if (property instanceof DateClass date) {
            return "a date matching the class's date format " + QUOTE
                + MCPTextGuards.fragment(date.getDateFormat()) + QUOTE;
        }
        return MCPObjectQuerySupport.expectedDetail(property);
    }

    /**
     * One applied object write, as needed by the calling tool's comment and result rendering.
     *
     * @param number the object's number on the document
     * @param createdObject whether the object was created rather than updated
     * @param fieldNames the names of the fields set, in call order, already neutralized and clamped for
     *     line-oriented output
     * @version $Id$
     */
    record AppliedWrite(int number, boolean createdObject, List<String> fieldNames)
    {
    }
}
