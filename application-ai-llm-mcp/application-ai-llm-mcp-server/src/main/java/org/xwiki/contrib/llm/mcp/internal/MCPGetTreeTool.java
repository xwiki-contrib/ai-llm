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
package org.xwiki.contrib.llm.mcp.internal;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
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
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.QueryException;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that renders a wiki as navigable text, so an agent can orient itself: which spaces and pages exist
 * and how they nest, without reading every page. A blank {@code root} surveys the whole wiki (one summary line
 * per top-level space, with page counts and recency, small spaces expanded inline); a page {@code root}
 * explores that subtree as an indented tree, one line per page.
 *
 * <p>This is a default (read-only) tool bundled with the MCP server module. A call operates on exactly one
 * wiki: the current wiki by default, or another farm wiki via the reach-gated {@code wiki} parameter (resolved
 * through {@link MCPWikiReach#resolveSingleWiki(String)}). A page {@code root} is resolved through
 * {@link MCPDocumentAccess#resolveAndAuthorize(String, Right, WikiReference)}, which resolves it into the
 * target wiki, checks {@link Right#VIEW} and applies the space filter; the explore descent is breadth-first
 * for {@code depth} levels. Every rendered reference is {@code get_document}-ready, and a trailing {@code ⋯}
 * marks a node whose children were not shown, so the agent can re-call with that reference as the new
 * {@code root}. Every rendered node is authorized individually (space filter plus {@link Right#VIEW}), so a
 * denied page is dropped entirely, and hidden pages are excluded unless {@code showHidden} is set, regardless
 * of the caller's profile preference.</p>
 *
 * <p>Page text rendered into the tree (titles, page names and object-class names) is editable wiki content and is
 * therefore untrusted. It is neutralized for the line grammar - control characters and the framing characters
 * (<code>"[]{}</code> and the {@code ⋯} more-children signal) are stripped or replaced and the text is
 * length-capped - so a crafted title cannot forge a row, a second reference or a fake marker, mirroring the
 * hardening of the rendered {@code get_document} view. It is otherwise passed through as-is: treat it as page
 * data, not as instructions.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPGetTreeTool.TOOL_ID)
@Singleton
public class MCPGetTreeTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "get_tree";

    /**
     * Global cap on the number of rendered nodes in one explore response, so a broad root cannot flood the
     * context window. Descent stops once this many nodes have been accepted.
     */
    private static final int MAX_NODES = 300;

    private static final int DEFAULT_DEPTH = 2;

    private static final int MAX_DEPTH = 4;

    private static final int MIN_DEPTH = 1;

    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    private static final int MIN_LIMIT = 1;

    /**
     * Cap on the composed marker string of one node, so a page carrying many object classes cannot blow up a
     * line; a longer marker is truncated at an item boundary.
     */
    private static final int MAX_MARKER_CHARS = 120;

    /**
     * Cap on a single rendered page-text fragment (title or name), so a long crafted title cannot dominate a
     * line. A longer fragment is cut and marked.
     */
    private static final int MAX_TITLE_CHARS = 200;

    /**
     * The door's absolute per-query row ceiling ({@link MCPRowQuery#MAX_FETCH_PER_QUERY}), aliased for the
     * fetch-limit computations and the sampled-counts note. In explore mode only {@link #MAX_NODES} are ever
     * rendered, so this ceiling never truncates a real result before the Java-side caps do; in survey mode
     * hitting it flags the counts as sampled.
     */
    private static final int MAX_FETCH_PER_QUERY = MCPRowQuery.MAX_FETCH_PER_QUERY;

    /**
     * Cap on the number of retained pages under which a surveyed space is expanded inline; a space with more
     * pages renders as a summary line only.
     */
    private static final int INLINE_THRESHOLD = 8;

    /**
     * Global cap on the total inlined lines of one survey; once it would be exceeded, remaining small spaces
     * render summary-only.
     */
    private static final int INLINE_BUDGET = 100;

    private static final int SECONDS_PER_MINUTE = 60;

    private static final int MINUTES_PER_HOUR = 60;

    private static final int HOURS_PER_DAY = 24;

    private static final int DAYS_PER_MONTH = 30;

    private static final int DAYS_PER_YEAR = 365;

    /**
     * Ceiling of the month bucket: with 30-day months, days 360-364 would otherwise read {@code 12mo ago}
     * while still short of the year bucket.
     */
    private static final int MAX_MONTHS = 11;

    private static final int MILLIS_PER_SECOND = 1000;

    /**
     * Matches a run of control characters ({@code \p{Cc}}), line/paragraph separators ({@code \p{Zl}}/
     * {@code \p{Zp}}, U+2028/U+2029) and whitespace, collapsed to a single space when neutralizing page text
     * for a tree line. The separator categories are named explicitly because {@code \p{Cc}} and {@code \s} both
     * miss U+2028/U+2029.
     */
    private static final Pattern CONTROL_OR_SPACE_RUN = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\s]+");

    /**
     * Matches the structural framing characters removed from page text: the reference and marker brackets, and
     * the {@code ⋯} (U+22EF) more-children signal.
     */
    private static final Pattern FRAMING_CHARS = Pattern.compile("[\\[\\]{}⋯]");

    /**
     * Marks a page-text fragment cut at {@link #MAX_TITLE_CHARS} and a marker list truncated at an item
     * boundary; the horizontal ellipsis (U+2026) is distinct from the {@code ⋯} more-children signal (U+22EF).
     */
    private static final String TITLE_ELLIPSIS = "…";

    private static final String ROOT_PARAM = "root";

    private static final String WIKI_PARAM = "wiki";

    private static final String DEPTH_PARAM = "depth";

    private static final String LIMIT_PARAM = "limit";

    private static final String OFFSET_PARAM = "offset";

    private static final String SHOW_HIDDEN_PARAM = "showHidden";

    /**
     * Shared tail of the depth/limit/offset parameter descriptions, naming the mode they apply to.
     */
    private static final String EXPLORE_ONLY = " Explore mode only (when root is given).";

    private static final String IN_SURVEY = " In a survey (no root), ";

    private static final String WEBHOME = "WebHome";

    private static final String PARENTS_BIND = "parents";

    private static final String NAMES_BIND = "names";

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String INDENT = "  ";

    private static final String SLASH = "/";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String LIST_SEPARATOR = ", ";

    private static final String REF_OPEN = "  [";

    private static final String REF_CLOSE = "]";

    /**
     * Opens the quoted-title segment of a node line: the gap after the reference bracket plus the opening
     * quote.
     */
    private static final String TITLE_OPEN = INDENT + QUOTE;

    private static final String ELLIPSIS_SUFFIX = " ⋯";

    private static final String OPEN_BRACE = " {";

    private static final String CLOSE_BRACE = "}";

    private static final String ATT_PREFIX = "att:";

    private static final String TREE_PREFIX = "Tree: root=";

    private static final String PAGE_KIND = "page";

    private static final String WIKI_SURVEY_KIND = "wiki survey";

    private static final String OPEN_PAREN = " (";

    private static final String CLOSE_PAREN = ")";

    private static final String PAREN_DEPTH = "), depth=";

    private static final String DASH_SEP = " — ";

    /**
     * Opens the counts segment of a survey space line; the preceding gap comes from {@link #INDENT}.
     */
    private static final String SURVEY_DASH = "— ";

    private static final String PAGES_SHOWN = " pages shown";

    private static final String SPACES_SUFFIX = " spaces";

    private static final String PAGES_SUFFIX = " pages";

    private static final String PAGE_SUFFIX = " page";

    private static final String PLUS = "+";

    private static final String HIDDEN_PLUS = " + ";

    private static final String HIDDEN_SUFFIX = " hidden";

    private static final String BRANCHES_TRUNCATED = " branches truncated";

    private static final String HIDDEN_INCLUDED = ", hidden included";

    private static final String SAMPLED_NOTE =
        ", counts sampled from the first " + MAX_FETCH_PER_QUERY + PAGES_SUFFIX;

    private static final String LEGEND = "Refs are get_document-ready. A trailing / marks a page that can have "
        + "children; ⋯ marks children not shown (re-call with root set to that ref). {…} lists notable "
        + "objects / attachment count.";

    private static final String SURVEY_LEGEND = "Refs are get_document-ready. One line per top-level space; "
        + "re-call with root set to a ref to explore it. Small spaces are expanded inline.";

    private static final String TERMINAL_NOTE = "This page is terminal and has no child pages.";

    private static final String NO_CHILDREN = "No child pages.";

    private static final String NO_SPACES = "No visible spaces.";

    /**
     * Shared tail of the paging hints, naming the parameter that pages to the next block.
     */
    private static final String OFFSET_HINT = "; re-call with " + OFFSET_PARAM + "=";

    private static final String ROOT_MORE_NOTE = "More direct children exist beyond this page" + OFFSET_HINT;

    private static final String SURVEY_MORE_NOTE = "More top-level spaces exist beyond this page" + OFFSET_HINT;

    private static final String TRUNCATION_FOOTER_HEAD = "Output truncated at the ~" + MAX_NODES
        + "-node budget; ";

    private static final String TRUNCATION_FOOTER_TAIL = " branch(es) not expanded. Narrow the view with a "
        + "deeper root or a smaller depth.";

    private static final String QUERY_FAILED =
        "Failed to read the page hierarchy. Try again; if it persists, report it to a wiki administrator "
            + "(details are in the server logs).";

    private static final String JUST_NOW = "just now";

    private static final String AGO = " ago";

    /**
     * Shared {@code XWikiSpace}-join and translation clause of the space-home queries; the {@code doc} and
     * {@code space} aliases it introduces are what the door's explicit hidden clauses predicate on.
     */
    private static final String SPACE_JOIN =
        " where doc.space = space.reference and doc.translation = 0";

    /**
     * Child-spaces statement base for explore levels &ge; 1: the {@code WebHome} documents of the spaces whose
     * parent space is in the bound frontier, keyed by {@code space.parent}. Has a {@code space} alias, so both
     * of the door's hidden clauses apply to it (see {@link MCPRowQuery#hierarchyRows}).
     */
    private static final String CHILD_SPACES_QUERY_BASE =
        "select doc.fullName, space.parent, doc.title from XWikiDocument doc, XWikiSpace space" + SPACE_JOIN
            + " and doc.name = 'WebHome' and space.parent in (:parents)";

    /**
     * Terminal-child-pages statement base for explore levels &ge; 1: the non-{@code WebHome} documents whose
     * own space is in the bound frontier, keyed by {@code doc.space}. It has no {@code space} alias, so only
     * the door's hidden-document clause applies to it.
     */
    private static final String TERMINAL_DOCS_QUERY_BASE =
        "select doc.fullName, doc.space, doc.title from XWikiDocument doc"
            + " where doc.translation = 0 and doc.name <> 'WebHome' and doc.space in (:parents)";

    /**
     * The complete survey statement: every document of the wiki with its title, last-modification date and
     * hidden flag, in {@code doc.fullName} order. Hidden rows are deliberately fetched even when hidden pages
     * are excluded - they feed the {@code +N hidden} counts and are separated in Java - so the door runs it as
     * a complete statement and never composes the hidden clauses into it.
     */
    private static final String SURVEY_QUERY =
        "select doc.fullName, doc.title, doc.date, doc.hidden from XWikiDocument doc"
            + " where doc.translation = 0 order by doc.fullName";

    /**
     * Object-class marker query: the distinct XClass names carried by each rendered node, keyed by local
     * full name.
     */
    private static final String OBJECT_CLASSES_QUERY =
        "select obj.name, obj.className from BaseObject as obj where obj.name in (:names)"
            + " group by obj.name, obj.className";

    /**
     * Attachment-count marker query: the number of attachments of each rendered node, keyed by local full name.
     */
    private static final String ATTACHMENTS_QUERY =
        "select doc.fullName, count(attachment) from XWikiDocument doc, XWikiAttachment attachment"
            + " where attachment.docId = doc.id and doc.fullName in (:names) group by doc.fullName";

    /**
     * Local XClass names hidden from the object markers: technical classes present on ordinary pages that carry
     * no navigational signal. Kept as a constant so the noise set is tunable.
     */
    private static final Set<String> NOISE_CLASSES = Set.of(
        "XWiki.TagClass",
        "XWiki.XWikiComments",
        "XWiki.XWikiRights",
        "XWiki.XWikiGlobalRights",
        "XWiki.XWikiPreferences",
        "XWiki.WatchListClass");

    private static final String DESCRIPTION =
        "Survey a wiki (omit root: per-space summary with page counts and recency) or explore a subtree "
            + "(root: indented tree of pages). Every ref is get_document-ready; re-call with a ref as root "
            + "to zoom in.";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant omits the
     * {@code wiki} parameter, so no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPGetTreeTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPRowQuery rowQuery;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * Builds the declared parameter set, only including the cross-wiki {@code wiki} parameter when cross-wiki
     * reach is advertised, preserving the parameter order in the advertised schema.
     *
     * @param crossWiki whether to advertise the cross-wiki {@code wiki} parameter
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        return MCPToolSupport.builder()
            .string(ROOT_PARAM, "Optional page reference to explore from (dotted, e.g. \"Sales.WebHome\" or "
                + "\"Sales\"). Omit for a survey of the whole wiki: one summary line per top-level space.")
            .stringIf(crossWiki, WIKI_PARAM, "Optional wiki id to render instead of the current wiki (see "
                + "list_wikis). One wiki per call.")
            .integer(DEPTH_PARAM, "How many levels deep to render (default 2, clamped to 1-4)." + EXPLORE_ONLY)
            .integer(LIMIT_PARAM, "Maximum child pages shown per node (default 50, clamped to 1-200). A node "
                + "with more children is marked with ⋯." + IN_SURVEY + "caps the top-level spaces listed.")
            .integer(OFFSET_PARAM, "Skip this many of the root's direct children before listing (default 0), "
                + "to page a very wide root." + IN_SURVEY + "skips that many top-level spaces.")
            .bool(SHOW_HIDDEN_PARAM, "If true, include hidden pages (default false: hidden pages are always "
                + "excluded, regardless of your profile preference).")
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
        return "Search & Navigation";
    }

    @Override
    public String getSummary()
    {
        return "Survey a wiki or explore a subtree as an indented page tree.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                The navigation loop: survey first (no root), then re-call with root set to a listed
                ref to explore that subtree as an indented tree, one page per line. A trailing /
                marks a page that can hold children; a node marked with a trailing ellipsis has more
                children than were shown - re-call with that node's reference as the new root to
                keep zooming. Survey lines carry page counts, a hidden-page count and the last
                content activity; small spaces are expanded inline.

                depth (default 2, max 4) sets how many levels an explore renders; it is ignored in a
                survey. limit (default 50, max 200) caps the children shown under one node and offset
                skips that many of the root's direct children; in a survey, limit and offset page the
                list of top-level spaces instead.

                Each node may carry a {...} marker: notable object classes it holds (technical classes
                such as comments, tags and rights are omitted), and att:N for its attachment count. A
                node with neither carries no marker.

                Hidden pages are always excluded by default, regardless of your account's "display
                hidden documents" preference. Set showHidden=true to include them.

                On cross-wiki enabled endpoints, the wiki parameter renders another wiki of the farm
                instead of the current one (one wiki per call; list_wikis shows what is reachable).

            EXAMPLES
                Survey this wiki:     (call with no arguments)
                A subtree:            root="Sales.WebHome", depth=3
                A space by name:      root="Sales"   (resolves to Sales.WebHome when Sales is not a page)
                Page a root:          root="Sales.WebHome", limit=20, offset=20
                Survey another wiki:  wiki="second"

            SEE ALSO
                man get_document       Read one page found here, by its Reference.
                man query_documents    Search documents when you know keywords rather than location.
                man                    (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            TreeRequest req = parseRequest(args);

            RootScope scope;
            try {
                String targetWiki = this.wikiReach.resolveSingleWiki(req.wiki());
                scope = resolveRoot(req.root(), targetWiki);
            } catch (MCPAccessDeniedException e) {
                return MCPToolSupport.errorResult(e.getMessage());
            }

            return MCPToolSupport.result(buildAndRender(scope, req));
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (QueryException e) {
            this.logger.warn("MCP get_tree tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_tree tool failure details", e);
            // Return a fixed message: the root cause (schema/driver detail) stays in the logs, off the wire.
            return MCPToolSupport.errorResult(QUERY_FAILED);
        }
    }

    private TreeRequest parseRequest(Map<String, Object> args)
    {
        String root = PARAMS.parser().string(args, ROOT_PARAM);
        String wiki = PARAMS.parser().string(args, WIKI_PARAM);
        int depth = clamp(PARAMS.parser().integer(args, DEPTH_PARAM, DEFAULT_DEPTH), MIN_DEPTH, MAX_DEPTH);
        int limit = clamp(PARAMS.parser().integer(args, LIMIT_PARAM, DEFAULT_LIMIT), MIN_LIMIT, MAX_LIMIT);
        int offset = Math.max(PARAMS.parser().integer(args, OFFSET_PARAM, 0), 0);
        boolean showHidden = PARAMS.parser().bool(args, SHOW_HIDDEN_PARAM);
        return new TreeRequest(root, wiki, depth, limit, offset, showHidden);
    }

    private static int clamp(int value, int low, int high)
    {
        return Math.min(Math.max(value, low), high);
    }

    /**
     * Resolves the tree root within the already-resolved target wiki. A blank root is a whole-wiki survey; a
     * page root goes through the sanctioned resolve-and-authorize door, which resolves it into the target wiki
     * (rejecting a contradicting wiki prefix), enforces {@link Right#VIEW} and applies the space filter. A bare
     * space name whose literal resolution names no existing document follows that space's home instead (see
     * {@link #followSpaceHome}), so {@code root="Sales"} explores the Sales space as promised by the parameter
     * description; the header then displays the followed reference.
     *
     * @param root the raw root argument, or {@code null}/blank for a survey
     * @param targetWiki the resolved target wiki id
     * @return the resolved root scope
     * @throws MCPAccessDeniedException when the root reference fails the rights check or the space filter, or
     *     when its explicit wiki prefix contradicts the call's target wiki
     */
    private RootScope resolveRoot(String root, String targetWiki) throws MCPAccessDeniedException
    {
        boolean sameWiki = targetWiki.equals(this.contextProvider.get().getWikiId());
        if (StringUtils.isBlank(root)) {
            return new RootScope(targetWiki, null, true, sanitizeReference(targetWiki), false, sameWiki);
        }
        WikiReference wikiContext = new WikiReference(targetWiki);
        DocumentReference literal = this.documentAccess.resolveAndAuthorize(root, Right.VIEW, wikiContext);
        DocumentReference rootDoc = followSpaceHome(root, literal, wikiContext);
        boolean terminal = !WEBHOME.equals(rootDoc.getName());
        return new RootScope(targetWiki, rootDoc, false, renderRef(rootDoc, sameWiki), terminal, sameWiki);
    }

    /**
     * Follows a bare space name to that space's home. When the literal, already-authorized resolution is not
     * itself a {@code WebHome} and names no existing document, the raw input with {@code .WebHome} appended is
     * resolved and authorized through the same door (appending to the raw string, so a bare {@code "Sandbox"}
     * yields the top-level {@code Sandbox.WebHome} rather than a home under the resolver's default space).
     * The fallback is used only when it resolves to a {@code WebHome} different from the literal reference;
     * a denied or unhelpful fallback keeps the literal behavior. The literal reference is authorized before
     * any existence probe, and the fallback is authorized before its result is used, so this leaks no
     * existence information about unauthorized documents.
     *
     * @param root the raw root argument
     * @param literal the literal, already-authorized resolution of {@code root}
     * @param wikiContext the wiki the call operates on
     * @return the space home to follow, or {@code literal} when there is nothing to follow
     */
    private DocumentReference followSpaceHome(String root, DocumentReference literal, WikiReference wikiContext)
    {
        if (WEBHOME.equals(literal.getName()) || documentExists(literal)) {
            return literal;
        }
        try {
            DocumentReference fallback =
                this.documentAccess.resolveAndAuthorize(root + PERIOD + WEBHOME, Right.VIEW, wikiContext);
            if (WEBHOME.equals(fallback.getName()) && !fallback.equals(literal)) {
                return fallback;
            }
        } catch (MCPAccessDeniedException e) {
            this.logger.debug("Space-home fallback for get_tree root [{}] was denied; keeping the literal "
                + "resolution", root, e);
        }
        return literal;
    }

    /**
     * @param reference the already-authorized reference to probe
     * @return whether the document exists; an unverifiable store reads as existing, so the space-home fallback
     *     is skipped rather than acted on with unverified information
     */
    private boolean documentExists(DocumentReference reference)
    {
        try {
            return this.documentAccessBridge.exists(reference);
        } catch (Exception e) {
            this.logger.debug("Existence probe failed for [{}]; treating the document as existing", reference,
                e);
            return true;
        }
    }

    /**
     * Dispatches on the mode and renders the response. A blank root surveys the wiki; a terminal page root (a
     * non-{@code WebHome} page, which cannot have children) short-circuits to a header and a note; any other
     * page root is explored.
     *
     * @param scope the resolved root scope
     * @param req the parsed request
     * @return the rendered response text
     * @throws QueryException if a hierarchy, survey or marker query fails
     */
    private String buildAndRender(RootScope scope, TreeRequest req) throws QueryException
    {
        if (scope.wikiRoot()) {
            return buildAndRenderSurvey(scope, req);
        }
        if (scope.terminal()) {
            return headerBlock(scope, req, new Budget(), false, 0) + DOUBLE_NEW_LINE + TERMINAL_NOTE;
        }

        Budget budget = new Budget();
        Level0Result level0 = fetchLevel0(scope, req, budget);
        descend(level0.roots(), req, scope.targetWiki(), budget);

        List<String> localNames = new ArrayList<>();
        collectLocalNames(level0.roots(), localNames);
        Map<String, List<String>> classMarkers = fetchObjectClasses(scope.targetWiki(), localNames);
        Map<String, Integer> attachmentMarkers = fetchAttachments(scope.targetWiki(), localNames);

        return renderTree(scope, req, level0, budget, classMarkers, attachmentMarkers);
    }

    /**
     * Descends the frontier breadth-first, rendering deeper levels until the render depth is reached, the
     * frontier empties, or the node budget truncates the run. After the last rendered level a boundary fetch
     * marks which of its nodes still have (unrendered) children.
     *
     * @param roots the level-0 nodes
     * @param req the parsed request
     * @param wiki the target wiki
     * @param budget the shared node budget
     * @throws QueryException if a hierarchy query fails
     */
    private void descend(List<TreeNode> roots, TreeRequest req, String wiki, Budget budget) throws QueryException
    {
        List<TreeNode> frontier = webHomes(roots);
        int renderedLevels = 1;
        while (renderedLevels < req.depth() && !frontier.isEmpty() && !budget.isTruncated()) {
            frontier = expandLevel(frontier, req, wiki, budget);
            renderedLevels++;
        }
        if (!frontier.isEmpty() && !budget.isTruncated()) {
            markBoundary(frontier, req, wiki);
        }
    }

    /**
     * Computes the database-level row ceiling for a descent fetch of the given frontier: enough to satisfy the
     * per-node breadth cap for every parent, but never more than {@link #MAX_FETCH_PER_QUERY}, so a broad
     * frontier cannot force the store to materialize an unbounded child set.
     *
     * @param parentCount the number of frontier parents batched into the query
     * @param perNodeLimit the per-node breadth cap
     * @return the bounded row ceiling to bind with {@code setLimit}
     */
    private static int descentFetchLimit(int parentCount, int perNodeLimit)
    {
        return Math.min(parentCount * (perNodeLimit + 1), MAX_FETCH_PER_QUERY);
    }

    /**
     * Fetches the root's direct children (level 0) and applies the root-only {@code offset}/{@code limit}
     * paging and the node budget.
     *
     * @param scope the resolved root scope
     * @param req the parsed request
     * @param budget the shared node budget
     * @return the accepted level-0 nodes plus the paging state
     * @throws QueryException if a hierarchy query fails
     */
    private Level0Result fetchLevel0(RootScope scope, TreeRequest req, Budget budget) throws QueryException
    {
        // The root's direct children are paged with offset/limit in Java, so fetch offset+limit+1 (bounded) to
        // both fill the page and learn whether more remain, without materializing the whole wide level.
        // The window is fetched before authorization, so denied rows consume it: accessible children beyond
        // the fetch are then dropped without a ⋯ or offset hint. Under-reporting is the same accepted accuracy
        // tradeoff as the bounded boundary probe in markBoundary - the fetch stays bounded either way.
        int level0Fetch = Math.min(req.offset() + req.limit() + 1, MAX_FETCH_PER_QUERY);
        TreeNode syntheticRoot = makeNode(scope.rootDoc(), null, true);
        List<TreeNode> all =
            fetchChildren(List.of(syntheticRoot), scope.targetWiki(), req.showHidden(), level0Fetch)
                .getOrDefault(syntheticRoot.spaceKey(), new ArrayList<>());

        int offset = req.offset();
        if (offset >= all.size()) {
            return new Level0Result(new ArrayList<>(), false, offset);
        }
        int end = Math.min(offset + req.limit(), all.size());
        boolean rootHasMore = end < all.size();

        List<TreeNode> accepted = new ArrayList<>();
        for (TreeNode node : all.subList(offset, end)) {
            if (budget.count() >= MAX_NODES) {
                budget.markTruncated();
                break;
            }
            budget.increment();
            accepted.add(node);
        }
        return new Level0Result(accepted, rootHasMore, offset + accepted.size());
    }

    /**
     * Expands one level: fetches every frontier node's children, buckets them, applies the per-node breadth cap
     * and the node budget, and returns the next frontier (the {@code WebHome} children). If the budget is
     * exhausted mid-level, the remaining branches are marked as unexpanded and descent stops.
     *
     * @param parents the current frontier
     * @param req the parsed request
     * @param wiki the target wiki
     * @param budget the shared node budget
     * @return the next frontier, or an empty list when the budget stopped descent
     * @throws QueryException if a hierarchy query fails
     */
    private List<TreeNode> expandLevel(List<TreeNode> parents, TreeRequest req, String wiki, Budget budget)
        throws QueryException
    {
        int fetchLimit = descentFetchLimit(parents.size(), req.limit());
        Map<String, List<TreeNode>> childrenByParent = fetchChildren(parents, wiki, req.showHidden(), fetchLimit);
        List<TreeNode> next = new ArrayList<>();
        for (int i = 0; i < parents.size(); i++) {
            TreeNode parent = parents.get(i);
            List<TreeNode> bucket = childrenByParent.get(parent.spaceKey());
            if (CollectionUtils.isEmpty(bucket)) {
                continue;
            }
            if (!attachChildren(parent, bucket, req.limit(), next, budget)) {
                markRemainingBranches(parents, i, childrenByParent, budget);
                return new ArrayList<>();
            }
        }
        return next;
    }

    /**
     * Attaches a node's children under it, honoring the per-node breadth cap and the node budget.
     *
     * @param parent the parent node
     * @param bucket the parent's surviving children, in deterministic order
     * @param limit the per-node breadth cap
     * @param next the next-frontier accumulator, appended with the {@code WebHome} children accepted
     * @param budget the shared node budget
     * @return {@code true} if all capped children were accepted, {@code false} if the budget stopped mid-way
     */
    private boolean attachChildren(TreeNode parent, List<TreeNode> bucket, int limit, List<TreeNode> next,
        Budget budget)
    {
        List<TreeNode> capped = bucket;
        if (bucket.size() > limit) {
            capped = bucket.subList(0, limit);
            parent.setHasMore(true);
        }
        for (TreeNode child : capped) {
            if (budget.count() >= MAX_NODES) {
                budget.markTruncated();
                parent.setHasMore(true);
                budget.addTruncatedBranch();
                return false;
            }
            parent.addChild(child);
            budget.increment();
            if (child.webHome()) {
                next.add(child);
            }
        }
        return true;
    }

    /**
     * Marks the frontier branches past the truncation point that still had children as unexpanded, and counts
     * them for the truncation footer.
     *
     * @param parents the current frontier
     * @param truncatedIndex the index of the parent at which the budget was exhausted (already counted)
     * @param childrenByParent the bucketed children of the frontier
     * @param budget the shared node budget
     */
    private void markRemainingBranches(List<TreeNode> parents, int truncatedIndex,
        Map<String, List<TreeNode>> childrenByParent, Budget budget)
    {
        for (int j = truncatedIndex + 1; j < parents.size(); j++) {
            TreeNode parent = parents.get(j);
            if (!CollectionUtils.isEmpty(childrenByParent.get(parent.spaceKey()))) {
                parent.setHasMore(true);
                budget.addTruncatedBranch();
            }
        }
    }

    /**
     * Runs the boundary (depth+1) fetch for the last rendered level: it does not render the children, it only
     * marks each frontier node that has at least one surviving child, so the node shows the more-children
     * ellipsis.
     *
     * @param parents the last rendered frontier
     * @param req the parsed request
     * @param wiki the target wiki
     * @throws QueryException if a hierarchy query fails
     */
    private void markBoundary(List<TreeNode> parents, TreeRequest req, String wiki) throws QueryException
    {
        // Bounded existence probe: under adversarial fan-out (parents * (limit+1) beyond the ceiling) some
        // parents past the ceiling may miss their ⋯ signal. That under-reporting is an accepted accuracy
        // tradeoff, not a correctness or security issue - the fetch stays bounded either way.
        int fetchLimit = descentFetchLimit(parents.size(), req.limit());
        Map<String, List<TreeNode>> childrenByParent = fetchChildren(parents, wiki, req.showHidden(), fetchLimit);
        for (TreeNode parent : parents) {
            if (!CollectionUtils.isEmpty(childrenByParent.get(parent.spaceKey()))) {
                parent.setHasMore(true);
            }
        }
    }

    /**
     * Fetches and authorizes the children of the given frontier nodes, batched into one child-spaces query and
     * one terminal-docs query, bucketed by parent space key. Child spaces precede terminal pages within each
     * bucket, each group kept in the query's {@code doc.fullName} order.
     *
     * @param parents the frontier nodes
     * @param wiki the target wiki
     * @param showHidden whether hidden pages are included
     * @param fetchLimit the bounded database row ceiling for each query
     * @return the surviving children bucketed by parent space key
     * @throws QueryException if a hierarchy query fails
     */
    private Map<String, List<TreeNode>> fetchChildren(List<TreeNode> parents, String wiki, boolean showHidden,
        int fetchLimit) throws QueryException
    {
        List<String> parentKeys = parents.stream().map(TreeNode::spaceKey).distinct().toList();
        Map<String, List<TreeNode>> byParent = new HashMap<>();
        if (parentKeys.isEmpty()) {
            return byParent;
        }
        WikiReference wikiRef = new WikiReference(wiki);
        bucketRows(byParent,
            this.rowQuery.hierarchyRows(CHILD_SPACES_QUERY_BASE, showHidden, true, wiki, PARENTS_BIND,
                parentKeys, fetchLimit), true, wikiRef);
        bucketRows(byParent,
            this.rowQuery.hierarchyRows(TERMINAL_DOCS_QUERY_BASE, showHidden, false, wiki, PARENTS_BIND,
                parentKeys, fetchLimit), false, wikiRef);
        return byParent;
    }

    /**
     * Resolves each child row into the target wiki, authorizes it (both through the door) and buckets the
     * survivors by their parent space key.
     *
     * @param byParent the bucket map to populate
     * @param rows the raw result rows ({@code {fullName, parentSpaceKey, title}})
     * @param webHome whether these rows are space homes (which can have children) or terminal pages
     * @param targetWiki the wiki the rows belong to, which unqualified full names resolve into
     */
    private void bucketRows(Map<String, List<TreeNode>> byParent, List<Object[]> rows, boolean webHome,
        WikiReference targetWiki)
    {
        for (Object[] columns : rows) {
            DocumentReference ref = this.rowQuery.authorizedDocument((String) columns[0], targetWiki);
            if (ref == null) {
                continue;
            }
            String parentKey = (String) columns[1];
            String title = (String) columns[2];
            byParent.computeIfAbsent(parentKey, key -> new ArrayList<>()).add(makeNode(ref, title, webHome));
        }
    }

    private TreeNode makeNode(DocumentReference ref, String title, boolean webHome)
    {
        String name = webHome ? ref.getLastSpaceReference().getName() : ref.getName();
        String spaceKey = webHome ? this.localSerializer.serialize(ref.getLastSpaceReference()) : null;
        return new TreeNode(ref, name, webHome, title, spaceKey);
    }

    private static List<TreeNode> webHomes(List<TreeNode> nodes)
    {
        List<TreeNode> result = new ArrayList<>();
        for (TreeNode node : nodes) {
            if (node.webHome()) {
                result.add(node);
            }
        }
        return result;
    }

    private void collectLocalNames(List<TreeNode> nodes, List<String> accumulator)
    {
        for (TreeNode node : nodes) {
            accumulator.add(this.localSerializer.serialize(node.reference()));
            collectLocalNames(node.children(), accumulator);
        }
    }

    private Map<String, List<String>> fetchObjectClasses(String wiki, List<String> names) throws QueryException
    {
        Map<String, List<String>> markers = new HashMap<>();
        if (names.isEmpty()) {
            return markers;
        }
        for (Object[] columns : this.rowQuery.rows(OBJECT_CLASSES_QUERY, wiki, NAMES_BIND, names,
            MAX_FETCH_PER_QUERY)) {
            String className = (String) columns[1];
            if (!NOISE_CLASSES.contains(className)) {
                markers.computeIfAbsent((String) columns[0], key -> new ArrayList<>()).add(className);
            }
        }
        return markers;
    }

    private Map<String, Integer> fetchAttachments(String wiki, List<String> names) throws QueryException
    {
        Map<String, Integer> markers = new HashMap<>();
        if (names.isEmpty()) {
            return markers;
        }
        for (Object[] columns : this.rowQuery.rows(ATTACHMENTS_QUERY, wiki, NAMES_BIND, names,
            MAX_FETCH_PER_QUERY)) {
            markers.put((String) columns[0], ((Long) columns[1]).intValue());
        }
        return markers;
    }

    private String renderTree(RootScope scope, TreeRequest req, Level0Result level0, Budget budget,
        Map<String, List<String>> classMarkers, Map<String, Integer> attachmentMarkers)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(headerBlock(scope, req, budget, level0.rootHasMore(), level0.nextOffset()));
        sb.append(DOUBLE_NEW_LINE);

        if (level0.roots().isEmpty()) {
            sb.append(NO_CHILDREN);
        } else {
            for (TreeNode root : level0.roots()) {
                renderNode(sb, root, 0, scope.sameWiki(), classMarkers, attachmentMarkers);
            }
        }

        String body = sb.toString().stripTrailing();
        if (budget.isTruncated()) {
            body += NEW_LINE + TRUNCATION_FOOTER_HEAD + budget.truncatedBranches() + TRUNCATION_FOOTER_TAIL;
        }
        return body;
    }

    /**
     * Builds the two-line explore header block, plus an optional third line when the root has more direct
     * children than this page showed.
     *
     * @param scope the resolved root scope
     * @param req the parsed request
     * @param budget the node budget, for the shown count and truncated-branch count
     * @param rootHasMore whether more of the root's direct children remain beyond this page
     * @param nextOffset the offset to page the next block of the root's direct children
     * @return the header block
     */
    private String headerBlock(RootScope scope, TreeRequest req, Budget budget, boolean rootHasMore,
        int nextOffset)
    {
        StringBuilder header = new StringBuilder();
        header.append(TREE_PREFIX).append(scope.display()).append(OPEN_PAREN).append(PAGE_KIND)
            .append(PAREN_DEPTH).append(req.depth()).append(DASH_SEP).append(budget.count())
            .append(PAGES_SHOWN);
        if (budget.truncatedBranches() > 0) {
            header.append(LIST_SEPARATOR).append(budget.truncatedBranches()).append(BRANCHES_TRUNCATED);
        }
        if (req.showHidden()) {
            header.append(HIDDEN_INCLUDED);
        }
        header.append(NEW_LINE).append(LEGEND);
        if (rootHasMore) {
            header.append(NEW_LINE).append(ROOT_MORE_NOTE).append(nextOffset).append(PERIOD);
        }
        return header.toString();
    }

    private void renderNode(StringBuilder sb, TreeNode node, int depth, boolean sameWiki,
        Map<String, List<String>> classMarkers, Map<String, Integer> attachmentMarkers)
    {
        sb.append(INDENT.repeat(depth)).append(nodeLine(node, sameWiki, classMarkers, attachmentMarkers))
            .append(NEW_LINE);
        for (TreeNode child : node.children()) {
            renderNode(sb, child, depth + 1, sameWiki, classMarkers, attachmentMarkers);
        }
    }

    /**
     * Composes one node line (without indentation): name, {@code /} for a space home, the reference, the
     * quoted title (omitted when it adds nothing over the name), the more-children signal and the marker.
     *
     * @param node the node to render
     * @param sameWiki whether the target wiki is the endpoint's own (context) wiki, for local references
     * @param classMarkers the object-class markers, keyed by local full name
     * @param attachmentMarkers the attachment-count markers, keyed by local full name
     * @return the composed line
     */
    private String nodeLine(TreeNode node, boolean sameWiki, Map<String, List<String>> classMarkers,
        Map<String, Integer> attachmentMarkers)
    {
        StringBuilder line = new StringBuilder(sanitizeForLine(node.name()));
        if (node.webHome()) {
            line.append(SLASH);
        }
        line.append(REF_OPEN).append(renderRef(node.reference(), sameWiki)).append(REF_CLOSE);
        line.append(titleSegment(node.title(), node.name()));
        if (node.hasMore()) {
            line.append(ELLIPSIS_SUFFIX);
        }
        line.append(markerSuffix(node, classMarkers, attachmentMarkers));
        return line.toString();
    }

    /**
     * Renders a node reference for a line: local (unqualified) when the target wiki is the endpoint's own
     * wiki, so it resolves back through {@code get_document} as-is; wiki-prefixed otherwise, which
     * {@code get_document} accepts cross-wiki. Always neutralized for the line grammar.
     *
     * @param ref the reference to render
     * @param sameWiki whether the target wiki is the endpoint's own (context) wiki
     * @return the rendered reference
     */
    private String renderRef(DocumentReference ref, boolean sameWiki)
    {
        String serialized =
            sameWiki ? this.localSerializer.serialize(ref) : this.serializer.serialize(ref);
        return sanitizeReference(serialized);
    }

    /**
     * Composes the quoted-title segment of a line, or an empty string when the title adds nothing: a blank
     * title and a raw-Velocity title (starting with {@code $} or {@code #}, noise for an agent) fall back to
     * the node name, and a title equal to the name is omitted entirely.
     *
     * @param rawTitle the raw title, possibly {@code null}
     * @param name the node name the title is compared against
     * @return the quoted-title segment, or an empty string
     */
    private static String titleSegment(String rawTitle, String name)
    {
        String title = effectiveTitle(rawTitle, name);
        if (title.equals(name)) {
            return "";
        }
        return TITLE_OPEN + sanitizeForLine(title) + QUOTE;
    }

    private static String effectiveTitle(String rawTitle, String name)
    {
        if (StringUtils.isBlank(rawTitle)) {
            return name;
        }
        String trimmed = rawTitle.trim();
        if (trimmed.startsWith("$") || trimmed.startsWith("#")) {
            return name;
        }
        return trimmed;
    }

    /**
     * Neutralizes a page-text fragment (a title, page name or object-class name) for the tree line grammar, so
     * untrusted wiki content cannot forge a row, a second reference or a fake marker: control characters and
     * whitespace runs collapse to a single space, the framing characters are stripped ({@code []{}} and the
     * {@code ⋯} signal) or replaced ({@code "} becomes {@code '}), and the result is length-capped.
     *
     * @param value the raw page-text fragment, possibly {@code null}
     * @return the neutralized, length-capped fragment
     */
    private static String sanitizeForLine(String value)
    {
        if (StringUtils.isEmpty(value)) {
            return "";
        }
        String collapsed = CONTROL_OR_SPACE_RUN.matcher(value).replaceAll(" ").trim();
        String neutralized = FRAMING_CHARS.matcher(collapsed.replace('"', '\'')).replaceAll("");
        if (neutralized.length() > MAX_TITLE_CHARS) {
            neutralized = neutralized.substring(0, MAX_TITLE_CHARS) + TITLE_ELLIPSIS;
        }
        return neutralized;
    }

    /**
     * Neutralizes a serialized reference for a single line: it strips only the newline/control family, so an
     * entity name containing a newline cannot forge extra rows or banners. The reference-syntax framing
     * punctuation ({@code []{}"}) is deliberately kept so the value stays a valid, resolvable
     * {@code get_document} argument; a name carrying such characters can still mangle its own one line - up to
     * imitating an adjacent metadata segment on it, such as the survey counts or age chip - which is the
     * accepted tradeoff for keeping refs resolvable, a far smaller risk than row forgery.
     *
     * @param reference the serialized reference
     * @return the reference with newline/control-family characters removed
     */
    private static String sanitizeReference(String reference)
    {
        return MCPToolSupport.stripLineBreaks(reference);
    }

    private String markerSuffix(TreeNode node, Map<String, List<String>> classMarkers,
        Map<String, Integer> attachmentMarkers)
    {
        String local = this.localSerializer.serialize(node.reference());
        List<String> classes = classMarkers.getOrDefault(local, List.of());
        int attachments = attachmentMarkers.getOrDefault(local, 0);
        if (classes.isEmpty() && attachments == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String className : classes) {
            parts.add(sanitizeForLine(className));
        }
        if (attachments > 0) {
            parts.add(ATT_PREFIX + attachments);
        }
        return composeMarker(parts);
    }

    /**
     * Composes the {@code {...}} marker, truncating at an item boundary: parts are joined while the composed
     * marker (with room reserved for the {@code , …}} close) stays within {@link #MAX_MARKER_CHARS}; a part
     * that does not fit is never cut - the list ends with {@code …} instead. When not even the first part
     * fits, the marker is just {@code {…}}.
     *
     * @param parts the sanitized marker parts, in render order
     * @return the composed, boundary-truncated marker
     */
    private static String composeMarker(List<String> parts)
    {
        String full = OPEN_BRACE + String.join(LIST_SEPARATOR, parts) + CLOSE_BRACE;
        if (full.length() <= MAX_MARKER_CHARS) {
            return full;
        }
        String close = LIST_SEPARATOR + TITLE_ELLIPSIS + CLOSE_BRACE;
        StringBuilder marker = new StringBuilder(OPEN_BRACE);
        boolean first = true;
        for (String part : parts) {
            int separatorLength = first ? 0 : LIST_SEPARATOR.length();
            if (marker.length() + separatorLength + part.length() + close.length() > MAX_MARKER_CHARS) {
                break;
            }
            if (!first) {
                marker.append(LIST_SEPARATOR);
            }
            marker.append(part);
            first = false;
        }
        if (first) {
            return OPEN_BRACE + TITLE_ELLIPSIS + CLOSE_BRACE;
        }
        return marker.append(close).toString();
    }

    /**
     * Builds and renders the whole-wiki survey: one bounded query over every document of the target wiki,
     * aggregated per top-level space, with small spaces expanded inline.
     *
     * @param scope the resolved root scope (a wiki root)
     * @param req the parsed request
     * @return the rendered survey text
     * @throws QueryException if the survey or a marker query fails
     */
    private String buildAndRenderSurvey(RootScope scope, TreeRequest req) throws QueryException
    {
        List<Object[]> rows =
            this.rowQuery.rows(SURVEY_QUERY, scope.targetWiki(), null, null, MAX_FETCH_PER_QUERY);
        boolean capped = rows.size() >= MAX_FETCH_PER_QUERY;
        SurveyData data = aggregateSurvey(rows, scope.targetWiki(), req.showHidden());
        return renderSurvey(scope, req, data, capped);
    }

    /**
     * Aggregates the survey rows per top-level space: resolves each row into the target wiki, drops denied
     * rows (remembering their space, which then renders summary-only), splits hidden from visible counts,
     * tracks the last activity of the visible rows and retains the rows of small spaces for inline expansion.
     *
     * @param rows the raw survey rows ({@code {fullName, title, date, hidden}})
     * @param targetWiki the surveyed wiki id
     * @param showHidden whether hidden pages count as ordinary pages (one folded count)
     * @return the aggregated survey data, in the query's space-name order
     */
    private SurveyData aggregateSurvey(List<Object[]> rows, String targetWiki, boolean showHidden)
    {
        Map<String, SpaceSummary> spaces = new LinkedHashMap<>();
        Set<String> deniedSpaces = new HashSet<>();
        WikiReference wikiRef = new WikiReference(targetWiki);
        for (Object[] columns : rows) {
            DocumentReference ref = this.rowQuery.resolveInto((String) columns[0], wikiRef);
            String topSpace = ref.getSpaceReferences().get(0).getName();
            if (!this.rowQuery.isAuthorized(ref)) {
                deniedSpaces.add(topSpace);
                continue;
            }
            boolean hidden = Boolean.TRUE.equals(columns[3]) && !showHidden;
            SpaceSummary summary = spaces.computeIfAbsent(topSpace,
                key -> new SpaceSummary(ref.getSpaceReferences().get(0)));
            accumulateRow(summary, ref, (String) columns[1], (Date) columns[2], hidden);
        }
        return new SurveyData(spaces, deniedSpaces);
    }

    /**
     * Folds one authorized survey row into its space summary. The space's own home row contributes the space
     * title and is never retained for inlining (the summary line already is it); a hidden row only counts.
     *
     * @param summary the space summary to update
     * @param ref the resolved row reference
     * @param title the row title
     * @param date the row's last-modification date
     * @param hidden whether the row counts as hidden (already folded when hidden pages are included)
     */
    private void accumulateRow(SpaceSummary summary, DocumentReference ref, String title, Date date,
        boolean hidden)
    {
        boolean topHome = WEBHOME.equals(ref.getName()) && ref.getSpaceReferences().size() == 1;
        if (topHome && !hidden) {
            summary.setTitle(title);
        }
        if (hidden) {
            summary.countHidden();
            return;
        }
        summary.countVisible(date);
        if (!topHome) {
            summary.retain(makeInlineNode(ref, title));
        }
    }

    /**
     * Builds an inline node for a survey row, named by its space-relative dotted path from the top-level space
     * ({@code AI.Models.Default} renders as {@code Models.Default}, a nested space home
     * {@code Attachment.Picker.Code.WebHome} as {@code Picker.Code/}), so a flat inline list stays readable
     * even when an intermediate space home is itself excluded.
     *
     * @param ref the resolved row reference
     * @param title the row title
     * @return the inline node
     */
    private TreeNode makeInlineNode(DocumentReference ref, String title)
    {
        return new TreeNode(ref, relativeName(ref), WEBHOME.equals(ref.getName()), title, null);
    }

    /**
     * Composes the space-relative dotted name of an inline node: the space chain below the top-level space,
     * then the page name unless it is {@code WebHome} (a space home is named by its relative space path; the
     * trailing {@code /} comes from the node line grammar).
     *
     * @param ref the resolved row reference
     * @return the relative dotted name
     */
    private static String relativeName(DocumentReference ref)
    {
        List<SpaceReference> spaces = ref.getSpaceReferences();
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < spaces.size(); i++) {
            parts.add(spaces.get(i).getName());
        }
        if (!WEBHOME.equals(ref.getName())) {
            parts.add(ref.getName());
        }
        return String.join(PERIOD, parts);
    }

    /**
     * Renders the survey: header, then one line per top-level space in the paged window, with inline
     * expansion of small spaces within the inline budget.
     *
     * @param scope the resolved root scope
     * @param req the parsed request, whose limit/offset page the space list
     * @param data the aggregated survey data
     * @param capped whether the survey fetch hit the row ceiling, so counts are sampled
     * @return the rendered survey text
     * @throws QueryException if a marker query fails
     */
    private String renderSurvey(RootScope scope, TreeRequest req, SurveyData data, boolean capped)
        throws QueryException
    {
        List<SpaceSummary> all = new ArrayList<>(data.spaces().values());
        int totalPages = all.stream().mapToInt(SpaceSummary::visibleCount).sum();
        int from = Math.min(req.offset(), all.size());
        int to = Math.min(from + req.limit(), all.size());
        List<SpaceSummary> window = all.subList(from, to);
        boolean hasMore = to < all.size();

        Map<String, List<TreeNode>> inlineBySpace = planInline(window, data.deniedSpaces(), capped);
        List<String> inlinedNames = new ArrayList<>();
        for (List<TreeNode> nodes : inlineBySpace.values()) {
            for (TreeNode node : nodes) {
                inlinedNames.add(this.localSerializer.serialize(node.reference()));
            }
        }
        Map<String, List<String>> classMarkers = fetchObjectClasses(scope.targetWiki(), inlinedNames);
        Map<String, Integer> attachmentMarkers = fetchAttachments(scope.targetWiki(), inlinedNames);

        StringBuilder sb = new StringBuilder();
        sb.append(surveyHeader(scope, req, all.size(), totalPages, capped, hasMore, to));
        sb.append(DOUBLE_NEW_LINE);
        if (window.isEmpty()) {
            sb.append(NO_SPACES);
        } else {
            for (SpaceSummary summary : window) {
                renderSurveySpace(sb, summary, scope.sameWiki(), capped, inlineBySpace.get(summary.name()),
                    classMarkers, attachmentMarkers);
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Decides which spaces of the paged window are expanded inline: a space whose retained row list survived
     * the per-space threshold, none of whose rows were denied, and whose lines still fit the global inline
     * budget. A capped (sampled) survey expands nothing inline - with rows missing, no subtree may be
     * presented as complete.
     *
     * @param window the paged space summaries
     * @param deniedSpaces the top-level spaces that had at least one denied row
     * @param capped whether the survey fetch hit the row ceiling
     * @return the inline nodes keyed by top-level space name
     */
    private Map<String, List<TreeNode>> planInline(List<SpaceSummary> window, Set<String> deniedSpaces,
        boolean capped)
    {
        Map<String, List<TreeNode>> inlineBySpace = new HashMap<>();
        if (capped) {
            return inlineBySpace;
        }
        int budget = INLINE_BUDGET;
        for (SpaceSummary summary : window) {
            List<TreeNode> nodes = summary.inlineRows();
            if (CollectionUtils.isEmpty(nodes) || deniedSpaces.contains(summary.name())
                || nodes.size() > budget) {
                continue;
            }
            inlineBySpace.put(summary.name(), nodes);
            budget -= nodes.size();
        }
        return inlineBySpace;
    }

    /**
     * Builds the survey header block: the survey banner with space/page totals, the survey legend, and the
     * paging hint when more spaces remain.
     *
     * @param scope the resolved root scope
     * @param req the parsed request
     * @param spaceCount the total number of surveyed top-level spaces
     * @param pageCount the total number of visible pages across them
     * @param capped whether the survey fetch hit the row ceiling
     * @param hasMore whether more spaces remain beyond this page
     * @param nextOffset the offset to page the next block of spaces
     * @return the header block
     */
    private String surveyHeader(RootScope scope, TreeRequest req, int spaceCount, int pageCount, boolean capped,
        boolean hasMore, int nextOffset)
    {
        StringBuilder header = new StringBuilder();
        header.append(TREE_PREFIX).append(scope.display()).append(OPEN_PAREN).append(WIKI_SURVEY_KIND)
            .append(CLOSE_PAREN).append(DASH_SEP).append(spaceCount).append(SPACES_SUFFIX)
            .append(LIST_SEPARATOR);
        appendCount(header, pageCount, capped);
        header.append(PAGES_SUFFIX);
        if (req.showHidden()) {
            header.append(HIDDEN_INCLUDED);
        }
        if (capped) {
            header.append(SAMPLED_NOTE);
        }
        header.append(NEW_LINE).append(SURVEY_LEGEND);
        if (hasMore) {
            header.append(NEW_LINE).append(SURVEY_MORE_NOTE).append(nextOffset).append(PERIOD);
        }
        return header.toString();
    }

    /**
     * Renders one surveyed space: its summary line, then its inline lines when it was selected for expansion -
     * a flat list at one indent level, each line named by its space-relative dotted path (see
     * {@link #makeInlineNode}), so the block reads correctly whatever the rows' nesting.
     *
     * @param sb the output buffer
     * @param summary the space summary
     * @param sameWiki whether the target wiki is the endpoint's own wiki
     * @param capped whether counts are sampled
     * @param inline the selected inline nodes, or {@code null} for summary-only
     * @param classMarkers the object-class markers of the inlined nodes
     * @param attachmentMarkers the attachment-count markers of the inlined nodes
     */
    private void renderSurveySpace(StringBuilder sb, SpaceSummary summary, boolean sameWiki, boolean capped,
        List<TreeNode> inline, Map<String, List<String>> classMarkers, Map<String, Integer> attachmentMarkers)
    {
        sb.append(surveyLine(summary, sameWiki, capped)).append(NEW_LINE);
        if (inline == null) {
            return;
        }
        for (TreeNode node : inline) {
            sb.append(INDENT).append(nodeLine(node, sameWiki, classMarkers, attachmentMarkers))
                .append(NEW_LINE);
        }
    }

    /**
     * Composes one survey summary line: space name, its {@code WebHome} reference (the explore-root argument,
     * whether or not that document exists), the space title when it adds something, then the counts and the
     * compact age of the last visible activity.
     *
     * @param summary the space summary
     * @param sameWiki whether the target wiki is the endpoint's own wiki
     * @param capped whether counts are sampled (rendered with a trailing {@code +})
     * @return the composed line
     */
    private String surveyLine(SpaceSummary summary, boolean sameWiki, boolean capped)
    {
        DocumentReference homeRef = new DocumentReference(WEBHOME, summary.topSpace());
        StringBuilder line = new StringBuilder(sanitizeForLine(summary.name()));
        line.append(SLASH).append(REF_OPEN).append(renderRef(homeRef, sameWiki)).append(REF_CLOSE);
        line.append(titleSegment(summary.title(), summary.name()));
        line.append(INDENT).append(SURVEY_DASH);
        appendCount(line, summary.visibleCount(), capped);
        line.append(summary.visibleCount() == 1 && !capped ? PAGE_SUFFIX : PAGES_SUFFIX);
        if (summary.hiddenCount() > 0) {
            line.append(HIDDEN_PLUS);
            appendCount(line, summary.hiddenCount(), capped);
            line.append(HIDDEN_SUFFIX);
        }
        if (summary.lastActive() != null) {
            line.append(LIST_SEPARATOR).append(age(summary.lastActive(), new Date()));
        }
        return line.toString();
    }

    private static void appendCount(StringBuilder sb, int count, boolean capped)
    {
        sb.append(count);
        if (capped) {
            sb.append(PLUS);
        }
    }

    /**
     * Formats the interval between two instants as a compact age chip. Pure function of its two arguments so
     * tests pass fixed instants; a negative interval reads as zero.
     *
     * @param then the earlier instant
     * @param now the current instant
     * @return the compact age, e.g. {@code just now}, {@code 5m ago}, {@code 3d ago}, {@code 2y ago}
     */
    static String age(Date then, Date now)
    {
        long seconds = Math.max(now.getTime() - then.getTime(), 0) / MILLIS_PER_SECOND;
        long minutes = seconds / SECONDS_PER_MINUTE;
        long hours = minutes / MINUTES_PER_HOUR;
        long days = hours / HOURS_PER_DAY;
        String formatted;
        if (seconds < SECONDS_PER_MINUTE) {
            formatted = JUST_NOW;
        } else if (minutes < MINUTES_PER_HOUR) {
            formatted = minutes + "m" + AGO;
        } else if (hours < HOURS_PER_DAY) {
            formatted = hours + "h" + AGO;
        } else if (days < DAYS_PER_MONTH) {
            formatted = days + "d" + AGO;
        } else if (days < DAYS_PER_YEAR) {
            formatted = Math.min(days / DAYS_PER_MONTH, MAX_MONTHS) + "mo" + AGO;
        } else {
            formatted = (days / DAYS_PER_YEAR) + "y" + AGO;
        }
        return formatted;
    }

    /**
     * The parsed and validated arguments of a tree call.
     *
     * @param root the raw root argument, or {@code null}/blank for a whole-wiki survey
     * @param wiki the raw wiki argument, or {@code null}/blank for the current wiki
     * @param depth the render depth, clamped to the allowed range
     * @param limit the per-node breadth cap (explore) or space page size (survey), clamped to the allowed range
     * @param offset the number of root children (explore) or spaces (survey) to skip, never negative
     * @param showHidden whether hidden pages are included
     * @version $Id$
     */
    private record TreeRequest(String root, String wiki, int depth, int limit, int offset, boolean showHidden)
    {
    }

    /**
     * The resolved root of a call: its target wiki and, for a page root, the resolved reference.
     *
     * @param targetWiki the single wiki this call operates within
     * @param rootDoc the resolved page root, or {@code null} for a wiki root (survey)
     * @param wikiRoot whether this is a whole-wiki root (survey) rather than a page root (explore)
     * @param display the root label shown in the header (the wiki id, or the rendered page reference)
     * @param terminal whether a page root is a terminal page that cannot have children
     * @param sameWiki whether the target wiki is the endpoint's own (context) wiki, for local references
     * @version $Id$
     */
    private record RootScope(String targetWiki, DocumentReference rootDoc, boolean wikiRoot, String display,
        boolean terminal, boolean sameWiki)
    {
    }

    /**
     * The level-0 (root's direct children) fetch result: the accepted nodes and the root-paging state.
     *
     * @param roots the accepted level-0 nodes
     * @param rootHasMore whether more of the root's direct children remain beyond this page
     * @param nextOffset the offset to page the next block of the root's direct children
     * @version $Id$
     */
    private record Level0Result(List<TreeNode> roots, boolean rootHasMore, int nextOffset)
    {
    }

    /**
     * The aggregated survey of one wiki: the per-space summaries in render order, and the top-level spaces
     * that had at least one denied row (never expanded inline, so a partial listing is not presented as
     * complete).
     *
     * @param spaces the per-space summaries, keyed by top-level space name, in render order
     * @param deniedSpaces the top-level space names that had at least one denied row
     * @version $Id$
     */
    private record SurveyData(Map<String, SpaceSummary> spaces, Set<String> deniedSpaces)
    {
    }

    /**
     * One rendered node of the tree.
     *
     * @version $Id$
     */
    private static final class TreeNode
    {
        private final DocumentReference reference;

        private final String name;

        private final boolean webHome;

        private final String title;

        private final String spaceKey;

        private final List<TreeNode> children = new ArrayList<>();

        private boolean hasMore;

        TreeNode(DocumentReference reference, String name, boolean webHome, String title, String spaceKey)
        {
            this.reference = reference;
            this.name = name;
            this.webHome = webHome;
            this.title = title;
            this.spaceKey = spaceKey;
        }

        DocumentReference reference()
        {
            return this.reference;
        }

        String name()
        {
            return this.name;
        }

        boolean webHome()
        {
            return this.webHome;
        }

        String title()
        {
            return this.title;
        }

        String spaceKey()
        {
            return this.spaceKey;
        }

        List<TreeNode> children()
        {
            return this.children;
        }

        boolean hasMore()
        {
            return this.hasMore;
        }

        void setHasMore(boolean hasMore)
        {
            this.hasMore = hasMore;
        }

        void addChild(TreeNode child)
        {
            this.children.add(child);
        }
    }

    /**
     * The running aggregate of one surveyed top-level space: its counts, last visible activity, title and the
     * retained rows of a small space, kept only while the space stays small so memory stays bounded.
     *
     * @version $Id$
     */
    private static final class SpaceSummary
    {
        private final SpaceReference topSpace;

        private int visibleCount;

        private int hiddenCount;

        private Date lastActive;

        private String title;

        private List<TreeNode> inlineRows = new ArrayList<>();

        SpaceSummary(SpaceReference topSpace)
        {
            this.topSpace = topSpace;
        }

        SpaceReference topSpace()
        {
            return this.topSpace;
        }

        String name()
        {
            return this.topSpace.getName();
        }

        int visibleCount()
        {
            return this.visibleCount;
        }

        int hiddenCount()
        {
            return this.hiddenCount;
        }

        Date lastActive()
        {
            return this.lastActive;
        }

        String title()
        {
            return this.title;
        }

        List<TreeNode> inlineRows()
        {
            return this.inlineRows;
        }

        void setTitle(String title)
        {
            this.title = title;
        }

        void countVisible(Date date)
        {
            this.visibleCount++;
            if (date != null && (this.lastActive == null || date.getTime() > this.lastActive.getTime())) {
                this.lastActive = date;
            }
        }

        void countHidden()
        {
            this.hiddenCount++;
        }

        /**
         * Retains a row for inline expansion, dropping the whole list the moment the space grows past
         * {@link #INLINE_THRESHOLD} so a large space never accumulates rows.
         *
         * @param node the row's rendered node
         */
        void retain(TreeNode node)
        {
            if (this.inlineRows == null) {
                return;
            }
            this.inlineRows.add(node);
            if (this.inlineRows.size() > INLINE_THRESHOLD) {
                this.inlineRows = null;
            }
        }
    }

    /**
     * The mutable run state shared across the explore descent: how many nodes have been rendered, whether the
     * node budget truncated the run, and how many branches were left unexpanded by that truncation.
     *
     * @version $Id$
     */
    private static final class Budget
    {
        private int count;

        private boolean truncated;

        private int truncatedBranches;

        int count()
        {
            return this.count;
        }

        void increment()
        {
            this.count++;
        }

        boolean isTruncated()
        {
            return this.truncated;
        }

        void markTruncated()
        {
            this.truncated = true;
        }

        int truncatedBranches()
        {
            return this.truncatedBranches;
        }

        void addTruncatedBranch()
        {
            this.truncatedBranches++;
        }
    }
}
