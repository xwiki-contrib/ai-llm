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

import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPReachAwareParams;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that creates or changes an XClass definition: add a field, modify a field's display name and
 * attributes, or remove a field. The capstone of the structured-data tool family - {@code get_schema} reads
 * the same grammar this tool writes, and its output doubles as this tool's input.
 *
 * <p>This is a default tool bundled with the MCP server module, off by default until an admin enables
 * it like every other write tool. It changes the schema every object of a class obeys. There is no
 * separate create operation - a zero-field class is indistinguishable from an ordinary document, so the
 * first {@code add_field} on a fieldless or new document is what creates the class. Removing a field or
 * changing its type does not delete the object values keyed by that field name; they persist as orphaned
 * rows and reappear if the field is re-added with a compatible type, so the change is reversible and the
 * success message says so.</p>
 *
 * <p>The document is resolved and authorized for the edit right through {@link MCPDocumentAccess} (so the
 * per-wiki space filter is applied and a protected document is never leaked), then rights- and
 * configuration-bearing documents and classes are refused outright (the sensitive-document and
 * sensitive-class denylists shared with the object-writing tools). The change is applied to the tool's own
 * clone of the loaded document and saved through {@link com.xpn.xwiki.api.Document}, so author attribution
 * and save-time rights apply - an agent authoring a computed field can never make it run above its own
 * rights. All the {@code BaseClass}/{@code PropertyClass} type dispatch lives in
 * {@link MCPSchemaWriteSupport} so this tool's own class fan-out stays bounded.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPWriteSchemaTool.TOOL_ID)
@Singleton
public class MCPWriteSchemaTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "write_schema";

    private static final String REFERENCE_PARAM = "reference";

    private static final String OPERATION_PARAM = "operation";

    private static final String FIELD_PARAM = "field";

    private static final String TYPE_PARAM = "type";

    private static final String PRETTY_NAME_PARAM = "pretty_name";

    private static final String ATTRIBUTES_PARAM = "attributes";

    private static final String BASE_VERSION_PARAM = "base_version";

    private static final String COMMENT_PARAM = "comment";

    private static final String MAJOR_PARAM = "major";

    private static final String ADD_FIELD = "add_field";

    private static final String MODIFY_FIELD = "modify_field";

    private static final String REMOVE_FIELD = "remove_field";

    private static final Set<String> OPERATIONS = Set.of(ADD_FIELD, MODIFY_FIELD, REMOVE_FIELD);

    private static final String NEW_LINE = "\n";

    private static final String INDENT = "  ";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String CLASS_INFIX = " class " + QUOTE;

    private static final String FIELD_PREFIX = "field " + QUOTE;

    private static final String ARROW = " -> ";

    private static final String REFUSING_PREFIX = "Refusing to change the definition of ";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description so
     * no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPWriteSchemaTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private MCPRowQuery rowQuery;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private MCPWikiReach wikiReach;

    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The class document to define or change - this document IS the class, "
            + "e.g. \"MyApp.MyClass\" or \"" + (crossWiki ? "xwiki:" : "") + "MyApp.MyClass\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(OPERATION_PARAM, "One of add_field (add a new field, creating the class on a "
                + "fieldless or new document), modify_field (change an existing field's display name and "
                + "attributes) or remove_field (remove a field).")
            .requiredString(FIELD_PARAM, "The field (property) name to add, modify or remove.")
            .string(TYPE_PARAM, "The field type, REQUIRED for add_field and refused otherwise. One of: "
                + MCPSchemaWriteSupport.acceptedTypes() + ". Changing a field's type is remove_field then "
                + "add_field. get_schema shows the type of each existing field.")
            .string(PRETTY_NAME_PARAM, "The field's display name (add_field and modify_field). Default for "
                + "add_field: the field name.")
            .stringMap(ATTRIBUTES_PARAM, "Type-specific attributes as name to string value (add_field and "
                + "modify_field), e.g. {\"size\": \"60\"} for String, {\"numberType\": \"integer\"} for "
                + "Number, {\"values\": \"a|b|c\", \"multiSelect\": \"1\"} for StaticList, {\"sql\": \"...\"} "
                + "for DBList. An attribute not valid for the type is refused, listing the accepted ones.")
            .string(BASE_VERSION_PARAM, "The document version you last read (get_document shows it). "
                + "Required when the document already exists, and always required for remove_field; omit it "
                + "only when add_field is creating a new document. The change is refused if the document "
                + "changed since you read it.")
            .string(COMMENT_PARAM, "Version comment shown in the document history. Stored prefixed with "
                + "[AI]. Default: a generic [AI] comment.")
            .bool(MAJOR_PARAM, "Set true to record this change as a major version. Default false (minor). "
                + "Creation is always major.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID,
                PARAMS.advertised(this.wikiReach.isReachEnabled()).inputSchema())
            .description("Define or change an XWiki class (XClass): add a field, modify a field's name and "
                + "attributes, or remove a field. Powerful and off by default - it changes the schema every "
                + "object of the class obeys. See man write_schema before using it. get_schema shows the "
                + "classes and their fields.")
            .build();
    }

    @Override
    public boolean isWrite()
    {
        return true;
    }

    @Override
    public String getCategory()
    {
        return "Structured Data";
    }

    @Override
    public String getSummary()
    {
        return "Create or change an XClass definition (add, modify or remove a field).";
    }

    @Override
    public String getManPage()
    {
        return """
            WARNING
                This tool changes the SCHEMA every object of a class obeys - a class-wide change, not
                an edit to one page. Removing a field hides (does NOT delete) the values every object
                held under that name: they persist as orphaned rows and reappear if the field is
                re-added with a compatible type, so the change is reversible. Changing a field's type
                is remove_field then add_field (same reversibility). Some field types run code with
                the class author's rights: ComputedField, DBList, DBTreeList, Page and a Velocity
                TextArea - and saving a class re-attributes it to the acting user, so editing an
                application's class without that application's rights can stop its own scripted
                fields from running. Give this tool only to a trusted, capable model.

            NOTES
                The reference document IS the class. There is no separate create operation: the first
                add_field on a fieldless or new document creates the class.
                operation is one of:
                    add_field     Add a new field. type is required.
                    modify_field  Change an existing field's pretty_name and/or attributes. type is
                                  refused (changing a type is remove_field then add_field).
                    remove_field  Remove a field. type, pretty_name and attributes are refused.
                type (add_field only) is one of the get_schema type tokens, e.g. String, TextArea,
                Number, Boolean, Date, StaticList, DBList, Page, ComputedField.
                attributes (add_field and modify_field) are type-specific, name to string value:
                    String/Password/Email  size
                    Number                 size, numberType (integer|long|float|double)
                    Boolean                displayType
                    Date                   size, dateFormat, emptyIsToday
                    StaticList             size, values (a|b|c), multiSelect, relationalStorage,
                                           displayType, separator
                    DBList/DBTreeList      size, sql, multiSelect, relationalStorage, displayType
                    Page                   size, sql, multiSelect, displayType
                    Groups/Users           size, multiSelect
                    TextArea               size, rows, editor, contentType
                    ComputedField          script
                An attribute not valid for the type is refused, listing the accepted ones.
                base_version is the version you last read (get_document shows it): required when the
                document already exists, and always required for remove_field; omit it only when
                add_field is creating a new document. The change is refused if the document changed
                since. The change is saved as a new document version and can be reverted from history.
                Classes that define access rights, group membership, user accounts or wiki
                configuration are refused; manage those in the wiki UI.

            EXAMPLES
                New class:   reference="MyApp.TaskClass", operation="add_field", field="title",
                             type="String", attributes={"size": "60"}
                Add field:   reference="MyApp.TaskClass", operation="add_field", field="done",
                             type="Boolean", base_version="1.1"
                Rename:      reference="MyApp.TaskClass", operation="modify_field", field="title",
                             pretty_name="Task title", base_version="2.1"
                Remove:      reference="MyApp.TaskClass", operation="remove_field", field="done",
                             base_version="3.1"

            SEE ALSO
                man get_schema       The classes of the wiki and each class's fields and types.
                man query_objects    Find objects, their numbers and field values.
                man write_object     Create an object or set fields on an existing one.
                man                  (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            Request parsed = parseRequest(args);
            DocumentReference ref = MCPWriteSupport.resolveForEdit(this.documentAccess, parsed.reference());
            return MCPWriteSupport.inTargetWiki(this.contextProvider.get(), ref,
                (xcontext, xdoc) -> performChange(xcontext, xdoc, ref, parsed));
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP write_schema tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP write_schema tool failure details", e);
            return MCPToolSupport.errorResult(MCPWriteSupport.SAVE_FAILED_MESSAGE);
        }
    }

    private Request parseRequest(Map<String, Object> args)
    {
        String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
        String operation = PARAMS.parser().requireString(args, OPERATION_PARAM);
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + OPERATION_PARAM
                + "' must be one of add_field, modify_field, remove_field.");
        }
        String field = PARAMS.parser().requireString(args, FIELD_PARAM);
        String type = PARAMS.parser().string(args, TYPE_PARAM);
        String prettyName = PARAMS.parser().string(args, PRETTY_NAME_PARAM);
        Map<String, String> attributes = PARAMS.parser().stringMap(args, ATTRIBUTES_PARAM);
        String baseVersion = PARAMS.parser().string(args, BASE_VERSION_PARAM);
        String comment = PARAMS.parser().string(args, COMMENT_PARAM);
        boolean major = PARAMS.parser().bool(args, MAJOR_PARAM);
        validateShape(operation, type, prettyName, attributes);
        return new Request(reference, operation, field, type, prettyName, attributes, baseVersion, comment,
            major);
    }

    /**
     * Enforces the per-operation argument shape: {@code type} is required for add_field and refused
     * otherwise; remove_field takes no {@code pretty_name} or {@code attributes}.
     *
     * @param operation the validated operation
     * @param type the raw {@code type} argument, or {@code null}
     * @param prettyName the raw {@code pretty_name} argument, or {@code null}
     * @param attributes the parsed attributes, possibly empty
     * @throws IllegalArgumentException with an agent-facing message on a shape violation
     */
    private static void validateShape(String operation, String type, String prettyName,
        Map<String, String> attributes)
    {
        if (ADD_FIELD.equals(operation) && type == null) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + TYPE_PARAM
                + "' is required for add_field. One of: " + MCPSchemaWriteSupport.acceptedTypes() + PERIOD);
        }
        if (!ADD_FIELD.equals(operation) && type != null) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + TYPE_PARAM + "' is only valid "
                + "for add_field; changing a field's type is remove_field then add_field.");
        }
        if (REMOVE_FIELD.equals(operation) && (prettyName != null || !attributes.isEmpty())) {
            throw new IllegalArgumentException("remove_field takes no pretty_name or attributes.");
        }
    }

    private McpSchema.CallToolResult performChange(XWikiContext xcontext, XWikiDocument xdoc,
        DocumentReference ref, Request request) throws XWikiException
    {
        boolean creating = xdoc.isNew();
        String oldVersion = xdoc.getVersion();
        String localName = this.localSerializer.serialize(ref);
        McpSchema.CallToolResult refusal = sensitiveRefusal(xcontext, ref, localName);
        if (refusal != null) {
            return refusal;
        }
        McpSchema.CallToolResult versionProblem = checkVersion(request, creating, oldVersion);
        if (versionProblem != null) {
            return versionProblem;
        }
        XWikiDocument editable = xdoc.clone();
        return switch (request.operation()) {
            case ADD_FIELD -> saveAdd(editable, request, ref, localName, creating, oldVersion, xcontext);
            case MODIFY_FIELD -> saveModify(editable, request, ref, localName, creating, oldVersion, xcontext);
            case REMOVE_FIELD -> saveRemove(editable, request, ref, localName, creating, oldVersion, xcontext);
            default -> MCPToolSupport.errorResult("Unsupported operation.");
        };
    }

    private McpSchema.CallToolResult saveAdd(XWikiDocument editable, Request request, DocumentReference ref,
        String localName, boolean creating, String oldVersion, XWikiContext xcontext) throws XWikiException
    {
        String rendered = MCPSchemaWriteSupport.addField(editable, request.field(), request.type(),
            request.prettyName(), request.attributes(), localName, xcontext);
        String newVersion = save(editable, request, creating,
            "added " + FIELD_PREFIX + strip(request.field()) + "\" (" + strip(request.type()) + ") to "
                + strip(localName), xcontext);
        String headline = "Added " + FIELD_PREFIX + strip(request.field()) + QUOTE + " to" + CLASS_INFIX
            + strip(localName) + QUOTE + PERIOD;
        return MCPToolSupport.result(fieldSuccess(headline, ref, rendered, creating, oldVersion, newVersion));
    }

    private McpSchema.CallToolResult saveModify(XWikiDocument editable, Request request, DocumentReference ref,
        String localName, boolean creating, String oldVersion, XWikiContext xcontext) throws XWikiException
    {
        String rendered = MCPSchemaWriteSupport.modifyField(editable, request.field(), request.prettyName(),
            request.attributes(), localName, xcontext);
        String newVersion = save(editable, request, creating,
            "modified " + FIELD_PREFIX + strip(request.field()) + "\" on " + strip(localName), xcontext);
        String headline = "Updated " + FIELD_PREFIX + strip(request.field()) + QUOTE + " on" + CLASS_INFIX
            + strip(localName) + QUOTE + PERIOD;
        return MCPToolSupport.result(fieldSuccess(headline, ref, rendered, creating, oldVersion, newVersion));
    }

    private McpSchema.CallToolResult saveRemove(XWikiDocument editable, Request request, DocumentReference ref,
        String localName, boolean creating, String oldVersion, XWikiContext xcontext) throws XWikiException
    {
        MCPSchemaWriteSupport.removeField(editable, request.field(), localName);
        String newVersion = save(editable, request, creating,
            "removed " + FIELD_PREFIX + strip(request.field()) + "\" from " + strip(localName), xcontext);
        String disclosure = MCPSchemaWriteSupport.orphanDisclosure(this.rowQuery, ref.getWikiReference(),
            localName, request.field(), this.logger);
        StringBuilder sb = new StringBuilder("Removed " + FIELD_PREFIX + strip(request.field()) + QUOTE
            + " from" + CLASS_INFIX + strip(localName) + QUOTE + PERIOD);
        appendVersionAndReview(sb, ref, creating, oldVersion, newVersion);
        sb.append(NEW_LINE).append(disclosure);
        sb.append(NEW_LINE).append("The change is recorded in the document history and can be reverted "
            + "from there.");
        return MCPToolSupport.result(sb.toString());
    }

    private String save(XWikiDocument editable, Request request, boolean creating, String summary,
        XWikiContext xcontext) throws XWikiException
    {
        Document apiDoc = new Document(editable, xcontext);
        apiDoc.save(MCPWriteSupport.buildComment(request.comment(), creating, summary),
            MCPWriteSupport.isMinorEdit(creating, request.major()));
        return apiDoc.getVersion();
    }

    private String fieldSuccess(String headline, DocumentReference ref, String rendered, boolean creating,
        String oldVersion, String newVersion)
    {
        StringBuilder sb = new StringBuilder(headline).append(NEW_LINE).append(INDENT).append(rendered);
        appendVersionAndReview(sb, ref, creating, oldVersion, newVersion);
        return sb.toString();
    }

    private void appendVersionAndReview(StringBuilder sb, DocumentReference ref, boolean creating,
        String oldVersion, String newVersion)
    {
        sb.append(NEW_LINE).append(MCPWriteSupport.VERSION_PREFIX);
        if (creating) {
            sb.append(newVersion);
        } else {
            sb.append(oldVersion).append(ARROW).append(newVersion);
        }
        String urlLine = MCPWriteSupport.buildReviewLine(this.documentAccessBridge, this.logger, ref, creating,
            oldVersion, newVersion);
        if (urlLine != null) {
            sb.append(NEW_LINE).append(urlLine);
        }
    }

    /**
     * Builds the sensitive-target refusal, or {@code null} when the change may proceed: the
     * sensitive-document denylist first (a rights- or configuration-bearing page, with its manual-change
     * URL), then the sensitive-class denylist (redefining a class whose objects govern access rights, group
     * membership, user accounts or wiki configuration). Decided from the reference alone, never from
     * content.
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param ref the resolved reference of the class document
     * @param localName the wiki-local serialized class name
     * @return the refusal result, or {@code null} when the change may proceed
     */
    private McpSchema.CallToolResult sensitiveRefusal(XWikiContext xcontext, DocumentReference ref,
        String localName)
    {
        if (MCPWriteSupport.isSensitiveDocument(xcontext, ref, this.localSerializer)) {
            String url = MCPWriteSupport.safeDocumentUrl(this.documentAccessBridge, this.logger, ref, null);
            return MCPToolSupport.errorResult(REFUSING_PREFIX + QUOTE + strip(this.serializer.serialize(ref))
                + QUOTE + ": this page defines access rights or wiki configuration. If you really intend to "
                + "change it, do it manually in the wiki UI" + (url != null ? ": " + url : PERIOD));
        }
        if (MCPObjectWriteSupport.isSensitiveClass(localName)) {
            return MCPToolSupport.errorResult(REFUSING_PREFIX + "class " + QUOTE + strip(localName) + QUOTE
                + ": this class defines access rights, group membership, user accounts or wiki "
                + "configuration. Manage it in the wiki UI.");
        }
        return null;
    }

    /**
     * Checks the document's state against the {@code base_version} workflow: remove_field always requires a
     * matching version (destructive), while add_field and modify_field mirror {@code write_object} (required
     * when the document exists, refused when add_field creates one). Best-effort: a concurrent save landing
     * between this check and the save can still win.
     *
     * @param request the parsed arguments
     * @param creating whether the document does not exist yet
     * @param currentVersion the document's current version
     * @return an error result describing the problem, or {@code null} when the save may proceed
     */
    private McpSchema.CallToolResult checkVersion(Request request, boolean creating, String currentVersion)
    {
        if (REMOVE_FIELD.equals(request.operation())) {
            return checkRemoveVersion(request, currentVersion);
        }
        return checkAddModifyVersion(request, creating, currentVersion);
    }

    private McpSchema.CallToolResult checkRemoveVersion(Request request, String currentVersion)
    {
        if (request.baseVersion() == null) {
            return MCPToolSupport.errorResult("Removing a field is destructive; read the document first with "
                + "get_document and pass the base_version it shows.");
        }
        if (!request.baseVersion().equals(currentVersion)) {
            return MCPToolSupport.errorResult(MCPWriteSupport.versionConflictError(MCPWriteSupport.DOCUMENT_SUBJECT,
                currentVersion, request.baseVersion(), "retry the removal if you still intend it."));
        }
        return null;
    }

    private McpSchema.CallToolResult checkAddModifyVersion(Request request, boolean creating,
        String currentVersion)
    {
        if (creating) {
            if (request.baseVersion() != null) {
                return MCPToolSupport.errorResult(
                    MCPWriteSupport.missingDocumentBaseVersionError(request.reference()));
            }
            return null;
        }
        if (request.baseVersion() == null) {
            return MCPToolSupport.errorResult("Document " + QUOTE + request.reference() + QUOTE
                + " already exists. First read it with get_document and pass the base_version it shows.");
        }
        if (!request.baseVersion().equals(currentVersion)) {
            return MCPToolSupport.errorResult(
                MCPWriteSupport.versionConflictError(MCPWriteSupport.DOCUMENT_SUBJECT, currentVersion,
                    request.baseVersion(), "retry."));
        }
        return null;
    }

    private static String strip(String value)
    {
        return MCPTextGuards.fragment(value);
    }

    /**
     * The parsed and validated arguments of one call, bundled so the guard, apply and format steps share one
     * immutable view of the request.
     *
     * @param reference the raw {@code reference} argument
     * @param operation the validated operation
     * @param field the raw {@code field} argument
     * @param type the raw {@code type} argument, or {@code null}
     * @param prettyName the raw {@code pretty_name} argument, or {@code null}
     * @param attributes the parsed attributes, possibly empty
     * @param baseVersion the version the agent read, or {@code null} when none was given
     * @param comment the agent-supplied version comment, or {@code null}
     * @param major whether to record a major version
     * @version $Id$
     */
    private record Request(String reference, String operation, String field, String type, String prettyName,
        Map<String, String> attributes, String baseVersion, String comment, boolean major)
    {
    }
}
