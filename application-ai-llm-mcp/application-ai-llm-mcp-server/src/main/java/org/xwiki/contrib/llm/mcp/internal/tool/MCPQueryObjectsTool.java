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
import javax.inject.Singleton;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
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

import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that finds structured objects (XObjects) by class and field filters. The declarative parameters
 * are compiled by {@link MCPObjectQuerySupport} into one bound HQL statement - the agent never writes a
 * statement, and every dynamic statement fragment comes from closed maps owned by this module while all
 * input travels as bind values. With a {@code document} and no {@code class}, the call inventories that
 * document instead: every object it carries across classes, class names and numbers only - never a field
 * value, so the any-class listing exposes nothing a per-class call would mask.
 *
 * <p>This is a default (read-only) tool bundled with the MCP server module. A call operates on exactly one
 * wiki: the current wiki by default, or another farm wiki via the reach-gated {@code wiki} parameter. The
 * class document and the optional {@code document} restriction are resolved and authorized through
 * {@link MCPDocumentAccess}; the statement runs through the {@link MCPRowQuery} door up to the door's row
 * ceiling, EVERY fetched row is individually authorized (space filter plus view right), and paging plus the
 * reported total operate on the surviving rows only. Totals are therefore exact over what the caller may
 * view and reveal nothing about denied matches - a database-side count would tally rights-denied matches
 * under agent-chosen field predicates, turning count differences into a value-probing oracle. A call whose
 * raw fetch hits the ceiling reports its total as a floor ({@code N+}) with a note.</p>
 *
 * <p>There is deliberately NO hidden-document clause: structured data commonly lives on conventionally
 * hidden documents (configuration, application data), so excluding hidden pages would silently corrupt
 * data answers; the man page says so. References, titles, field names and stored values are wiki-authored
 * and therefore untrusted; every such fragment is neutralized and length-capped before landing in the
 * line-oriented output. Password values are never read at all.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPQueryObjectsTool.TOOL_ID)
@Singleton
public class MCPQueryObjectsTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "query_objects";

    private static final String CLASS_PARAM = "class";

    private static final String FILTERS_PARAM = "filters";

    private static final String SELECT_PARAM = "select";

    private static final String DOCUMENT_PARAM = "document";

    private static final String SORT_PARAM = "sort";

    private static final String LIMIT_PARAM = "limit";

    private static final String OFFSET_PARAM = "offset";

    private static final String WIKI_PARAM = "wiki";

    private static final int DEFAULT_LIMIT = 10;

    private static final int MAX_LIMIT = 25;

    private static final int MIN_LIMIT = 1;

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String LIST_SEPARATOR = ", ";

    private static final String IN_WIKI = " in wiki ";

    /**
     * Closing of the two result headers: the quoted wiki id's closing quote, the colon and the blank
     * line before the body.
     */
    private static final String HEADER_END = QUOTE + ":" + DOUBLE_NEW_LINE;

    private static final String DOCUMENT_QUOTE_PREFIX = "Document " + QUOTE;

    private static final String NO_SUCH_DOCUMENT_PREFIX = "No such document: " + QUOTE;

    private static final String CONTINUE_HINT = " Continue with offset=";

    /**
     * The note appended when the raw fetch came back at the door's row ceiling: rows beyond it were never
     * scanned, so the authorized total is a floor (rendered as {@code N+}).
     */
    private static final String CEILING_NOTE = "The scan hit the " + MCPRowQuery.MAX_FETCH_PER_QUERY
        + "-row fetch ceiling: matches beyond it are not counted.";

    /**
     * The agent-facing result message for a failed page or count query. The root cause (schema/driver
     * detail) stays in the server logs, off the wire.
     */
    private static final String QUERY_FAILED =
        "Could not run the object query. Try again; if it persists, report it to a wiki administrator "
            + "(details are in the server logs).";

    private static final String DESCRIPTION =
        "Find structured objects (XObjects) by class and field filters: each result names the document "
            + "holding the object, the object number and the requested field values. get_schema lists the "
            + "classes and each class's fields. With document and no class, lists every object on that "
            + "document across classes - class, number and document version only, no field values.";

    /**
     * The man-page NOTES shown on every endpoint, without any cross-wiki mention.
     */
    private static final String MAN_NOTES_BASE = """
        NOTES
            Finds the objects (instances) of one class. get_schema shows the classes of the
            wiki and each class's fields with their types; use it first.

            With document and NO class, the call inventories that document instead: every
            object it carries, across classes, grouped by class and ordered by object
            number, with the document's version. Answers "what objects does this page
            carry?". No field values are shown in this mode, and select, filters and sort
            are refused (they are class-scoped).

            Each filters entry is one condition: "<field> <op> <value>". The field is the
            first word, the op the second, and everything after the op is the value (it may
            contain spaces). All conditions must match (AND). Ops:
                =           equal
                !=          not equal
                >           greater than
                <           less than
                contains    substring match, text fields only
            Values are converted with the field's own type: numbers per the field's number
            type, dates per the class's date format (ISO-8601 also works: an instant, or a
            bare date like 2026-01-31, which compares as UTC midnight), booleans as
            0/1/true/false. Filters and sort are refused on Password fields, on computed
            fields (they have no stored values) and on list-valued fields; sort is also
            refused on large text fields.

            sort orders by an object field ("<field> asc" or "<field> desc"). Objects with
            no stored value for the sort field are EXCLUDED from sorted results.

            A result header reads "<reference> (object <N>, v<version>)": N is the object's
            number on its document (stable, and numbering may have holes) and the version is
            the document version the values were read from. In select output, Password
            values render masked and computed fields render as a fixed note.

            Hidden documents are included: structured data commonly lives on conventionally
            hidden documents, so their objects match like any other.

            Counts are exact over what you may view. A very broad query stops scanning at a
            2000-row ceiling: the count then reads "N+" and matches beyond it are not
            counted.
        """;

    /**
     * The cross-wiki NOTES paragraph, appended only when the endpoint has cross-wiki reach - the man page
     * must not advertise a parameter the advertised schema does not carry.
     */
    private static final String MAN_NOTES_CROSS_WIKI = """

            The wiki parameter queries another wiki of the farm instead of the current one
            (one wiki per call; list_wikis shows what is reachable).
        """;

    /**
     * The man-page EXAMPLES shown on every endpoint.
     */
    private static final String MAN_EXAMPLES_BASE = """

        EXAMPLES
            All instances:   class="Blog.BlogPostClass"
            Filter + fields: class="Blog.BlogPostClass",
                             filters=["published = 1", "title contains release"],
                             select=["title", "publishDate"]
            One document:    class="XWiki.XWikiComments", document="Blog.MyPost"
            Page inventory:  document="Blog.MyPost"
                             (no class: what objects does this page carry?)
            Sort and page:   class="Blog.BlogPostClass", sort="publishDate desc",
                             limit=10, offset=10
        """;

    /**
     * The cross-wiki example line, reach-gated like its NOTES paragraph.
     */
    private static final String MAN_EXAMPLE_CROSS_WIKI = """
            Another wiki:    class="Blog.BlogPostClass", wiki="second"
        """;

    /**
     * The man-page tail shown on every endpoint.
     */
    private static final String MAN_TAIL = """

        SEE ALSO
            man get_schema         The classes of the wiki and each class's fields and types.
            man query_documents    Search documents by keywords rather than by object values.
            man                    (no argument) List all tools and reference pages.
        """;

    /**
     * The full man page for cross-wiki enabled endpoints.
     */
    private static final String MAN_PAGE = MAN_NOTES_BASE + MAN_NOTES_CROSS_WIKI + MAN_EXAMPLES_BASE
        + MAN_EXAMPLE_CROSS_WIKI + MAN_TAIL;

    /**
     * The man page for reach-off endpoints: no cross-wiki paragraph, no wiki-parameter example.
     */
    private static final String MAN_PAGE_LOCAL = MAN_NOTES_BASE + MAN_EXAMPLES_BASE + MAN_TAIL;

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant omits the
     * {@code wiki} parameter, so no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPQueryObjectsTool::params);

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
            .string(CLASS_PARAM, "Reference of the class whose objects to find (e.g. "
                + "\"Blog.BlogPostClass\"). get_schema with no arguments lists the classes of this wiki. "
                + "Optional only when document is given: without it, every object on that document is "
                + "listed (class and number only, no field values).")
            .stringArray(FILTERS_PARAM, "Optional conditions, each \"<field> <op> <value>\": the field is "
                + "the first word, the op the second (one of =, !=, >, <, contains), and the rest is the "
                + "value (it may contain spaces). All conditions must match. get_schema class=\"...\" "
                + "lists the fields.")
            .stringArray(SELECT_PARAM, "Optional field names to show for each match (at most 10).")
            .string(DOCUMENT_PARAM, "Optional document reference: only that document's objects of the "
                + "class. Without class, lists every object on the document.")
            .string(SORT_PARAM, "Optional ordering by an object field: \"<field> asc\" or \"<field> "
                + "desc\". Objects without a stored value for the field are excluded.")
            .integer(LIMIT_PARAM, "Maximum number of results to return (default: %d, max: %d)."
                .formatted(DEFAULT_LIMIT, MAX_LIMIT))
            .integer(OFFSET_PARAM, "0-based index of the first result to return (default: 0). Use with "
                + "limit to page through results; the result footer tells you the offset of the next page.")
            .stringIf(crossWiki, WIKI_PARAM, "Optional wiki id to query instead of the current wiki (see "
                + "list_wikis). One wiki per call.")
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
        return "Find structured objects (XObjects) by class and field filters.";
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
            Request parsed = parseRequest(args);
            try {
                String targetWiki = this.wikiReach.resolveSingleWiki(parsed.wiki());
                if (parsed.classReference() == null) {
                    return runInventory(parsed, targetWiki);
                }
                return run(parsed, targetWiki);
            } catch (MCPAccessDeniedException e) {
                return MCPToolSupport.errorResult(e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (QueryException e) {
            this.logger.warn("MCP query_objects tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP query_objects tool failure details", e);
            // Return a fixed message: the root cause (schema/driver detail) stays in the logs, off the wire.
            return MCPToolSupport.errorResult(QUERY_FAILED);
        }
    }

    /**
     * Runs one parsed call: resolves and authorizes the class document, validates the declarative
     * parameters against it, compiles and executes the statement through the door up to the row ceiling,
     * authorizes EVERY fetched row (so the total and the paging see only what the caller may view),
     * applies offset and limit on the authorized rows, renders the page and assembles the budgeted
     * response with its footer.
     *
     * @param request the parsed arguments
     * @param targetWiki the resolved wiki id the call operates on
     * @return the tool result
     * @throws MCPAccessDeniedException when a reference fails the reach gate, the rights check or the
     *     space filter
     * @throws QueryException if the query fails
     */
    private McpSchema.CallToolResult run(Request request, String targetWiki)
        throws MCPAccessDeniedException, QueryException
    {
        WikiReference wikiRef = new WikiReference(targetWiki);
        DocumentReference classRef =
            this.documentAccess.resolveAndAuthorize(request.classReference(), Right.VIEW, wikiRef);
        XWikiDocument classDoc = loadDocument(classRef, request.classReference());
        if (classDoc.isNew()) {
            return MCPToolSupport.errorResult(NO_SUCH_DOCUMENT_PREFIX
                + MCPTextGuards.fragment(request.classReference()) + QUOTE + PERIOD);
        }
        if (MCPObjectQuerySupport.definesNoFields(classDoc)) {
            return MCPToolSupport.errorResult(DOCUMENT_QUOTE_PREFIX
                + MCPTextGuards.fragment(request.classReference()) + QUOTE
                + " exists but defines no class fields. Use get_schema with no arguments to list the "
                + "classes of this wiki.");
        }
        MCPObjectQuerySupport.validateSelect(classDoc, request.select());
        String documentFullName = null;
        if (request.document() != null) {
            documentFullName = this.localSerializer.serialize(
                this.documentAccess.resolveAndAuthorize(request.document(), Right.VIEW, wikiRef));
        }
        MCPObjectQuerySupport.CompiledObjectQuery compiled = MCPObjectQuerySupport.compile(classDoc,
            this.localSerializer.serialize(classRef), request.filters(), request.sort(), documentFullName);
        List<Object[]> rows = this.rowQuery.rows(compiled.statement(), targetWiki, compiled.binds(),
            MCPRowQuery.MAX_FETCH_PER_QUERY);
        boolean hitCeiling = rows.size() >= MCPRowQuery.MAX_FETCH_PER_QUERY;
        List<Object[]> authorized = authorizedRows(rows, wikiRef);
        int total = authorized.size();
        if (total == 0) {
            return MCPToolSupport.result(noMatches(request, hitCeiling));
        }
        requireOffsetWithinTotal(request.offset(), total);
        List<Object[]> page = pageOf(authorized, request, total);
        List<String> blocks = renderRows(page, classDoc, classRef, wikiRef, request.select());
        String body = MCPSourceText.budgeted("OBJECTS of class " + QUOTE
            + MCPTextGuards.fragment(this.localSerializer.serialize(classRef)) + QUOTE + IN_WIKI
            + QUOTE + targetWiki + HEADER_END + String.join(DOUBLE_NEW_LINE, blocks));
        return MCPToolSupport.result(body + DOUBLE_NEW_LINE
            + footer(total, blocks.size(), hitCeiling, request));
    }

    /**
     * Runs one document-only call (no {@code class}): resolves and authorizes the document exactly like
     * the class-scoped document restriction, runs the any-class inventory statement through the door,
     * authorizes every fetched row the same way, applies the same paging, and renders the objects grouped
     * by class - class names and object numbers only, never a field value. The document version shown in
     * the header is the version every listed object was read at (a single document has a single version).
     *
     * @param request the parsed arguments
     * @param targetWiki the resolved wiki id the call operates on
     * @return the tool result
     * @throws MCPAccessDeniedException when the document fails the reach gate, the rights check or the
     *     space filter
     * @throws QueryException if the query fails
     */
    private McpSchema.CallToolResult runInventory(Request request, String targetWiki)
        throws MCPAccessDeniedException, QueryException
    {
        WikiReference wikiRef = new WikiReference(targetWiki);
        DocumentReference docRef =
            this.documentAccess.resolveAndAuthorize(request.document(), Right.VIEW, wikiRef);
        String localDocName = this.localSerializer.serialize(docRef);
        MCPObjectQuerySupport.CompiledObjectQuery compiled =
            MCPObjectQuerySupport.compileDocumentInventory(localDocName);
        List<Object[]> rows = this.rowQuery.rows(compiled.statement(), targetWiki, compiled.binds(),
            MCPRowQuery.MAX_FETCH_PER_QUERY);
        boolean hitCeiling = rows.size() >= MCPRowQuery.MAX_FETCH_PER_QUERY;
        List<Object[]> authorized = authorizedRows(rows, wikiRef);
        int total = authorized.size();
        if (total == 0) {
            return emptyInventoryResult(docRef, localDocName, hitCeiling);
        }
        requireOffsetWithinTotal(request.offset(), total);
        List<Object[]> page = pageOf(authorized, request, total);
        XWikiDocument resultDoc = loadResult(docRef);
        String versionSuffix = resultDoc != null
            ? " (v" + MCPTextGuards.fragment(resultDoc.getVersion()) + ")" : "";
        String body = MCPSourceText.budgeted("OBJECTS on document " + QUOTE + MCPTextGuards.fragment(localDocName)
            + QUOTE + versionSuffix
            + IN_WIKI + QUOTE + targetWiki + HEADER_END
            + MCPObjectQuerySupport.renderDocumentInventory(page));
        return MCPToolSupport.result(body + DOUBLE_NEW_LINE
            + footer(total, page.size(), hitCeiling, request));
    }

    /**
     * Builds the result of an inventory that found no rows, distinguishing a nonexistent document from a
     * really object-free one: the (already authorized) document is loaded and a new document gets the same
     * no-such-document error as the class-scoped mode, so an agent inventorying a mistyped reference is
     * told the page does not exist instead of reading that it carries no objects. The distinction costs
     * one document load and only ever runs on the empty path; a document that exists but cannot be loaded
     * keeps the carries-no-objects message (zero viewable objects remains true).
     *
     * @param docRef the resolved and authorized document reference
     * @param localDocName the wiki-local serialized document name
     * @param hitCeiling whether the raw fetch came back at the door's row ceiling
     * @return the tool result
     */
    private McpSchema.CallToolResult emptyInventoryResult(DocumentReference docRef, String localDocName,
        boolean hitCeiling)
    {
        XWikiDocument targetDoc = loadResult(docRef);
        if (targetDoc != null && targetDoc.isNew()) {
            return MCPToolSupport.errorResult(NO_SUCH_DOCUMENT_PREFIX + MCPTextGuards.fragment(localDocName)
                + QUOTE + PERIOD);
        }
        String message = DOCUMENT_QUOTE_PREFIX + MCPTextGuards.fragment(localDocName) + QUOTE
            + " carries no objects.";
        return MCPToolSupport.result(hitCeiling ? message + NEW_LINE + CEILING_NOTE : message);
    }

    /**
     * Refuses an offset pointing beyond the last authorized match, shared by the class-scoped and
     * inventory modes.
     *
     * @param offset the requested 0-based offset
     * @param total the number of authorized matches
     * @throws IllegalArgumentException with an agent-facing message when the offset is out of range
     */
    private static void requireOffsetWithinTotal(int offset, int total)
    {
        if (offset >= total) {
            throw new IllegalArgumentException("offset " + offset + " is beyond the last match ("
                + total + " total matches). Use an offset below " + total + PERIOD);
        }
    }

    /**
     * @param authorized the authorized rows
     * @param request the parsed arguments, carrying the validated offset and clamped limit
     * @param total the number of authorized rows
     * @return the page slice of the authorized rows
     */
    private static List<Object[]> pageOf(List<Object[]> authorized, Request request, int total)
    {
        return authorized.subList(request.offset(), Math.min(request.offset() + request.limit(), total));
    }

    /**
     * Keeps only the rows the current user may see: each row's document is resolved into the target wiki
     * and authorized through the door (space filter, then view right); a denied row is dropped before
     * anything about it is read or counted, so denied matches leave no trace in totals or paging.
     *
     * @param rows the raw {@code (doc.fullName, obj.number, ...)} rows
     * @param wikiRef the target wiki
     * @return the surviving rows, in fetch order
     */
    private List<Object[]> authorizedRows(List<Object[]> rows, WikiReference wikiRef)
    {
        List<Object[]> authorized = new ArrayList<>();
        for (Object[] columns : rows) {
            if (this.rowQuery.authorizedDocument((String) columns[0], wikiRef) != null) {
                authorized.add(columns);
            }
        }
        return authorized;
    }

    /**
     * Renders one page of already-authorized rows. The authorization is re-checked per row as a guard
     * (the door call is cheap and the answer is the resolved reference the renderer needs anyway); a row
     * whose document fails to load or whose object vanished between the query and the read is skipped.
     *
     * @param page the authorized {@code (doc.fullName, obj.number, ...)} rows of this page
     * @param classDoc the loaded class document
     * @param classRef the resolved class reference
     * @param wikiRef the target wiki
     * @param select the validated field names to show, possibly {@code null}
     * @return the rendered blocks of the surviving rows
     */
    private List<String> renderRows(List<Object[]> page, XWikiDocument classDoc, DocumentReference classRef,
        WikiReference wikiRef, List<String> select)
    {
        List<String> blocks = new ArrayList<>();
        for (Object[] columns : page) {
            String fullName = (String) columns[0];
            int number = ((Number) columns[1]).intValue();
            DocumentReference ref = this.rowQuery.authorizedDocument(fullName, wikiRef);
            if (ref == null) {
                continue;
            }
            XWikiDocument resultDoc = loadResult(ref);
            if (resultDoc == null) {
                continue;
            }
            String block = MCPObjectQuerySupport.renderResult(resultDoc, classDoc, classRef, number,
                this.localSerializer.serialize(ref), select);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    /**
     * Builds the message for a call with no viewable matches: the not-found message with the
     * active-filter echo, plus the ceiling note when the raw scan was cut (matches may exist beyond the
     * ceiling, viewable or not).
     *
     * @param request the parsed arguments
     * @param hitCeiling whether the raw fetch came back at the door's row ceiling
     * @return the agent-facing message
     */
    private String noMatches(Request request, boolean hitCeiling)
    {
        String message = "No objects of class " + QUOTE + MCPTextGuards.fragment(request.classReference())
            + QUOTE + " found."
            + describeActiveFilters(request);
        return hitCeiling ? message + NEW_LINE + CEILING_NOTE : message;
    }

    /**
     * Builds the trailing count/paging footer. The total is exact over what the caller may view (denied
     * matches are dropped before counting); when the raw scan hit the row ceiling it is a floor, rendered
     * as {@code N+} with the ceiling note. The continue hint only appears while matches remain, and the
     * capped-limit notice only when the cap actually hid results.
     *
     * @param total the number of authorized matches
     * @param shown the number of objects rendered in this page
     * @param hitCeiling whether the raw fetch came back at the door's row ceiling
     * @param request the parsed arguments
     * @return the footer line(s)
     */
    private String footer(int total, int shown, boolean hitCeiling, Request request)
    {
        String suffix = total == 1 && !hitCeiling ? " matching object" : " matching objects";
        StringBuilder footer = new StringBuilder("Found ").append(total).append(hitCeiling ? "+" : "")
            .append(suffix).append(PERIOD).append(" Showing ").append(shown)
            .append(" from offset ").append(request.offset()).append(PERIOD);
        long nextOffset = (long) request.offset() + request.limit();
        if (nextOffset < total) {
            footer.append(CONTINUE_HINT).append(nextOffset).append(PERIOD);
        }
        if (request.limitCapped() && total > MAX_LIMIT) {
            footer.append(" (The requested limit was capped to the maximum of ").append(MAX_LIMIT)
                .append(" per page.)");
        }
        if (hitCeiling) {
            footer.append(NEW_LINE).append(CEILING_NOTE);
        }
        return footer.toString();
    }

    /**
     * Describes the narrowing parameters that were active, so an empty result set tells the agent what it
     * constrained by.
     *
     * @param request the parsed arguments
     * @return a trailing sentence listing the active filters, or an empty string when none were set
     */
    private String describeActiveFilters(Request request)
    {
        List<String> parts = new ArrayList<>();
        if (request.filters() != null && !request.filters().isEmpty()) {
            parts.add("filters=[" + String.join("; ", request.filters()) + "]");
        }
        if (request.document() != null) {
            parts.add("document=" + request.document());
        }
        if (request.sort() != null) {
            parts.add("sort=" + request.sort());
        }
        if (StringUtils.isNotBlank(request.wiki())) {
            parts.add("wiki=" + request.wiki());
        }
        return parts.isEmpty() ? ""
            : " Active filters: " + MCPToolSupport.stripLineBreaks(String.join(LIST_SEPARATOR, parts))
                + PERIOD;
    }

    /**
     * Parses and validates the call arguments.
     *
     * @param args the tool call arguments
     * @return the parsed request
     * @throws IllegalArgumentException with an agent-facing message on a missing class, a class-scoped
     *     parameter sent without a class, or an invalid paging value
     */
    private Request parseRequest(Map<String, Object> args)
    {
        String classReference = PARAMS.parser().string(args, CLASS_PARAM);
        List<String> filters = PARAMS.parser().stringList(args, FILTERS_PARAM);
        List<String> select = PARAMS.parser().stringList(args, SELECT_PARAM);
        String document = PARAMS.parser().string(args, DOCUMENT_PARAM);
        String sort = PARAMS.parser().string(args, SORT_PARAM);
        if (classReference == null) {
            validateClassOmitted(document, filters, select, sort);
        }
        int requestedLimit = PARAMS.parser().integer(args, LIMIT_PARAM, DEFAULT_LIMIT);
        int limit = Math.min(Math.max(requestedLimit, MIN_LIMIT), MAX_LIMIT);
        int offset = PARAMS.parser().integer(args, OFFSET_PARAM, 0);
        if (offset < 0) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + OFFSET_PARAM
                + "' must be >= 0.");
        }
        String wiki = PARAMS.parser().string(args, WIKI_PARAM);
        return new Request(classReference, filters, select, document, sort, limit, offset, wiki,
            requestedLimit > MAX_LIMIT);
    }

    /**
     * Validates a call sent without a {@code class}: it must carry a {@code document} (the call is then a
     * document inventory) and must not carry the class-scoped parameters - {@code select}, {@code filters}
     * and {@code sort} are validated against a class's field definitions, so without a class they cannot
     * mean anything.
     *
     * @param document the raw {@code document} argument, possibly {@code null}
     * @param filters the raw filter entries, possibly {@code null}
     * @param select the requested field names, possibly {@code null}
     * @param sort the raw sort parameter, possibly {@code null}
     * @throws IllegalArgumentException with a teaching message on either violation
     */
    private static void validateClassOmitted(String document, List<String> filters, List<String> select,
        String sort)
    {
        if (document == null) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + CLASS_PARAM
                + "' parameter is required unless '" + DOCUMENT_PARAM + "' is given: a document-only call "
                + "lists every object on that document.");
        }
        if (CollectionUtils.isNotEmpty(filters) || CollectionUtils.isNotEmpty(select) || sort != null) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + SELECT_PARAM + "', '"
                + FILTERS_PARAM + "' and '" + SORT_PARAM + "' are class-scoped; pass '" + CLASS_PARAM
                + "' to use them. A document-only call lists only each object's class and number.");
        }
    }

    /**
     * Loads the already-authorized class document as an {@link XWikiDocument} (the type that exposes the
     * class definition and objects).
     *
     * @param ref the resolved and authorized class reference
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
            this.logger.warn("MCP query_objects tool failed to load [{}]: [{}]", classReference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP query_objects tool load failure details", e);
        }
        throw new IllegalArgumentException("Could not read document " + QUOTE
            + MCPTextGuards.fragment(classReference) + QUOTE + PERIOD);
    }

    /**
     * Loads one already-authorized result document; a load failure only skips that row (logged at DEBUG),
     * it does not fail the page.
     *
     * @param ref the resolved and authorized result-document reference
     * @return the loaded document, or {@code null} when it cannot be loaded
     */
    private XWikiDocument loadResult(DocumentReference ref)
    {
        try {
            if (this.documentAccessBridge.getDocumentInstance(ref) instanceof XWikiDocument xdoc) {
                return xdoc;
            }
        } catch (Exception e) {
            this.logger.debug("MCP query_objects tool could not load result [{}]", ref, e);
        }
        return null;
    }

    /**
     * The parsed and validated arguments of one call, bundled so the compile, execute and format steps
     * share one immutable view of the request.
     *
     * @param classReference the raw {@code class} argument, or {@code null} for a document-only inventory
     *     (then {@code document} is guaranteed non-{@code null} and the class-scoped parameters are absent)
     * @param filters the raw filter entries, possibly {@code null}
     * @param select the field names to show per match, possibly {@code null}
     * @param document the raw document restriction, possibly {@code null}
     * @param sort the raw sort parameter, possibly {@code null}
     * @param limit the page size, already clamped to the allowed range
     * @param offset the 0-based index of the first raw match to return, already validated as &gt;= 0
     * @param wiki the raw {@code wiki} parameter value, possibly {@code null}
     * @param limitCapped whether the requested limit exceeded {@link #MAX_LIMIT} and was reduced to it
     * @version $Id$
     */
    private record Request(String classReference, List<String> filters, List<String> select, String document,
        String sort, int limit, int offset, String wiki, boolean limitCapped)
    {
    }
}
