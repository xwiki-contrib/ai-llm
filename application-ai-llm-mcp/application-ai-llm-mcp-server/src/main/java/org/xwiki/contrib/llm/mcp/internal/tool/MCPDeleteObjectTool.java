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

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that removes one structured object (an XObject) from a document, identified by its class and
 * object number.
 *
 * <p>This is a default tool bundled with the MCP server module. In XWiki's model an object removal is a
 * document edit: the object is removed from the document and the document is saved as a new version, so
 * the removal appears in the document history and can be reverted from there - which is why the tool
 * requires the edit right (through {@link MCPDocumentAccess}, so the per-wiki space filter is applied and
 * the existence of a protected document is never leaked), not the delete right. A mandatory
 * {@code base_version} (the version the agent read) doubles as proof of a recent read, so a removal is
 * never based on a stale or absent view of the document. Rights- and configuration-bearing documents and
 * classes are refused outright (the sensitive denylists shared with {@code write_object}).</p>
 *
 * <p>The removal is applied through {@link com.xpn.xwiki.api.Document}, whose own lazy internal clone is
 * the cache-safety barrier (the loaded instance may be the store cache's, so it is never mutated in
 * place), and the same wrapper's save applies author attribution and save-time rights. The platform
 * nulls the removed object's slot rather than renumbering, so the remaining objects keep their
 * numbers.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPDeleteObjectTool.TOOL_ID)
@Singleton
public class MCPDeleteObjectTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "delete_object";

    private static final String REFERENCE_PARAM = "reference";

    private static final String CLASS_PARAM = "class";

    private static final String OBJECT_PARAM = "object";

    private static final String BASE_VERSION_PARAM = "base_version";

    private static final String COMMENT_PARAM = "comment";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String OBJECT_INFIX = " object ";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description so
     * no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPDeleteObjectTool::params);

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
        String referenceDescription = "The document holding the object, e.g. \"Blog.MyPost\" "
            + "or \"" + (crossWiki ? "xwiki:" : "") + "Help.Foo\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(CLASS_PARAM, "Reference of the object's class, e.g. \"XWiki.XWikiComments\". "
                + "Classes are wiki-local: never use a wiki prefix here.")
            .requiredInteger(OBJECT_PARAM, "Number of the object to remove (shown by query_objects as "
                + "\"object N\").")
            .requiredString(BASE_VERSION_PARAM, "The document version you last read, shown by get_document "
                + "and query_objects. Always required: read the document first and pass its version, so a "
                + "removal is always based on a recent read. The removal is refused if the document has "
                + "changed since.")
            .string(COMMENT_PARAM, "Version comment shown in the document history. Stored prefixed with "
                + "[AI]. Default: a generic [AI] comment.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = PARAMS.advertised(this.wikiReach.isReachEnabled());
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description("Remove one structured object from a document, by class and object number "
                + "(query_objects shows both). Requires base_version - read the document first. The "
                + "removal is saved as a new document version, so it stays in the history and can be "
                + "reverted.")
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
        return "Remove one structured object from a document.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                Removes ONE object from a document, identified by its class and its number (shown
                by query_objects as "object N"). The removal is saved as a new document version,
                so it appears in the document history and can be reverted from there. Removals are
                recorded as minor versions. The remaining objects keep their numbers.
                base_version is always required: read the document first (get_document and
                query_objects show the version) and pass what you read, so a removal is always
                based on a recent read. The removal is refused if the document has changed since.
                Pages and classes that define access rights or wiki configuration are refused;
                manage those in the wiki UI.

            EXAMPLES
                Delete:      query_objects class="XWiki.XWikiComments" document="Blog.MyPost"
                             (note the object number and the document version v4.2), then
                             delete_object reference="Blog.MyPost", class="XWiki.XWikiComments",
                             object=1, base_version="4.2"
                Conflict:    a "Version conflict" result means the document changed since you
                             read it - re-read it and retry if you still intend the removal.

            SEE ALSO
                man query_objects    Find objects, their numbers ("object N") and field values.
                man write_object     Create an object or set fields on an existing one.
                man get_schema       The classes of the wiki and each class's fields and types.
                man                  (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
            String classReference = PARAMS.parser().requireString(args, CLASS_PARAM);
            int objectNumber = PARAMS.parser().requireInteger(args, OBJECT_PARAM);
            String baseVersion = PARAMS.parser().requireString(args, BASE_VERSION_PARAM);
            String comment = PARAMS.parser().string(args, COMMENT_PARAM);

            DocumentReference ref = MCPWriteSupport.resolveForEdit(this.documentAccess, reference);
            DocumentReference classRef =
                MCPObjectWriteSupport.resolveClass(this.documentAccess, classReference, ref);

            return removeAndSave(ref, classRef, reference, objectNumber, baseVersion, comment);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP delete_object tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP delete_object tool failure details", e);
            return MCPToolSupport.errorResult(MCPWriteSupport.SAVE_FAILED_MESSAGE);
        }
    }

    /**
     * Runs the guarded removal inside the target wiki: the document must exist, the sensitive-document
     * and sensitive-class refusals fire next (before the version check, so a denylisted target is
     * refused regardless of version), then the mandatory {@code base_version} is compared, and the
     * object is removed through the {@link com.xpn.xwiki.api.Document} wrapper (whose internal clone is
     * the cache-safety barrier) and the document is saved as a single new version.
     *
     * @param ref the resolved and authorized document reference
     * @param classRef the resolved and authorized class reference
     * @param reference the original reference string, for error messages
     * @param objectNumber the number of the object to remove
     * @param baseVersion the version the agent read
     * @param comment the agent-supplied version comment, or {@code null}
     * @return the tool result
     * @throws XWikiException when loading or saving fails
     */
    private McpSchema.CallToolResult removeAndSave(DocumentReference ref, DocumentReference classRef,
        String reference, int objectNumber, String baseVersion, String comment) throws XWikiException
    {
        return MCPWriteSupport.inTargetWiki(this.contextProvider.get(), ref, (xcontext, xdoc) -> {
            if (xdoc.isNew()) {
                return MCPToolSupport.errorResult("Document " + QUOTE + MCPTextGuards.fragment(reference)
                    + QUOTE + " does not exist; there is no object to delete.");
            }
            McpSchema.CallToolResult refusal = MCPObjectWriteSupport.sensitiveRefusal(xcontext, ref,
                classRef, this.serializer, this.localSerializer,
                () -> MCPWriteSupport.safeDocumentUrl(this.documentAccessBridge, this.logger, ref, null));
            if (refusal != null) {
                return refusal;
            }
            String oldVersion = xdoc.getVersion();
            if (!baseVersion.equals(oldVersion)) {
                return MCPToolSupport.errorResult(MCPWriteSupport.versionConflictError(
                    MCPWriteSupport.DOCUMENT_SUBJECT, oldVersion, baseVersion,
                    "retry the removal if you still intend it."));
            }

            String localClassName = this.localSerializer.serialize(classRef);
            // Guard read-only against the cached xdoc BEFORE mutating (negative number, no-object-at-number
            // listing existing numbers). The api wrapper clones internally on first mutation, so the cache
            // instance is never mutated in place - do not pre-clone or mutate xdoc here.
            MCPObjectWriteSupport.requireObjectExists(xdoc, classRef, localClassName, objectNumber);
            Document apiDoc = new Document(xdoc, xcontext);
            // Resolve the object by the already-authorized class reference (not a re-resolved name) so the
            // lookup matches requireObjectExists. requireObjectExists validated presence on xdoc, so a null
            // here would mean the wrapper's internal clone diverged; guard it defensively because execute()
            // catches only IllegalArgumentException and XWikiException, not a stray NullPointerException.
            com.xpn.xwiki.api.Object apiObject = apiDoc.getObject(classRef, objectNumber);
            if (apiObject == null) {
                return MCPToolSupport.errorResult(
                    MCPObjectWriteSupport.noObjectMessage(xdoc, classRef, localClassName, objectNumber));
            }
            apiDoc.removeObject(apiObject);
            apiDoc.save(MCPWriteSupport.buildComment(comment, false,
                "deleted " + localClassName + OBJECT_INFIX + objectNumber), MCPWriteSupport.isMinorEdit(false, false));

            return MCPToolSupport.result(
                buildSuccessResult(ref, localClassName, objectNumber, oldVersion, apiDoc.getVersion()));
        });
    }

    /**
     * Builds the success result: what was deleted from which document, the version transition, the
     * review line (the compare URL shows the removal as a diff) and the recoverability note.
     *
     * @param ref the saved document's reference
     * @param localClassName the wiki-local serialized class name
     * @param objectNumber the removed object's number
     * @param oldVersion the version before the save
     * @param newVersion the version after the save
     * @return the result text
     */
    private String buildSuccessResult(DocumentReference ref, String localClassName, int objectNumber,
        String oldVersion, String newVersion)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Deleted ").append(MCPToolSupport.stripLineBreaks(localClassName)).append(OBJECT_INFIX)
            .append(objectNumber).append(" from document ")
            .append(MCPToolSupport.stripLineBreaks(this.serializer.serialize(ref))).append('.').append(NEW_LINE);
        sb.append(MCPWriteSupport.VERSION_PREFIX).append(oldVersion).append(" -> ").append(newVersion);
        String urlLine = MCPWriteSupport.buildReviewLine(this.documentAccessBridge, this.logger, ref, false,
            oldVersion, newVersion);
        if (urlLine != null) {
            sb.append(NEW_LINE).append(urlLine);
        }
        sb.append(NEW_LINE).append("The removal is recorded in the document history and can be reverted "
            + "from there.");
        return sb.toString();
    }
}
