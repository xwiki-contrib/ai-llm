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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPReachAwareParams;
import org.xwiki.contrib.llm.mcp.MCPSourceText;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.link.LinkException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that reads the wiki's link graph around one document. {@code direction="in"} (the default)
 * lists the BACKLINKS: the pages whose static links point at the document, read from the platform's
 * Solr-backed link index. {@code direction="out"} lists the document's own outgoing links, extracted
 * live from its stored content and its objects' wiki-content fields (links buried in object fields do
 * not show as resolved references when reading the raw source). {@code direction="both"} renders both
 * sections.
 *
 * <p>The raw backlink index is farm-wide and unfiltered, so its answer never reaches the agent as-is:
 * {@link MCPLinksSupport#backlinks(DocumentReference)} reduces it to this endpoint's reach and to what
 * the current user may view, and the reported count covers ONLY that reduced list - the cardinality of
 * denied content is never disclosed. Outgoing targets, by contrast, are part of the source content the
 * caller is already authorized to view, so they are listed as authored, unfiltered and unprobed.</p>
 *
 * <p>Resolution and authorization of the input reference go through
 * {@link MCPDocumentAccess#resolveAndAuthorize(String, Right, WikiReference)} for {@link Right#VIEW}.
 * The target does not need to exist for a backlink read: listing the pages that link to a missing
 * document is the broken-link-repair use case, and rights are evaluable on nonexistent references.</p>
 *
 * @version $Id$
 * @since 0.10
 */
@Component
@Named(MCPGetLinksTool.TOOL_ID)
@Singleton
public class MCPGetLinksTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "get_links";

    private static final String REFERENCE_PARAM = "reference";

    private static final String WIKI_PARAM = "wiki";

    private static final String DIRECTION_PARAM = "direction";

    private static final String LOCALE_PARAM = "locale";

    private static final String LIMIT_PARAM = "limit";

    private static final String OFFSET_PARAM = "offset";

    private static final String SHOW_HIDDEN_PARAM = "showHidden";

    private static final String DIRECTION_IN = "in";

    private static final String DIRECTION_OUT = "out";

    private static final String DIRECTION_BOTH = "both";

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    /**
     * Marks a capped total as a floor ({@code N+}).
     */
    private static final String FLOOR_MARK = "+";

    /**
     * The count-line suffix of a hidden-including backlink list: it replaces the {@code (+N hidden)}
     * count, since the hidden rows are then listed (marked) in the list itself. The wording is
     * {@code get_tree}'s header suffix, referenced so the two tools cannot drift apart.
     */
    private static final String HIDDEN_INCLUDED = MCPGetTreeTool.HIDDEN_INCLUDED;

    /**
     * The backlink page size when {@code limit} is omitted.
     */
    private static final int DEFAULT_LIMIT = 50;

    /**
     * The largest accepted backlink page size.
     */
    private static final int MAX_LIMIT = 200;

    /**
     * Opens the header line of every response, followed by the canonical reference.
     */
    private static final String LINKS_OF_PREFIX = "Links of ";

    /**
     * The parenthesized direction tag of the header line, followed by the direction value.
     */
    private static final String DIRECTION_TAG = " (direction: ";

    /**
     * The header note of a backlink read whose target does not exist: backlinks to a missing page are
     * exactly the links to repair after a delete or a rename, so the read proceeds.
     */
    private static final String MISSING_TARGET_NOTE =
        "Document does not exist; listing pages that link to it.";

    /**
     * The out-section of a {@code direction="both"} call on a missing document: the in-section still
     * answers, and this line replaces the outgoing block instead of failing the whole call.
     */
    private static final String OUT_MISSING_NOTE =
        "Outgoing links: the document does not exist, so it has no content to read links from.";

    /**
     * The teaching note of an empty backlink list, so an agent understands the two innocuous reasons a
     * link it just wrote (or a rendered link it just saw) is absent.
     */
    private static final String EMPTY_BACKLINKS_NOTE = "No indexed backlinks. Note: links are indexed "
        + "asynchronously on save (a just-saved link may not appear yet), and only static links are "
        + "indexed - links generated by macros at render time are never in the index.";

    /**
     * The note appended when the raw backlink set exceeded the scan ceiling: references beyond it were
     * never scanned, so the authorized total is a floor (rendered as {@code N+}).
     */
    private static final String CEILING_NOTE = "The backlink scan hit the "
        + MCPRowQuery.MAX_FETCH_PER_QUERY + "-reference ceiling: backlinks beyond it are not counted.";

    /**
     * The agent-facing message of a failed link index read or backlink filter; the root cause stays in
     * the server logs.
     */
    private static final String LINK_INDEX_ERROR = "Could not read the link index. Try again; if it "
        + "persists, report it to a wiki administrator (details are in the server logs).";

    /**
     * The offset-assignment fragment of the paging texts, directly followed by the offset value.
     */
    private static final String OFFSET_EQUALS = OFFSET_PARAM + "=";

    /**
     * Error returned when {@code locale} is combined with a direction it does not apply to.
     */
    private static final String LOCALE_DIRECTION_ERROR = MCPToolSupport.ERROR_PREFIX + LOCALE_PARAM
        + "' only applies to direction=\"out\": the backlink list has one row per linking page, covering "
        + "all its translations. Drop it, or set direction=\"out\".";

    /**
     * Error returned when {@code limit} or {@code offset} is combined with {@code direction="out"},
     * whose outgoing list is bounded by the source document and always listed whole.
     */
    private static final String PAGING_DIRECTION_ERROR = MCPToolSupport.ERROR_PREFIX + LIMIT_PARAM
        + "'/'" + OFFSET_PARAM + "' only page the backlink list; drop them with direction=\"out\" "
        + "(outgoing links are always listed whole).";

    /**
     * Error returned when {@code showHidden} is combined with {@code direction="out"}: hidden
     * filtering only exists on the backlink side, outgoing links being listed as authored.
     */
    private static final String SHOW_HIDDEN_DIRECTION_ERROR = MCPToolSupport.ERROR_PREFIX
        + SHOW_HIDDEN_PARAM + "' only applies to the backlink list; drop it with direction=\"out\" "
        + "(outgoing links are listed as authored, with no hidden filtering).";

    /**
     * The man-page NOTES shown on every endpoint, without any cross-wiki mention.
     */
    private static final String MAN_NOTES_BASE = """
        NOTES
            direction="in" (the default) answers "which pages link HERE?": every page whose
            stored content or objects carries a static link to the document. The list and
            its count cover ONLY what you may view on this endpoint: other wikis appear
            only within the endpoint's reach, denied pages are dropped, and hidden pages
            are counted but not listed unless showHidden=true. The target itself does not
            have to exist - backlinks of a missing page are exactly the links to repair
            after a delete or a rename.

            direction="out" lists the document's own outgoing links, resolved live from its
            stored content plus its objects' wiki-content fields (links buried in object
            fields do not show as resolved references in the raw source). Targets are part
            of the source content you already read: they are listed as authored, without
            rights filtering, and their existence is not probed. locale reads a specific
            translation's own links (exact match, no fallback) and is only valid here: the
            backlink list already covers all translations of each linking page in its one
            row.

            Only STATIC links count, in both directions: links written in the page source
            or in object fields. Links a macro GENERATES at render time are never included;
            the reference PARAMETERS of include/display macros are. The backlink index is
            updated asynchronously on save, so a just-saved link may not appear yet;
            direction="out" reads live and has no lag.
        """;

    /**
     * The paging/ceiling NOTES paragraph, interpolating the enforced constants so the man page cannot
     * drift from the values the code applies.
     */
    private static final String MAN_NOTES_PAGING = "\n    limit/offset page the backlink list (default "
        + DEFAULT_LIMIT + " per page, max " + MAX_LIMIT + "); outgoing links are\n"
        + "    always listed whole. A very broad backlink set stops scanning at a "
        + MCPRowQuery.MAX_FETCH_PER_QUERY + "-reference\n"
        + "    ceiling: the count then reads \"N+\".\n";

    /**
     * The hidden-pages NOTES paragraph, teaching the same preference-independence the {@code get_tree}
     * man page teaches.
     */
    private static final String MAN_NOTES_HIDDEN = """

            Hidden backlinks are counted but not listed by default ("+N hidden"), regardless
            of your account's "display hidden documents" preference. Set showHidden=true to
            list them too, each marked (hidden); the count line then reads "hidden included".
        """;

    /**
     * The cross-wiki NOTES paragraph, appended only when the endpoint has cross-wiki reach - the man
     * page must not advertise a parameter the advertised schema does not carry.
     */
    private static final String MAN_NOTES_CROSS_WIKI = """

            The wiki parameter inspects a document of another wiki instead of the current
            one (one wiki per call; list_wikis shows what is reachable). Backlinks are
            collected farm-wide within this endpoint's reach either way.
        """;

    /**
     * The man-page EXAMPLES shown on every endpoint.
     */
    private static final String MAN_EXAMPLES_BASE = """

        EXAMPLES
            Who links here:     reference="Help.GettingStarted"
            Broken-link repair: reference="Deleted.Page"  (backlinks of a missing page)
            What it links to:   reference="Help.GettingStarted", direction="out"
            Both directions:    reference="Help.GettingStarted", direction="both"
            A translation:      reference="Help.GettingStarted", direction="out", locale="fr"
            Page the list:      reference="Main.WebHome", limit=20, offset=20
        """;

    /**
     * The cross-wiki example line, reach-gated like its NOTES paragraph.
     */
    private static final String MAN_EXAMPLE_CROSS_WIKI = """
            Another wiki:       reference="Main.WebHome", wiki="second"
        """;

    /**
     * The man-page tail shown on every endpoint.
     */
    private static final String MAN_TAIL = """

        SEE ALSO
            man get_document       Read a page listed here, by its reference.
            man get_tree           Navigate the page hierarchy around a document.
            man query_documents    Search documents by keywords instead of link structure.
            man get_history        See when and by whom a linking page changed.
            man                    (no argument) List all tools and reference pages.
        """;

    /**
     * The full man page for cross-wiki enabled endpoints.
     */
    private static final String MAN_PAGE = MAN_NOTES_BASE + MAN_NOTES_PAGING + MAN_NOTES_HIDDEN
        + MAN_NOTES_CROSS_WIKI + MAN_EXAMPLES_BASE + MAN_EXAMPLE_CROSS_WIKI + MAN_TAIL;

    /**
     * The man page for reach-off endpoints: no cross-wiki paragraph, no wiki-parameter example.
     */
    private static final String MAN_PAGE_LOCAL =
        MAN_NOTES_BASE + MAN_NOTES_PAGING + MAN_NOTES_HIDDEN + MAN_EXAMPLES_BASE + MAN_TAIL;

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops
     * the cross-wiki sentence and example from the {@code reference} description and omits the
     * {@code wiki} parameter, so no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPGetLinksTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private MCPLinksSupport linksSupport;

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example, the cross-wiki
     * sentence and the {@code wiki} parameter only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document whose links to inspect, e.g. \"Help.GettingStarted\" "
            + "or \"" + (crossWiki ? "xwiki:" : "") + "Sandbox.WebHome\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .stringIf(crossWiki, WIKI_PARAM, "Optional wiki id to inspect instead of the current wiki "
                + "(see list_wikis). One wiki per call.")
            .string(DIRECTION_PARAM, "\"in\" (default): the pages linking TO this document (backlinks). "
                + "\"out\": this document's own outgoing links (documents and attachments). \"both\": "
                + "both sections.")
            .string(LOCALE_PARAM, "Only with direction=\"out\": read the outgoing links of a specific "
                + "translation, e.g. " + MCPToolSupport.LOCALE_FORMS + ". Omit for the default language "
                + "version.")
            .integer(LIMIT_PARAM, "Backlinks per page (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT
                + "). Backlink list only - not valid with direction=\"out\".")
            .integer(OFFSET_PARAM, "How many backlinks to skip (paging; default 0). Backlink list only - "
                + "not valid with direction=\"out\".")
            .bool(SHOW_HIDDEN_PARAM, "If true, list hidden pages in the backlink list, each marked "
                + "(hidden) (default false: hidden backlinks are only counted, regardless of your "
                + "profile preference). Backlink list only - not valid with direction=\"out\".")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.advertised(this.wikiReach.isReachEnabled()).inputSchema())
            .description("Read the link graph around one document. direction=\"in\" (default) lists the "
                + "pages whose links point AT it (backlinks - \"who links here?\", including links to a "
                + "missing page that needs repair). direction=\"out\" lists the document's own resolved "
                + "outgoing links, including links stored in its objects' fields. direction=\"both\" "
                + "lists both.")
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
        return "List the pages linking to a document (backlinks), or its own outgoing links.";
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
            return read(args);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }
    }

    /**
     * Runs one link read: parses and cross-validates the arguments, resolves and authorizes the
     * reference through the shared door, and dispatches to the requested direction.
     *
     * @param args the tool call arguments
     * @return the tool result
     * @throws IllegalArgumentException with an agent-facing message on invalid arguments or a failed
     *     load
     */
    private McpSchema.CallToolResult read(Map<String, Object> args)
    {
        String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
        LinksRequest req = parseRequest(args);

        DocumentReference ref;
        try {
            String targetWiki = this.wikiReach.resolveSingleWiki(PARAMS.parser().string(args, WIKI_PARAM));
            ref = this.documentAccess.resolveAndAuthorize(reference, Right.VIEW, new WikiReference(targetWiki));
        } catch (MCPAccessDeniedException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }

        if (DIRECTION_OUT.equals(req.direction())) {
            return outgoingResult(ref, reference, req.locale());
        }
        try {
            return backlinksResult(ref, reference, req);
        } catch (LinkException e) {
            this.logger.warn("MCP get_links tool failed to read the backlinks of [{}]: [{}]", ref,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_links tool backlink read failure details", e);
            return MCPToolSupport.errorResult(LINK_INDEX_ERROR);
        }
    }

    /**
     * Parses and cross-validates the direction-dependent arguments into one value: the direction must
     * be a known value, {@code locale} is rejected outside {@code direction="out"} while {@code limit}/
     * {@code offset}/{@code showHidden} are rejected with it, so a malformed call teaches rather than
     * pretends. The limit is clamped to its accepted range and a negative offset folds to zero.
     *
     * @param args the tool call arguments
     * @return the validated request
     * @throws IllegalArgumentException with the agent-facing message on an invalid value or combination
     */
    private LinksRequest parseRequest(Map<String, Object> args)
    {
        String direction = validatedDirection(args);
        Locale locale = MCPToolSupport.parseLocale(PARAMS.parser().string(args, LOCALE_PARAM), LOCALE_PARAM);
        if (locale != null && !DIRECTION_OUT.equals(direction)) {
            throw new IllegalArgumentException(LOCALE_DIRECTION_ERROR);
        }
        Boolean showHidden = PARAMS.parser().boolOrNull(args, SHOW_HIDDEN_PARAM);
        if (showHidden != null && DIRECTION_OUT.equals(direction)) {
            throw new IllegalArgumentException(SHOW_HIDDEN_DIRECTION_ERROR);
        }
        return withPaging(args, direction, locale, Boolean.TRUE.equals(showHidden));
    }

    /**
     * Reads and validates the {@code direction} argument, defaulting an absent value to
     * {@link #DIRECTION_IN}.
     *
     * @param args the tool call arguments
     * @return the validated direction
     * @throws IllegalArgumentException with the agent-facing message on an unknown value
     */
    private static String validatedDirection(Map<String, Object> args)
    {
        String direction = PARAMS.parser().string(args, DIRECTION_PARAM);
        if (direction == null) {
            return DIRECTION_IN;
        }
        if (!DIRECTION_IN.equals(direction) && !DIRECTION_OUT.equals(direction)
            && !DIRECTION_BOTH.equals(direction)) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + DIRECTION_PARAM
                + "' must be \"in\", \"out\" or \"both\"; got " + QUOTE
                + MCPTextGuards.fragment(direction) + QUOTE + PERIOD);
        }
        return direction;
    }

    /**
     * Reads the paging arguments and completes the request: {@code limit}/{@code offset} are rejected
     * with {@code direction="out"}, the limit is clamped to its accepted range and a negative offset
     * folds to zero.
     *
     * @param args the tool call arguments
     * @param direction the validated direction
     * @param locale the validated {@code locale} argument, or {@code null}
     * @param showHidden the validated {@code showHidden} argument, absence folded to {@code false}
     * @return the validated request
     * @throws IllegalArgumentException with the agent-facing message on an invalid combination
     */
    private static LinksRequest withPaging(Map<String, Object> args, String direction, Locale locale,
        boolean showHidden)
    {
        Integer rawLimit = PARAMS.parser().integer(args, LIMIT_PARAM);
        Integer rawOffset = PARAMS.parser().integer(args, OFFSET_PARAM);
        if (DIRECTION_OUT.equals(direction) && (rawLimit != null || rawOffset != null)) {
            throw new IllegalArgumentException(PAGING_DIRECTION_ERROR);
        }
        int limit = Math.min(Math.max(rawLimit != null ? rawLimit : DEFAULT_LIMIT, 1), MAX_LIMIT);
        int offset = Math.max(rawOffset != null ? rawOffset : 0, 0);
        return new LinksRequest(direction, locale, offset, limit, showHidden);
    }

    /**
     * Composes the {@code direction="out"} response: the document must exist, the optional locale is
     * routed to the addressed translation, and the outgoing block is rendered under the shared output
     * budget.
     *
     * @param ref the resolved locale-free document reference
     * @param reference the original reference string, for error messages
     * @param locale the validated {@code locale} argument, or {@code null}
     * @return the tool result
     */
    private McpSchema.CallToolResult outgoingResult(DocumentReference ref, String reference, Locale locale)
    {
        if (!this.linksSupport.documentExists(ref, reference)) {
            return MCPToolSupport.errorResult(
                "No such document: " + QUOTE + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
        }
        DocumentReference target = ref;
        if (locale != null) {
            target = this.linksSupport.resolveTranslation(ref, locale, reference);
        }
        String header = LINKS_OF_PREFIX + this.linksSupport.canonical(ref)
            + translationSuffix(target.getLocale()) + DIRECTION_TAG + DIRECTION_OUT + ')';
        return MCPToolSupport.result(MCPSourceText.budgeted(
            header + DOUBLE_NEW_LINE + this.linksSupport.outgoingBlock(target, reference)));
    }

    /**
     * Composes the {@code direction="in"} and {@code direction="both"} responses: the header (with the
     * missing-target note when the document does not exist), the paged incoming section, the outgoing
     * block for {@code "both"}, and - appended AFTER the output budget cut so they survive it - the
     * paging footer and the ceiling note.
     *
     * @param ref the resolved locale-free document reference
     * @param reference the original reference string, for error messages
     * @param req the validated request
     * @return the tool result
     * @throws LinkException when the link index read or the backlink filter fails
     */
    private McpSchema.CallToolResult backlinksResult(DocumentReference ref, String reference, LinksRequest req)
        throws LinkException
    {
        boolean exists = this.linksSupport.documentExists(ref, reference);
        MCPLinksSupport.BacklinkPage page = this.linksSupport.backlinks(ref, req.showHidden());
        StringBuilder body = new StringBuilder(LINKS_OF_PREFIX).append(this.linksSupport.canonical(ref))
            .append(DIRECTION_TAG).append(req.direction()).append(')');
        if (!exists) {
            body.append(NEW_LINE).append(MISSING_TARGET_NOTE);
        }
        InSection in = inSection(page, req);
        body.append(DOUBLE_NEW_LINE).append(in.block());
        if (DIRECTION_BOTH.equals(req.direction())) {
            body.append(DOUBLE_NEW_LINE)
                .append(exists ? this.linksSupport.outgoingBlock(ref, reference) : OUT_MISSING_NOTE);
        }
        String text = MCPSourceText.budgeted(body.toString());
        if (in.footer() != null) {
            text += NEW_LINE + in.footer();
        }
        if (page.capped()) {
            text += NEW_LINE + CEILING_NOTE;
        }
        return MCPToolSupport.result(text);
    }

    /**
     * Renders the incoming section: the counting heading (the count is authorized-only, a floor marked
     * {@code N+} when the scan ceiling was hit, with the authorized-but-hidden count appended when
     * hidden pages are not listed), then either the requested page of rows with its paging footer, the
     * applicable zero-rows note, or the past-the-end note.
     *
     * @param page the authorized backlink page
     * @param req the validated request, for the paging values and the hidden-inclusion flag
     * @return the rendered section block and its footer ({@code null} when no rows are shown)
     */
    private static InSection inSection(MCPLinksSupport.BacklinkPage page, LinksRequest req)
    {
        int offset = req.offset();
        int total = page.rows().size();
        StringBuilder block = new StringBuilder(countLine(page, req.showHidden()));
        if (total == 0) {
            String note = zeroNote(page);
            if (note != null) {
                block.append(NEW_LINE).append(note);
            }
            return new InSection(block.toString(), null);
        }
        if (offset >= total) {
            block.append(NEW_LINE).append("No backlinks at " + OFFSET_EQUALS).append(offset)
                .append("; this document has ").append(totalPhrase(page)).append(PERIOD);
            return new InSection(block.toString(), null);
        }
        List<String> rows = page.rows().subList(offset, Math.min(offset + req.limit(), total));
        block.append(NEW_LINE).append(String.join(NEW_LINE, rows));
        String footer = "Showing backlinks " + (offset + 1) + "-" + (offset + rows.size()) + " of "
            + total + (page.capped() ? FLOOR_MARK : "") + PERIOD;
        if (offset + rows.size() < total) {
            footer += " Continue with " + OFFSET_EQUALS + (offset + rows.size()) + PERIOD;
        }
        return new InSection(block.toString(), footer);
    }

    /**
     * Renders the counting heading of the incoming section: the authorized total (with its {@code +}
     * floor mark when capped, and the singular noun for exactly one), plus either the
     * {@code hidden included} suffix when hidden pages are listed in the rows, or the
     * authorized-but-hidden count when hidden pages link here without being listed.
     *
     * @param page the authorized backlink page
     * @param showHidden whether hidden pages are listed in the rows
     * @return the heading line
     */
    private static String countLine(MCPLinksSupport.BacklinkPage page, boolean showHidden)
    {
        String line = "Incoming links: " + totalPhrase(page);
        if (showHidden) {
            line += HIDDEN_INCLUDED;
        } else if (page.hiddenCount() > 0) {
            line += " (+" + page.hiddenCount() + " hidden)";
        }
        return line;
    }

    /**
     * Phrases the authorized total with its unit: {@code 1 backlink}, {@code 3 backlinks} or the
     * {@code 2000+ backlinks} floor form (a capped count of one is still a floor, so it keeps the
     * plural).
     *
     * @param page the authorized backlink page
     * @return the phrased total
     */
    private static String totalPhrase(MCPLinksSupport.BacklinkPage page)
    {
        int total = page.rows().size();
        return total + (page.capped() ? FLOOR_MARK : "")
            + (total == 1 && !page.capped() ? " backlink" : " backlinks");
    }

    /**
     * Picks the note below a zero-row heading: hidden-only backlinks are stated as a count when hidden
     * pages are not requested (with {@code showHidden} they appear as rows, so this branch never
     * fires), an empty uncapped answer carries the indexing teaching note, and a capped answer with
     * nothing visible needs no note here - the ceiling note follows the section.
     *
     * @param page the authorized backlink page
     * @return the note, or {@code null} when none applies
     */
    private static String zeroNote(MCPLinksSupport.BacklinkPage page)
    {
        if (page.hiddenCount() > 0) {
            return "No visible backlinks (" + page.hiddenCount()
                + (page.hiddenCount() == 1 ? " hidden page links here)." : " hidden pages link here).");
        }
        return page.capped() ? null : EMPTY_BACKLINKS_NOTE;
    }

    /**
     * @param locale the translation locale in play, or {@code null} for the default language row
     * @return the header suffix naming the translation, or an empty string for the default row
     */
    private static String translationSuffix(Locale locale)
    {
        if (locale == null) {
            return "";
        }
        return " (" + MCPToolSupport.stripLineBreaks(locale.toString()) + " translation)";
    }

    /**
     * The validated direction-dependent arguments of one call, produced together so the dispatch
     * receives one already-checked value.
     *
     * @param direction the validated {@code direction} argument
     * @param locale the {@code locale} argument, or {@code null}; only set with {@code direction="out"}
     * @param offset the non-negative {@code offset} argument
     * @param limit the clamped {@code limit} argument
     * @param showHidden whether hidden pages are listed in the backlink rows; never set with
     *     {@code direction="out"}
     * @version $Id$
     */
    private record LinksRequest(String direction, Locale locale, int offset, int limit, boolean showHidden)
    {
    }

    /**
     * One rendered incoming section: the heading-plus-rows block that goes through the output budget,
     * and the paging footer appended after the budget cut ({@code null} when no rows are shown).
     *
     * @param block the rendered heading and rows
     * @param footer the paging footer, or {@code null}
     * @version $Id$
     */
    private record InSection(String block, String footer)
    {
    }
}
