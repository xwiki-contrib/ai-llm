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
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that creates a structured object (an XObject) on a document or sets fields on an existing
 * one, schema-validated: every field entry is checked against the class definition and coerced with the
 * field's own type before anything is mutated (see {@link MCPObjectWriteSupport}).
 *
 * <p>This is a default tool bundled with the MCP server module. Classes are wiki-local, so the
 * {@code class} argument is resolved in the target document's wiki and a contradicting wiki prefix is
 * refused. The {@code base_version} discipline mirrors {@code write_document}: required when the
 * document exists, refused when creating one. Rights- and configuration-bearing documents and classes
 * are refused outright (the sensitive-document and sensitive-class denylists shared with the other
 * destructive tools).</p>
 *
 * <p>Resolution and authorization both go through {@link MCPDocumentAccess} for the edit right before
 * the document is loaded, so the per-wiki space filter is applied and the existence of a protected
 * document is never leaked. The changes are applied to the tool's own clone of the loaded document (the
 * loaded instance may be the store cache's, so it is never mutated in place) and the save goes through
 * {@link com.xpn.xwiki.api.Document} so author attribution and save-time rights are applied.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPWriteObjectTool.TOOL_ID)
@Singleton
public class MCPWriteObjectTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "write_object";

    private static final String REFERENCE_PARAM = "reference";

    private static final String CLASS_PARAM = "class";

    private static final String OBJECT_PARAM = "object";

    private static final String FIELDS_PARAM = "fields";

    private static final String BASE_VERSION_PARAM = "base_version";

    private static final String COMMENT_PARAM = "comment";

    private static final String MAJOR_PARAM = "major";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String OBJECT_INFIX = " object ";

    private static final String ON_DOCUMENT_INFIX = " on document ";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description so
     * no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPWriteObjectTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

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

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example and the cross-wiki
     * sentence in the {@code reference} description only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document holding (or to hold) the object, e.g. \"Blog.MyPost\" "
            + "or \"" + (crossWiki ? "xwiki:" : "") + "Help.Foo\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(CLASS_PARAM, "Reference of the object's class, e.g. \"Blog.BlogPostClass\". "
                + "Classes are wiki-local: the class always lives in the target document's wiki, so never "
                + "use a wiki prefix here. get_schema lists the classes and each class's fields.")
            .integer(OBJECT_PARAM, "Number of the existing object to update (shown by query_objects as "
                + "\"object N\"). Omit to create a new object of the class.")
            .requiredStringMap(FIELDS_PARAM, "Field name to value. All values are strings; each is "
                + "converted with the field's own type (get_schema shows the types and formats; "
                + "multi-select list values accept \"|\" or \",\" separators; booleans accept 0, 1, true "
                + "or false).")
            .string(BASE_VERSION_PARAM, "The document version you read (shown by get_document and "
                + "query_objects). Required when the document already exists; omit it when creating a new "
                + "document. The save is refused if the document has changed since you read it.")
            .string(COMMENT_PARAM, "Version comment shown in the document history. Stored prefixed with "
                + "[AI]. Default: a generic [AI] comment.")
            .bool(MAJOR_PARAM, "Set true to record this change as a major version. Default false (minor). "
                + "Creation is always major.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = PARAMS.advertised(this.wikiReach.isReachEnabled());
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description("Create a structured object (an instance of an XWiki class) on a document, or "
                + "set fields on an existing one. Field values are strings, validated and converted with "
                + "each field's type - get_schema shows the classes and fields. Omit object to create; "
                + "pass the number shown by query_objects to update. Updating an existing document "
                + "requires base_version.")
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
        return "Create a structured object or set fields on an existing one (schema-validated).";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                Creates or updates ONE object (an instance of a class) on a document. Omit the
                object parameter to create a new object; pass object=N (the number shown by
                query_objects as "object N") to update an existing one.
                All field values are strings; each is validated and converted with the field's
                own type - get_schema class="..." shows the fields with their types and formats.
                Multi-select list values accept "|" or "," separators (e.g. "News|Personal");
                booleans accept 0, 1, true or false. Unknown fields are refused (the error lists
                the class's fields); Password and computed fields cannot be set with this tool.
                base_version is required when the document already exists: read it first
                (get_document and query_objects show the version) and pass what you read. Omit
                base_version when the document does not exist yet - it is then created carrying
                only the new object (empty body).
                Pages and classes that define access rights or wiki configuration are refused;
                manage those in the wiki UI.

            EXAMPLES
                New object:  reference="Blog.MyPost", class="Blog.BlogPostClass",
                             base_version="2.1", fields={"title": "Hello", "published": "1"}
                Update:      reference="Blog.MyPost", class="Blog.BlogPostClass", object=0,
                             base_version="2.2", fields={"published": "0"}
                New page:    reference="Blog.NewPost", class="Blog.BlogPostClass",
                             fields={"title": "Draft"}
                             (no base_version: the document does not exist and is created)

            SEE ALSO
                man get_schema       The classes of the wiki and each class's fields and types.
                man query_objects    Find objects, their numbers ("object N") and field values.
                man delete_object    Remove one object from a document.
                man write_document   Create a document or replace its entire content.
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
            DocumentReference classRef =
                MCPObjectWriteSupport.resolveClass(this.documentAccess, parsed.classReference(), ref);

            return applyAndSave(ref, classRef, parsed);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP write_object tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP write_object tool failure details", e);
            return MCPToolSupport.errorResult(MCPWriteSupport.SAVE_FAILED_MESSAGE);
        }
    }

    /**
     * Parses and validates the call arguments.
     *
     * @param args the tool call arguments
     * @return the parsed request
     * @throws IllegalArgumentException with an agent-facing message on a missing or mistyped argument
     */
    private Request parseRequest(Map<String, Object> args)
    {
        return new Request(
            PARAMS.parser().requireString(args, REFERENCE_PARAM),
            PARAMS.parser().requireString(args, CLASS_PARAM),
            PARAMS.parser().integer(args, OBJECT_PARAM),
            PARAMS.parser().requireStringMap(args, FIELDS_PARAM),
            PARAMS.parser().string(args, BASE_VERSION_PARAM),
            PARAMS.parser().string(args, COMMENT_PARAM),
            PARAMS.parser().bool(args, MAJOR_PARAM));
    }

    /**
     * Runs the guarded write inside the target wiki: the sensitive-document and sensitive-class
     * refusals fire first (before the version check, so a denylisted target is refused regardless of
     * version), then the {@code base_version} discipline, then the class document is loaded and the
     * validated fields are applied to the tool's own clone of the target document, which is saved as a
     * single new version.
     *
     * @param ref the resolved and authorized document reference
     * @param classRef the resolved and authorized class reference
     * @param request the parsed arguments
     * @return the tool result
     * @throws XWikiException when loading or saving fails
     */
    private McpSchema.CallToolResult applyAndSave(DocumentReference ref, DocumentReference classRef,
        Request request) throws XWikiException
    {
        return MCPWriteSupport.inTargetWiki(this.contextProvider.get(), ref, (xcontext, xdoc) -> {
            boolean creating = xdoc.isNew();
            String oldVersion = xdoc.getVersion();

            McpSchema.CallToolResult refusal = MCPObjectWriteSupport.sensitiveRefusal(xcontext, ref,
                classRef, this.serializer, this.localSerializer,
                () -> MCPWriteSupport.safeDocumentUrl(this.documentAccessBridge, this.logger, ref, null));
            if (refusal != null) {
                return refusal;
            }
            McpSchema.CallToolResult versionProblem =
                checkBaseVersion(request.reference(), request.baseVersion(), creating, oldVersion);
            if (versionProblem != null) {
                return versionProblem;
            }

            XWikiDocument classDoc = xcontext.getWiki().getDocument(classRef, xcontext);
            String localClassName = this.localSerializer.serialize(classRef);
            if (classDoc.isNew()) {
                return MCPToolSupport.errorResult("No such class: " + QUOTE
                    + MCPTextGuards.fragment(localClassName) + QUOTE + ". Use get_schema with no "
                    + "arguments to list the classes of this wiki.");
            }

            XWikiDocument editable = xdoc.clone();
            var applied = MCPObjectWriteSupport.applyFields(editable, classDoc, classRef, localClassName,
                request.objectNumber(), request.fields(), xcontext);

            Document apiDoc = new Document(editable, xcontext);
            apiDoc.save(
                MCPWriteSupport.buildComment(request.comment(), creating, changeSummary(applied, localClassName)),
                MCPWriteSupport.isMinorEdit(creating, request.major()));

            return MCPToolSupport.result(
                buildSuccessResult(ref, creating, oldVersion, apiDoc.getVersion(), localClassName, applied));
        });
    }

    /**
     * Checks the document's state against the {@code base_version} workflow before anything is written,
     * mirroring {@code write_document}: an update must carry the version the agent read, a creation must
     * not carry one, and a stale version is refused.
     *
     * <p>The version check is best-effort: a concurrent save landing between this check and the save
     * below can still win. It protects the agent's read-modify-write loop against stale reads, not
     * transactional integrity.</p>
     *
     * @param reference the original reference string, for error messages
     * @param baseVersion the version the agent read, or {@code null} when none was given
     * @param creating whether the document does not exist yet
     * @param currentVersion the document's current version
     * @return an error result describing the problem, or {@code null} when the save may proceed
     */
    private McpSchema.CallToolResult checkBaseVersion(String reference, String baseVersion, boolean creating,
        String currentVersion)
    {
        if (creating) {
            if (baseVersion != null) {
                return MCPToolSupport.errorResult(MCPWriteSupport.missingDocumentBaseVersionError(reference));
            }
            return null;
        }
        if (baseVersion == null) {
            return MCPToolSupport.errorResult("Document " + QUOTE + reference + QUOTE + " already exists. "
                + "First read it with get_document and pass the base_version it shows, so the object "
                + "change is based on a recent read.");
        }
        if (!baseVersion.equals(currentVersion)) {
            return MCPToolSupport.errorResult(
                MCPWriteSupport.versionConflictError(MCPWriteSupport.DOCUMENT_SUBJECT, currentVersion,
                    baseVersion, "retry."));
        }
        return null;
    }

    /**
     * Builds this tool's generated update description for the version comment (used when the agent
     * supplied no comment on a non-creating save).
     *
     * @param applied the applied write
     * @param localClassName the wiki-local serialized class name
     * @return the update description, e.g. {@code "created Blog.BlogPostClass object 2"} or
     *     {@code "set 3 field(s) on Blog.BlogPostClass object 0"}
     */
    private static String changeSummary(MCPObjectWriteSupport.AppliedWrite applied, String localClassName)
    {
        if (applied.createdObject()) {
            return "created " + localClassName + OBJECT_INFIX + applied.number();
        }
        return "set " + applied.fieldNames().size() + " field(s) on " + localClassName + OBJECT_INFIX
            + applied.number();
    }

    /**
     * Builds the success result: what happened to which object, the version (transition), the names of
     * the fields set (names only - values are on the document, and the compare URL is the authoritative
     * review) and the review line.
     *
     * @param ref the saved document's reference
     * @param creatingDocument whether the document itself was created
     * @param oldVersion the version before the save
     * @param newVersion the version after the save
     * @param localClassName the wiki-local serialized class name
     * @param applied the applied write
     * @return the result text
     */
    private String buildSuccessResult(DocumentReference ref, boolean creatingDocument, String oldVersion,
        String newVersion, String localClassName, MCPObjectWriteSupport.AppliedWrite applied)
    {
        String canonicalRef = MCPToolSupport.stripLineBreaks(this.serializer.serialize(ref));
        String className = MCPToolSupport.stripLineBreaks(localClassName);
        StringBuilder sb = new StringBuilder();
        if (creatingDocument) {
            sb.append("Created document ").append(canonicalRef).append(" with ").append(className)
                .append(OBJECT_INFIX).append(applied.number()).append(PERIOD).append(NEW_LINE);
            sb.append(MCPWriteSupport.VERSION_PREFIX).append(newVersion);
        } else {
            sb.append(applied.createdObject() ? "Created " : "Updated ").append(className)
                .append(OBJECT_INFIX).append(applied.number()).append(ON_DOCUMENT_INFIX).append(canonicalRef)
                .append(PERIOD).append(NEW_LINE);
            sb.append(MCPWriteSupport.VERSION_PREFIX).append(oldVersion).append(" -> ").append(newVersion);
        }
        sb.append(NEW_LINE).append("Fields set: ").append(String.join(", ", applied.fieldNames()));
        String urlLine = MCPWriteSupport.buildReviewLine(this.documentAccessBridge, this.logger, ref,
            creatingDocument, oldVersion, newVersion);
        if (urlLine != null) {
            sb.append(NEW_LINE).append(urlLine);
        }
        return sb.toString();
    }

    /**
     * The parsed and validated arguments of one call, bundled so the guard, apply and format steps share
     * one immutable view of the request.
     *
     * @param reference the raw {@code reference} argument
     * @param classReference the raw {@code class} argument
     * @param objectNumber the number of the object to update, or {@code null} to create one
     * @param fields the field name to raw string value entries, in call order
     * @param baseVersion the version the agent read, or {@code null} when none was given
     * @param comment the agent-supplied version comment, or {@code null}
     * @param major whether to record a major version
     * @version $Id$
     */
    private record Request(String reference, String classReference, Integer objectNumber,
        Map<String, String> fields, String baseVersion, String comment, boolean major)
    {
    }
}
