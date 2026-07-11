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
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPReachAwareParams;
import org.xwiki.contrib.llm.mcp.MCPSourceText;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.QueryException;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that describes XWiki classes (XClass definitions) to agents. With no arguments it renders a
 * catalog of the wiki's instantiated classes with instance counts; with a {@code class} reference it renders
 * that class's field definitions in the plain-text grammar of {@link MCPSchemaText}, so an agent learns what
 * structured data a wiki holds and how each field is typed before reading or querying it.
 *
 * <p>This is a default (read-only) tool bundled with the MCP server module. A call operates on exactly one
 * wiki: the current wiki by default, or another farm wiki via the reach-gated {@code wiki} parameter
 * (resolved through {@link MCPWikiReach#resolveSingleWiki(String)}). A {@code class} reference is resolved
 * through {@link MCPDocumentAccess#resolveAndAuthorize(String, Right, WikiReference)}, which resolves it into
 * the target wiki, checks {@link Right#VIEW} and applies the space filter. Catalog counts are aggregates, so
 * per-row rights cannot apply to the counted instances; instead each row's CLASS DOCUMENT is authorized
 * (space filter plus view right) and denied rows are dropped, and the honesty note tells the agent the
 * remaining counts are upper bounds.</p>
 *
 * <p>Class names, field names, pretty names, list values and validation texts are wiki-authored and therefore
 * untrusted; every such fragment is stripped of the newline/control family before landing in the
 * line-oriented output, so a crafted name cannot forge extra catalog rows or field lines.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPGetSchemaTool.TOOL_ID)
@Singleton
public class MCPGetSchemaTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "get_schema";

    private static final String CLASS_PARAM = "class";

    private static final String WIKI_PARAM = "wiki";

    private static final String CLASSNAME_BIND = "className";

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String INDENT = "  ";

    /**
     * Shared instance-join of the catalog and count statements: an object row joined to its owning
     * document. {@code doc.translation = 0} is mandatory - without it every translation of a page
     * duplicates its objects into the counts. There is deliberately NO hidden-document clause: class
     * definitions are conventionally hidden documents, so filtering hidden pages would empty the catalog.
     * {@code obj.className} is the wiki-local serialized class name, which composes with the door's wiki
     * scoping; classes are wiki-local, so no cross-wiki prefixes ever appear in the rows.
     */
    private static final String INSTANCE_JOIN =
        " from XWikiDocument doc, BaseObject obj where doc.fullName = obj.name and doc.translation = 0";

    /**
     * The catalog statement: one row per instantiated class with its instance count, in class-name order.
     */
    private static final String CATALOG_QUERY = "select obj.className, count(obj.id)" + INSTANCE_JOIN
        + " group by obj.className order by obj.className";

    /**
     * The detail-mode instance count of one class, bound by its wiki-local serialized name.
     */
    private static final String COUNT_QUERY = "select count(obj.id)" + INSTANCE_JOIN
        + " and obj.className = :" + CLASSNAME_BIND;

    /**
     * The agent-facing result message for a failed catalog or count query, worded to fit both modes. The
     * root cause (schema/driver detail) stays in the server logs, off the wire.
     */
    private static final String QUERY_FAILED =
        "Could not read the class information. Try again; if it persists, report it to a wiki administrator "
            + "(details are in the server logs).";

    /**
     * The honesty note appended when the catalog fetch came back at the door's row ceiling: classes beyond
     * the ceiling were never fetched, so the list may be incomplete.
     */
    private static final String CATALOG_CEILING_NOTE = "The catalog hit the "
        + MCPRowQuery.MAX_FETCH_PER_QUERY + "-row fetch ceiling: classes beyond it are not listed.";

    /**
     * The notice ending a response cut at the shared output budget ({@link MCPSourceText#MAX_OUTPUT_CHARS}),
     * so a pathological class definition (thousands of fields) or catalog (thousands of classes) cannot
     * flood the agent's context window.
     */
    private static final String OUTPUT_TRUNCATION_NOTE =
        "Output truncated at the ~" + MCPSourceText.MAX_OUTPUT_TOKENS + "-token cap.";

    private static final String CATALOG_FOOTER =
        "Counts are upper bounds: they include instances the current user may not view." + NEW_LINE
            + "Only classes with at least one instance are listed." + NEW_LINE
            + "Use get_schema class=\"<reference>\" for a class's field definitions.";

    private static final String DESCRIPTION =
        "Describe an XWiki class: with no arguments, list all classes with instance counts; with "
            + "class=\"<reference>\", show that class's field definitions (types, list values, defaults, "
            + "validation).";

    /**
     * The man-page NOTES shown on every endpoint, without any cross-wiki mention.
     */
    private static final String MAN_NOTES_BASE = """
        NOTES
            Two modes. With no arguments, get_schema lists this wiki's classes with instance
            counts: only classes with at least one instance appear, and the counts are upper
            bounds (they include instances the current user may not view). With
            class="<reference>", it shows that class's field definitions.

            A class is an XWiki page holding field definitions (an XClass); its instances are
            objects attached to other pages. Class pages are conventionally hidden pages, so
            the catalog does not filter hidden documents.
        """;

    /**
     * The cross-wiki NOTES paragraph, appended only when the endpoint has cross-wiki reach - the man page
     * must not advertise a parameter the advertised schema does not carry.
     */
    private static final String MAN_NOTES_CROSS_WIKI = """

            The wiki parameter surveys or inspects another wiki of the farm instead of the
            current one (one wiki per call; list_wikis shows what is reachable).
        """;

    /**
     * The man-page FORMAT section (the field-line grammar agents parse) and the EXAMPLES shown on every
     * endpoint.
     */
    private static final String MAN_FORMAT_AND_EXAMPLES = """

        FORMAT
            Each enabled field renders on one line:
                name: Type(detail) "Pretty Name" modifiers [validation: regexp "message"]
            The quoted pretty name appears only when it differs from the field name; the
            detail, modifiers and validation block appear only when the field defines them.
            One example line per interesting type:
                title: String "Title"
                category: StaticList(News|Personal|Other) "Category" multiselect default=News
                published: Boolean(yesno) "Published" default=1
                publishDate: Date(dd/MM/yyyy HH:mm:ss) "Publish date"
                content: TextArea(wiki) "Content"
                setup: TextArea(velocitycode) "Setup" (executable script)
                reviewer: Users "Reviewer"
                secret: Password "Secret" (values masked in query results)
                source: DBList "Source" (values come from a database query)
                total: ComputedField "Total" (computed by a script; read-only)
                code: String "Code" [validation: ^[A-Z]{3}$ "Three uppercase letters"]
            A DISABLED FIELDS line lists fields that are defined but switched off, by name.
            There is no required-field flag: the XWiki class model does not define one.

        EXAMPLES
            Catalog:      (call with no arguments)
            One class:    class="Blog.BlogPostClass"
        """;

    /**
     * The cross-wiki example line, reach-gated like its NOTES paragraph.
     */
    private static final String MAN_EXAMPLE_CROSS_WIKI = """
            Another wiki: wiki="second"
        """;

    /**
     * The man-page tail shown on every endpoint.
     */
    private static final String MAN_TAIL = """

        SEE ALSO
            man get_document       Read one page (a class definition is a page) by its Reference.
            man query_documents    Search documents when you know keywords rather than location.
            man                    (no argument) List all tools and reference pages.
        """;

    /**
     * The full man page for cross-wiki enabled endpoints.
     */
    private static final String MAN_PAGE = MAN_NOTES_BASE + MAN_NOTES_CROSS_WIKI + MAN_FORMAT_AND_EXAMPLES
        + MAN_EXAMPLE_CROSS_WIKI + MAN_TAIL;

    /**
     * The man page for reach-off endpoints: no cross-wiki paragraph, no wiki-parameter example.
     */
    private static final String MAN_PAGE_LOCAL = MAN_NOTES_BASE + MAN_FORMAT_AND_EXAMPLES + MAN_TAIL;

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant omits the
     * {@code wiki} parameter, so no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPGetSchemaTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPRowQuery rowQuery;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * Builds the declared parameter set, only including the cross-wiki {@code wiki} parameter when
     * cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise the cross-wiki {@code wiki} parameter
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        return MCPToolSupport.builder()
            .string(CLASS_PARAM, "Optional reference of the class document (e.g. \"Blog.BlogPostClass\"). "
                + "Omit for a catalog of this wiki's classes with instance counts.")
            .stringIf(crossWiki, WIKI_PARAM, "Optional wiki id to survey or inspect instead of the current "
                + "wiki (see list_wikis). One wiki per call.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = PARAMS.advertised(this.wikiReach.isReachEnabled());
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description(DESCRIPTION)
            .build();
    }

    @Override
    public String getCategory()
    {
        return "Structured Data";
    }

    @Override
    public String getSummary()
    {
        return "Describe an XWiki class: its fields and their types, or list all classes with instance "
            + "counts.";
    }

    @Override
    public String getManPage()
    {
        return this.wikiReach.isReachEnabled() ? MAN_PAGE : MAN_PAGE_LOCAL;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String classReference = PARAMS.parser().string(args, CLASS_PARAM);
            String wikiParam = PARAMS.parser().string(args, WIKI_PARAM);
            try {
                String targetWiki = this.wikiReach.resolveSingleWiki(wikiParam);
                if (classReference == null) {
                    return MCPToolSupport.result(renderCatalog(targetWiki));
                }
                return describeClass(classReference, targetWiki);
            } catch (MCPAccessDeniedException e) {
                return MCPToolSupport.errorResult(e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (QueryException e) {
            this.logger.warn("MCP get_schema tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_schema tool failure details", e);
            // Return a fixed message: the root cause (schema/driver detail) stays in the logs, off the wire.
            return MCPToolSupport.errorResult(QUERY_FAILED);
        }
    }

    /**
     * Renders the catalog: one line per instantiated class the current user may know about, with its
     * instance count, plus the honesty notes. Each row's class document is authorized through the door;
     * a denied class is dropped entirely. A class document that does not exist (orphaned objects) is
     * authorized at its would-be location - rights are evaluated on the reference, not on stored content -
     * so such a row is kept when that location authorizes.
     *
     * @param targetWiki the resolved wiki id the call operates on
     * @return the rendered catalog text
     * @throws QueryException if the catalog query fails
     */
    private String renderCatalog(String targetWiki) throws QueryException
    {
        List<Object[]> rows =
            this.rowQuery.rows(CATALOG_QUERY, targetWiki, Map.of(), MCPRowQuery.MAX_FETCH_PER_QUERY);
        WikiReference wikiRef = new WikiReference(targetWiki);
        List<String> lines = new ArrayList<>();
        for (Object[] columns : rows) {
            String className = (String) columns[0];
            if (this.rowQuery.authorizedDocument(className, wikiRef) == null) {
                continue;
            }
            long count = ((Number) columns[1]).longValue();
            lines.add(INDENT + MCPToolSupport.stripLineBreaks(className) + " - " + count
                + instanceWord(count));
        }
        if (lines.isEmpty()) {
            return "No classes with instances found in wiki " + QUOTE + targetWiki + QUOTE + PERIOD;
        }
        // Budget the listing alone, then append the fixed-size honesty tail: the notes must survive a
        // budget cut - a truncated catalog needs its "upper bounds"/"may be incomplete" caveats the most.
        StringBuilder catalog = new StringBuilder(budgeted("CLASSES with instances in wiki " + QUOTE
            + targetWiki + QUOTE + ":" + DOUBLE_NEW_LINE + String.join(NEW_LINE, lines)));
        catalog.append(DOUBLE_NEW_LINE).append(CATALOG_FOOTER);
        if (rows.size() >= MCPRowQuery.MAX_FETCH_PER_QUERY) {
            catalog.append(NEW_LINE).append(CATALOG_CEILING_NOTE);
        }
        return catalog.toString();
    }

    private static String instanceWord(long count)
    {
        return count == 1 ? " instance" : " instances";
    }

    /**
     * Renders one class's field definitions: resolves and authorizes the class document, loads it, rejects
     * a missing document or one that defines no fields, counts its instances and renders the header plus
     * the {@link MCPSchemaText} block.
     *
     * @param classReference the raw {@code class} argument
     * @param targetWiki the resolved wiki id the call operates on
     * @return the tool result
     * @throws MCPAccessDeniedException when the reference fails the reach gate, the rights check or the
     *     space filter, or contradicts the {@code wiki} parameter with an explicit wiki prefix
     * @throws QueryException if the instance-count query fails
     */
    private McpSchema.CallToolResult describeClass(String classReference, String targetWiki)
        throws MCPAccessDeniedException, QueryException
    {
        DocumentReference ref =
            this.documentAccess.resolveAndAuthorize(classReference, Right.VIEW, new WikiReference(targetWiki));
        XWikiDocument xdoc = loadDocument(ref, classReference);
        if (xdoc.isNew()) {
            return MCPToolSupport.errorResult("No such document: " + QUOTE + classReference + QUOTE + PERIOD);
        }
        // getXClass() never returns null; a non-class document is detected by having no fields at all.
        if (MCPSchemaText.definesNoFields(xdoc.getXClass())) {
            return MCPToolSupport.errorResult("Document " + QUOTE + classReference + QUOTE
                + " exists but defines no class fields. Use get_schema with no arguments to list the "
                + "classes of this wiki.");
        }
        long count = countInstances(targetWiki, this.localSerializer.serialize(ref));
        return MCPToolSupport.result(budgeted("CLASS "
            + MCPToolSupport.stripLineBreaks(this.localSerializer.serialize(ref)) + NEW_LINE
            + "Instances: " + count + " (upper bound; rights apply when reading them)" + DOUBLE_NEW_LINE
            + MCPSchemaText.render(xdoc.getXClass(), this.contextProvider.get())));
    }

    /**
     * Enforces the shared output budget on a rendered response: a response over
     * {@link MCPSourceText#MAX_OUTPUT_CHARS} is cut at the last complete line within the budget (the output
     * is line-oriented, so a cut never leaves half a field line) and ends with the truncation notice.
     * The per-fragment caps of {@link MCPSchemaText} keep single lines bounded, so only a class with very
     * many fields (or a catalog with very many classes) reaches this cut.
     *
     * @param output the rendered response
     * @return the response, cut to the budget when needed
     */
    private static String budgeted(String output)
    {
        if (output.length() <= MCPSourceText.MAX_OUTPUT_CHARS) {
            return output;
        }
        int cut = output.lastIndexOf(NEW_LINE, MCPSourceText.MAX_OUTPUT_CHARS);
        if (cut <= 0) {
            cut = MCPSourceText.MAX_OUTPUT_CHARS;
        }
        return output.substring(0, cut) + NEW_LINE + OUTPUT_TRUNCATION_NOTE;
    }

    /**
     * Loads the already-authorized class document as an {@link XWikiDocument} (the type that exposes
     * {@code getXClass()}).
     *
     * @param ref the resolved and authorized document reference
     * @param classReference the raw {@code class} argument, for the error message
     * @return the loaded document
     * @throws IllegalArgumentException with an agent-facing message when the load fails
     */
    private XWikiDocument loadDocument(DocumentReference ref, String classReference)
    {
        try {
            if (this.documentAccessBridge.getDocumentInstance(ref) instanceof XWikiDocument xdoc) {
                return xdoc;
            }
        } catch (Exception e) {
            this.logger.warn("MCP get_schema tool failed to load [{}]: [{}]", classReference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_schema tool load failure details", e);
        }
        throw new IllegalArgumentException("Could not read document " + QUOTE + classReference + QUOTE
            + PERIOD);
    }

    /**
     * Counts the instances of one class through the door, bound by the class's wiki-local serialized name.
     *
     * @param targetWiki the resolved wiki id the call operates on
     * @param localClassName the wiki-local serialized class reference
     * @return the instance count, an upper bound over what the current user may view
     * @throws QueryException if the count query fails
     */
    private long countInstances(String targetWiki, String localClassName) throws QueryException
    {
        // A single-column HQL select shapes each row as the scalar itself, not as an Object[]; the door's
        // declared row type is erased at runtime, so read the first element as a plain Object and dispatch
        // on its actual shape.
        List<?> rows = this.rowQuery.rows(COUNT_QUERY, targetWiki,
            Map.<String, Object>of(CLASSNAME_BIND, localClassName), 1);
        Object first = rows.isEmpty() ? null : rows.get(0);
        if (first instanceof Number number) {
            return number.longValue();
        }
        if (first instanceof Object[] columns && columns.length > 0 && columns[0] instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }
}
