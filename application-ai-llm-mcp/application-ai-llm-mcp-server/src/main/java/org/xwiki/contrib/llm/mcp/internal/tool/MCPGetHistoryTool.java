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
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that reads an XWiki document's revision history for an agent, in three modes: the default
 * mode lists revision metadata (version, date, author, minor marker, comment) as a newest-first page
 * with minor edits included; {@code version} returns the source content of one historical revision,
 * windowed under the shared output budget; {@code from}/{@code to} return a unified diff of the
 * content between two revisions.
 *
 * <p>The tool closes the write-conflict recovery loop: when a write tool refuses a stale
 * {@code base_version}, a diff from that version to the current one shows exactly what changed, so
 * the agent can merge instead of blindly overwriting. Reverting is a read-then-write: read the old
 * content in version mode, then write it back through the normal write tools with the CURRENT
 * version as {@code base_version}.</p>
 *
 * <p>Resolution and authorization go through {@link MCPDocumentAccess#resolveAndAuthorize(String,
 * Right, WikiReference)} for {@link Right#VIEW} before anything is loaded, so the per-wiki space
 * filter is applied and the existence of a protected document is never leaked. {@link Right#VIEW} on
 * the document governs all of its history, matching the platform's own history viewer.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPGetHistoryTool.TOOL_ID)
@Singleton
public class MCPGetHistoryTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "get_history";

    private static final String REFERENCE_PARAM = "reference";

    private static final String WIKI_PARAM = "wiki";

    private static final String LOCALE_PARAM = "locale";

    private static final String VERSION_PARAM = "version";

    private static final String FROM_PARAM = "from";

    private static final String TO_PARAM = "to";

    private static final String LIMIT_PARAM = "limit";

    private static final String OFFSET_PARAM = "offset";

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String DASH = MCPContentWindow.DASH;

    private static final String OF_INFIX = MCPContentWindow.OF_INFIX;

    /**
     * The list-mode page size when {@code limit} is omitted.
     */
    private static final int DEFAULT_LIMIT = 20;

    /**
     * The largest accepted list-mode page size.
     */
    private static final int MAX_LIMIT = 100;

    /**
     * How many of the newest revisions a missing-revision error shows, so the agent can pick a real
     * version instead of retrying blindly.
     */
    private static final int RECENT_ROWS = 5;

    /**
     * The only accepted shape of a version argument: {@code major.minor}, both segments plain
     * digit runs. This is a security gate, not just input hygiene: the platform's revision provider
     * routes {@code prefix:} revision strings to other providers (deleted documents, XAR history),
     * and the digits-only shape makes such prefix smuggling impossible. It also keeps malformed
     * strings out of the archive's version parser, whose failure is an unchecked exception.
     * {@code String#matches(String)} anchors the whole input.
     */
    private static final String VERSION_REGEX = "\\d{1,9}\\.\\d{1,9}";

    /**
     * The agent-facing message shared by the existence-check and load failures, mirroring the
     * {@code get_document} wording so a broken document reads the same through both tools.
     */
    private static final String COULD_NOT_READ_PREFIX = "Could not read the document ";

    /**
     * The agent-facing message of a failed history read; the root cause stays in the server logs.
     */
    private static final String HISTORY_READ_ERROR = "Could not read the document history. Try again; if it "
        + "persists, report it to a wiki administrator (details are in the server logs).";

    /**
     * The agent-facing message of a failed diff computation; the root cause stays in the server logs.
     */
    private static final String DIFF_FAILED_ERROR = "Could not compute the diff. Try again; if it persists, "
        + "report it to a wiki administrator (details are in the server logs).";

    /**
     * The offset-assignment fragment closing the continuation hints, directly followed by the offset
     * value.
     */
    private static final String OFFSET_EQUALS = MCPContentWindow.OFFSET_EQUALS;

    /**
     * The self-correction hint shared by the two diff truncation notes, so their advice cannot drift
     * apart.
     */
    private static final String NARROWER_PAIR_HINT =
        "request a narrower revision pair (adjacent versions give the smallest diff)";

    /**
     * The note replacing the hunks a diff drops at the output budget: the cut is at a hunk boundary,
     * so every emitted hunk stays whole and its line numbers stay true.
     */
    private static final String DIFF_TRUNCATED_NOTE = "Diff truncated at the ~"
        + MCPSourceText.MAX_OUTPUT_TOKENS + "-token cap; " + NARROWER_PAIR_HINT + PERIOD;

    /**
     * The note closing a first hunk that ALONE exceeds the output budget (a full-content rewrite is one
     * giant hunk): its lines are cut at a line boundary at the budget, and this note announces the cut
     * so the emitted hunk cannot pass for the whole change.
     */
    private static final String FIRST_HUNK_TRUNCATED_NOTE =
        "[diff truncated: the first hunk alone exceeds the output budget - " + NARROWER_PAIR_HINT + "]";

    /**
     * The version example shared by the {@code version} and {@code from} parameter descriptions.
     */
    private static final String VERSION_EXAMPLE = " revision (e.g. \"2.1\")";

    /**
     * Error returned when {@code limit} is combined with a mode it does not apply to.
     */
    private static final String LIMIT_MODE_ERROR = MCPToolSupport.ERROR_PREFIX + LIMIT_PARAM
        + "' only applies to the revision list; drop it when using '" + VERSION_PARAM + "' or '"
        + FROM_PARAM + "'.";

    /**
     * Error returned when {@code offset} is combined with a diff, whose hunks carry their own line
     * numbers.
     */
    private static final String OFFSET_DIFF_ERROR = MCPToolSupport.ERROR_PREFIX + OFFSET_PARAM
        + "' does not apply to a diff (hunks carry their own line numbers); it pages the revision list "
        + "or continues a version-mode content read.";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops
     * the cross-wiki sentence and example from the {@code reference} description and omits the
     * {@code wiki} parameter, so no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPGetHistoryTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private MCPHistorySupport historySupport;

    @Inject
    private MCPTranslationSupport translationSupport;

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example, the cross-wiki
     * sentence and the {@code wiki} parameter only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document whose history to read, e.g. \"Help.GettingStarted\" or \""
            + (crossWiki ? "xwiki:" : "") + "Sandbox.WebHome\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .stringIf(crossWiki, WIKI_PARAM, "Optional wiki id to read from instead of the current wiki "
                + "(see list_wikis). One wiki per call.")
            .string(LOCALE_PARAM, "Read the history of a specific translation, e.g. "
                + MCPToolSupport.LOCALE_FORMS + ". Translations have separate histories. Omit for the "
                + "default language version.")
            .string(VERSION_PARAM, "Return the source content of this" + VERSION_EXAMPLE
                + " instead of the revision list. Cannot be combined with from/to.")
            .string(FROM_PARAM, "Return a unified diff of the content from this" + VERSION_EXAMPLE
                + " to the 'to' revision. After a \"Version conflict\" error, pass your stale "
                + "base_version here to see what changed.")
            .string(TO_PARAM, "Newer end of the diff; requires 'from'. Omit for the current version.")
            .integer(LIMIT_PARAM, "Revisions per page in list mode (default " + DEFAULT_LIMIT + ", max "
                + MAX_LIMIT + "). List mode only - not valid with version or from/to.")
            .integer(OFFSET_PARAM, "List mode: how many of the NEWEST revisions to skip (paging; default "
                + "0). Version mode: 1-based line to continue a truncated content read from, as given by "
                + "the truncation note. Not valid with from/to.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.advertised(this.wikiReach.isReachEnabled()).inputSchema())
            .description("Read a document's revision history. Default: a newest-first page of revisions "
                + "(minor edits included) with date, author and comment per version. version=\"X.Y\" "
                + "returns that revision's source content (a read-only historical snapshot). "
                + "from=\"X.Y\" (optionally with to=\"X.Y\"; default the current version) returns a "
                + "unified diff of the content between two revisions. After a \"Version conflict\" "
                + "error, call with from=<your base_version> to see what changed before retrying.")
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
        return "Read a document's revision history, one revision's content, or a diff.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                Three modes. The default lists revision metadata newest first, minor edits
                included (this server's updates are minor versions unless major=true;
                creations are major); offset skips the newest revisions and limit caps the
                page. version="X.Y" returns that
                revision's source content, line-numbered and windowed like get_document
                (offset continues a truncated read; do not copy the line-number prefix).
                from="X.Y" (with an optional to="X.Y", default the current version) returns
                a unified diff of the content; hunk line numbers refer to each revision's
                full source. Versions are always "major.minor" strings, as listed.

                Conflict recovery: when a write tool reports "Version conflict", call
                get_history with from=<your stale base_version> to see what changed, merge
                your change into the current content, then retry with the CURRENT version
                as base_version.

                Revert: read the old content with version="X.Y", then write it back with
                write_document using the CURRENT version (not X.Y) as base_version. There
                is no dedicated revert operation.

                Translations have separate histories: locale="fr" reads the fr row's
                history (exact match, no language fallback; a missing translation is
                refused with the list of translations that do exist). Authors and version
                comments are wiki-authored content.

            EXAMPLES
                List history:   reference="Sandbox.WebHome"
                Older page:     reference="Sandbox.WebHome", offset=20
                One revision:   reference="Sandbox.WebHome", version="2.1"
                What changed:   reference="Sandbox.WebHome", from="2.1"  (2.1 -> current)
                Between two:    reference="Sandbox.WebHome", from="2.1", to="3.4"
                A translation:  reference="Sandbox.WebHome", locale="fr"

            SEE ALSO
                man get_document    Read the CURRENT content and version (the base_version source).
                man get_links       Who links to this document (e.g. before reverting a rename).
                man write_document  Replace a document's content (revert = write an old revision back).
                man edit_document   Targeted exact-string edits, e.g. after a conflict merge.
                man                 (no argument) List all tools and reference pages.
            """;
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
     * Runs one history read: parses and cross-validates the arguments, resolves and authorizes the
     * document, routes the locale to the addressed language row, and dispatches to the requested mode.
     *
     * @param args the tool call arguments
     * @return the tool result
     * @throws IllegalArgumentException with an agent-facing message on invalid arguments or a failed
     *     load
     */
    private McpSchema.CallToolResult read(Map<String, Object> args)
    {
        String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
        Locale requestedLocale =
            MCPToolSupport.parseLocale(PARAMS.parser().string(args, LOCALE_PARAM), LOCALE_PARAM);
        ModeRequest mode = parseModeRequest(args);

        DocumentReference ref;
        try {
            String targetWiki = this.wikiReach.resolveSingleWiki(PARAMS.parser().string(args, WIKI_PARAM));
            ref = this.documentAccess.resolveAndAuthorize(reference, Right.VIEW, new WikiReference(targetWiki));
        } catch (MCPAccessDeniedException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }

        if (!documentExists(ref, reference)) {
            return MCPToolSupport.errorResult(
                "No such document: " + QUOTE + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
        }
        XWikiDocument xdoc = loadDocument(ref, reference);
        Locale translationLocale = null;
        if (requestedLocale != null) {
            // Authorization above used the locale-free reference; the shared resolver routes the
            // locale to the addressed language row (see MCPTranslationSupport).
            MCPTranslationSupport.TranslationTarget target =
                this.translationSupport.resolve(ref, xdoc, requestedLocale, reference);
            ref = target.reference();
            xdoc = target.document();
            translationLocale = target.locale();
        }
        return dispatch(xdoc, ref, translationLocale, mode);
    }

    /**
     * Parses and cross-validates the mode-selecting and paging arguments into one value: every version
     * string passes the {@link #VERSION_REGEX} gate, {@code version} excludes {@code from}/{@code to},
     * {@code to} needs {@code from}, the limit is clamped to its accepted range and a negative offset
     * folds to zero.
     *
     * @param args the tool call arguments
     * @return the validated mode request
     * @throws IllegalArgumentException with the agent-facing message on an invalid value or combination
     */
    private ModeRequest parseModeRequest(Map<String, Object> args)
    {
        String version = validatedVersion(args, VERSION_PARAM);
        String from = validatedVersion(args, FROM_PARAM);
        String to = validatedVersion(args, TO_PARAM);
        validateModeCombination(version, from, to);
        Integer rawLimit = PARAMS.parser().integer(args, LIMIT_PARAM);
        Integer rawOffset = PARAMS.parser().integer(args, OFFSET_PARAM);
        // Reject the parameters that are meaningless in the selected mode instead of silently ignoring
        // them, so a malformed call teaches rather than pretends: limit only pages the revision list,
        // and offset has no meaning in a diff (hunks carry their own line numbers).
        if (rawLimit != null && (version != null || from != null)) {
            throw new IllegalArgumentException(LIMIT_MODE_ERROR);
        }
        if (rawOffset != null && from != null) {
            throw new IllegalArgumentException(OFFSET_DIFF_ERROR);
        }
        int limit = Math.min(Math.max(rawLimit != null ? rawLimit : DEFAULT_LIMIT, 1), MAX_LIMIT);
        int offset = Math.max(rawOffset != null ? rawOffset : 0, 0);
        return new ModeRequest(version, from, to, offset, limit);
    }

    /**
     * Routes the validated call to its mode: version content, diff, or the default revision list.
     *
     * @param xdoc the loaded document row
     * @param ref the resolved (possibly locale-carrying) reference
     * @param locale the translation locale in play, or {@code null} for the default language row
     * @param mode the validated mode request
     * @return the tool result
     */
    private McpSchema.CallToolResult dispatch(XWikiDocument xdoc, DocumentReference ref, Locale locale,
        ModeRequest mode)
    {
        if (mode.version() != null) {
            return versionContent(xdoc, ref, mode.version(), mode.offset(), locale);
        }
        if (mode.from() != null) {
            return diff(xdoc, ref, mode.from(), mode.to(), locale);
        }
        return revisionList(xdoc, ref, locale, mode.offset(), mode.limit());
    }

    /**
     * Reads and validates one version-valued argument against {@link #VERSION_REGEX}. A sent-but-empty
     * value is rejected like any other malformed version rather than silently treated as absent, so a
     * templating slip on the caller's side cannot flip the call into another mode.
     *
     * @param args the tool call arguments
     * @param param the parameter name
     * @return the validated version string, or {@code null} when absent
     * @throws IllegalArgumentException with the agent-facing message when the value is not a plain
     *     {@code major.minor} version
     */
    private String validatedVersion(Map<String, Object> args, String param)
    {
        String value = PARAMS.parser().stringOrEmpty(args, param);
        if (value != null && !value.matches(VERSION_REGEX)) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + param
                + "' must be a version of the form major.minor (e.g. \"3.2\"); got "
                + QUOTE + MCPTextGuards.fragment(value) + QUOTE + PERIOD);
        }
        return value;
    }

    /**
     * Cross-validates the mode-selecting parameters: {@code version} excludes {@code from}/{@code to},
     * and {@code to} needs {@code from} to name the older end.
     *
     * @param version the {@code version} argument, or {@code null}
     * @param from the {@code from} argument, or {@code null}
     * @param to the {@code to} argument, or {@code null}
     * @throws IllegalArgumentException with the agent-facing message on an invalid combination
     */
    private static void validateModeCombination(String version, String from, String to)
    {
        if (version != null && (from != null || to != null)) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + VERSION_PARAM
                + "' cannot be combined with '" + FROM_PARAM + "'/'" + TO_PARAM + "'. Use version for one "
                + "revision's content, from/to for a diff.");
        }
        if (to != null && from == null) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + TO_PARAM + "' requires '"
                + FROM_PARAM + "' to name the older end of the diff.");
        }
    }

    /**
     * Composes the list-mode response: the header, one row per revision (newest first) and the paging
     * footer with a continuation hint while older revisions remain. An offset at or past the total
     * yields the header plus a note stating the total instead of an empty page.
     *
     * @param xdoc the loaded document row
     * @param ref the resolved (possibly locale-carrying) reference
     * @param locale the translation locale in play, or {@code null} for the default language row
     * @param offset the number of newest revisions to skip
     * @param limit the page size
     * @return the tool result
     */
    private McpSchema.CallToolResult revisionList(XWikiDocument xdoc, DocumentReference ref, Locale locale,
        int offset, int limit)
    {
        try {
            long total = this.historySupport.countRevisions(xdoc);
            String header = composeHeader(ref, xdoc.getVersion(), locale, total);
            if (offset >= total) {
                return MCPToolSupport.result(header + DOUBLE_NEW_LINE + "No revisions at " + OFFSET_EQUALS
                    + offset + "; this document has " + total + " revisions.");
            }
            List<String> rows = this.historySupport.revisionRows(xdoc, total, offset, limit);
            String footer = "Showing revisions " + (offset + 1) + DASH + (offset + rows.size()) + OF_INFIX
                + total + PERIOD;
            if (offset + rows.size() < total) {
                footer += " Continue with " + OFFSET_EQUALS + (offset + rows.size()) + PERIOD;
            }
            // Budget the header-plus-rows block alone, then append the paging footer AFTER the cut, so
            // a page of pathological comments cannot flood the response and the footer survives it.
            return MCPToolSupport.result(
                MCPSourceText.budgeted(header + DOUBLE_NEW_LINE + String.join(NEW_LINE, rows))
                    + NEW_LINE + footer);
        } catch (XWikiException e) {
            return historyReadFailure(ref, e);
        }
    }

    /**
     * Composes the version-mode response: the historical-snapshot banner first, then the revision's
     * source content windowed under the shared output budget with offset continuation. A missing
     * revision is refused with the newest revisions so the agent can self-correct.
     *
     * @param xdoc the loaded document row (the CURRENT version)
     * @param ref the resolved (possibly locale-carrying) reference
     * @param version the validated revision to read
     * @param offset the 1-based line to continue from (0 reads from the start)
     * @param locale the translation locale in play, or {@code null} for the default language row
     * @return the tool result
     */
    private McpSchema.CallToolResult versionContent(XWikiDocument xdoc, DocumentReference ref, String version,
        int offset, Locale locale)
    {
        XWikiDocument revision;
        try {
            revision = this.historySupport.loadRevision(ref, version);
        } catch (XWikiException e) {
            return historyReadFailure(ref, e);
        }
        if (revision == null) {
            return missingRevisionResult(xdoc, ref, version, locale);
        }
        String banner = "HISTORICAL SNAPSHOT of " + canonical(ref) + translationSuffix(locale)
            + " at version " + version + "; the CURRENT version is " + xdoc.getVersion()
            + ". This is read-only historical data: for edits, base_version must come from the CURRENT "
            + "document (get_document).";
        return windowedContent(banner, MCPSourceText.normalizeLineEndings(revision.getContent()), offset);
    }

    /**
     * Composes the diff-mode response: loads the two revisions ({@code to} defaults to the already
     * loaded current row), diffs their FULL contents and renders the {@code ---}/{@code +++} header,
     * the title-change line when the titles differ, and the unified hunks bounded at the output
     * budget. A missing revision is refused naming the side that was missing.
     *
     * @param xdoc the loaded document row (the CURRENT version)
     * @param ref the resolved (possibly locale-carrying) reference
     * @param from the validated older revision
     * @param to the validated newer revision, or {@code null} for the current version
     * @param locale the translation locale in play, or {@code null} for the default language row
     * @return the tool result
     */
    private McpSchema.CallToolResult diff(XWikiDocument xdoc, DocumentReference ref, String from, String to,
        Locale locale)
    {
        XWikiDocument fromDoc;
        XWikiDocument toDoc;
        try {
            fromDoc = this.historySupport.loadRevision(ref, from);
            toDoc = to != null ? this.historySupport.loadRevision(ref, to) : xdoc;
        } catch (XWikiException e) {
            return historyReadFailure(ref, e);
        }
        if (fromDoc == null) {
            return missingRevisionResult(xdoc, ref, from, locale);
        }
        if (toDoc == null) {
            return missingRevisionResult(xdoc, ref, to, locale);
        }
        return diffResult(ref, locale, fromDoc, from, toDoc, to != null ? to : xdoc.getVersion());
    }

    /**
     * Renders the diff of two loaded revisions. The contents are diffed whole - never pre-windowed -
     * so the hunk line numbers are true line numbers of each revision's full source; only the OUTPUT
     * is bounded, dropped at a hunk boundary with the truncation note.
     *
     * @param ref the resolved (possibly locale-carrying) reference
     * @param locale the translation locale in play, or {@code null} for the default language row
     * @param fromDoc the older revision
     * @param fromVersion the older revision's version, for the header
     * @param toDoc the newer revision
     * @param toVersion the newer revision's version, for the header
     * @return the tool result
     */
    private McpSchema.CallToolResult diffResult(DocumentReference ref, Locale locale, XWikiDocument fromDoc,
        String fromVersion, XWikiDocument toDoc, String toVersion)
    {
        List<String> hunks;
        try {
            hunks = this.historySupport.unifiedDiffBlocks(
                MCPSourceText.normalizeLineEndings(fromDoc.getContent()),
                MCPSourceText.normalizeLineEndings(toDoc.getContent()));
        } catch (Exception e) {
            this.logger.warn("MCP get_history tool failed to diff [{}]: [{}]", canonical(ref),
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_history tool diff failure details", e);
            return MCPToolSupport.errorResult(DIFF_FAILED_ERROR);
        }
        String titleLine = titleChangeLine(fromDoc, toDoc);
        if (hunks.isEmpty()) {
            String message = "No content differences between " + fromVersion + " and " + toVersion + PERIOD;
            if (titleLine != null) {
                message += NEW_LINE + titleLine;
            }
            return MCPToolSupport.result(message);
        }
        String subject = canonical(ref) + translationSuffix(locale);
        String header = "--- " + subject + versionTag(fromVersion) + NEW_LINE
            + "+++ " + subject + versionTag(toVersion);
        if (titleLine != null) {
            header += NEW_LINE + titleLine;
        }
        return MCPToolSupport.result(header + NEW_LINE + joinedHunks(hunks, header.length()));
    }

    /**
     * @param version a revision's version string
     * @return the parenthesized version tag of the diff header lines
     */
    private static String versionTag(String version)
    {
        return " (v" + version + ')';
    }

    /**
     * Builds the {@code Title changed:} line when the two revisions' titles differ, or {@code null}
     * when they match. Titles are wiki-authored values and are neutralized before entering the line
     * grammar.
     *
     * @param fromDoc the older revision
     * @param toDoc the newer revision
     * @return the title-change line, or {@code null} when the titles match
     */
    private static String titleChangeLine(XWikiDocument fromDoc, XWikiDocument toDoc)
    {
        String oldTitle = StringUtils.defaultString(fromDoc.getTitle());
        String newTitle = StringUtils.defaultString(toDoc.getTitle());
        if (oldTitle.equals(newTitle)) {
            return null;
        }
        return "Title changed: " + QUOTE + MCPTextGuards.fragment(oldTitle) + QUOTE + " -> "
            + QUOTE + MCPTextGuards.fragment(newTitle) + QUOTE;
    }

    /**
     * Joins the rendered hunks under the output budget left below the header. Later hunks are dropped
     * whole, never cut, from the first one that no longer fits, replaced by the truncation note. A
     * FIRST hunk that alone exceeds the budget (a full-content rewrite is one giant hunk) is instead
     * cut at a line boundary at the budget and closed with its own note, so a single hunk can never
     * bypass the budget - and the announced cut keeps the emitted lines honest.
     *
     * @param hunks the rendered hunks, each ending with a newline
     * @param headerLength the characters the header already spent of the output budget
     * @return the joined hunk text, possibly ending with a truncation note
     */
    private static String joinedHunks(List<String> hunks, int headerLength)
    {
        int budget = Math.max(1, MCPSourceText.MAX_OUTPUT_CHARS - headerLength);
        String first = hunks.get(0);
        if (first.length() > budget) {
            return cappedFirstHunk(first, budget);
        }
        StringBuilder out = new StringBuilder(first);
        for (String hunk : hunks.subList(1, hunks.size())) {
            if (out.length() + hunk.length() > budget) {
                out.append(DIFF_TRUNCATED_NOTE);
                break;
            }
            out.append(hunk);
        }
        return out.toString();
    }

    /**
     * Cuts an over-budget first hunk at the last complete line within the budget (falling back to a
     * hard cut when no line boundary exists there) and closes it with the announcing note.
     *
     * @param hunk the rendered hunk
     * @param budget the character budget left below the header
     * @return the cut hunk lines followed by the truncation note
     */
    private static String cappedFirstHunk(String hunk, int budget)
    {
        int cut = hunk.lastIndexOf('\n', budget);
        if (cut <= 0) {
            cut = budget;
        }
        return hunk.substring(0, cut) + NEW_LINE + FIRST_HUNK_TRUNCATED_NOTE;
    }

    /**
     * Composes the version-mode content window below the banner, sharing the {@code get_document}
     * range-read mechanics through {@link MCPContentWindow}: line-numbered output, the shown-lines
     * footer, the continuation hint on a budget cut and a clear refusal on an offset past the last
     * line.
     *
     * @param banner the historical-snapshot banner
     * @param content the revision's content, line endings normalized
     * @param offset the 1-based line to start from (0 reads from the start)
     * @return the tool result
     */
    private static McpSchema.CallToolResult windowedContent(String banner, String content, int offset)
    {
        if (content.isEmpty()) {
            return MCPToolSupport.result(banner + DOUBLE_NEW_LINE + "This revision has no content.");
        }
        String[] lines = content.split(NEW_LINE, -1);
        int totalLines = lines.length;
        int start = Math.max(offset, 1);
        if (start > totalLines) {
            return MCPToolSupport.errorResult("offset " + start + " exceeds this revision's length ("
                + totalLines + " lines). Use an offset of at most " + totalLines + PERIOD);
        }
        int end = MCPContentWindow.cappedEnd(lines, start, totalLines, MCPSourceText.MAX_OUTPUT_CHARS);
        return MCPToolSupport.result(banner + DOUBLE_NEW_LINE + MCPSourceText.numberedLines(lines, start, end)
            + NEW_LINE + MCPContentWindow.footer(start, end, totalLines, totalLines));
    }

    /**
     * Builds the missing-revision refusal: the bad version, and the newest revisions so the agent can
     * pick a real one. The recent-rows listing is a nicety - its own failure degrades to the bare
     * refusal instead of masking it.
     *
     * @param xdoc the loaded document row
     * @param ref the resolved (possibly locale-carrying) reference
     * @param version the revision that does not exist
     * @param locale the translation locale in play, or {@code null} for the default language row
     * @return the error result
     */
    private McpSchema.CallToolResult missingRevisionResult(XWikiDocument xdoc, DocumentReference ref,
        String version, Locale locale)
    {
        String message = "No revision " + QUOTE + version + QUOTE + OF_INFIX + QUOTE
            + canonical(ref) + QUOTE + translationSuffix(locale) + PERIOD;
        try {
            long total = this.historySupport.countRevisions(xdoc);
            if (total > 0) {
                List<String> rows = this.historySupport.revisionRows(xdoc, total, 0, RECENT_ROWS);
                message += " Most recent revisions:" + NEW_LINE + String.join(NEW_LINE, rows);
            }
        } catch (XWikiException e) {
            this.logger.debug("MCP get_history tool could not list the recent revisions", e);
        }
        return MCPToolSupport.errorResult(message);
    }

    /**
     * Composes the list-mode header: the addressed document (naming the translation when one is in
     * play), its current version and the total revision count with the semantics note.
     *
     * @param ref the resolved (possibly locale-carrying) reference
     * @param currentVersion the row's current version
     * @param locale the translation locale in play, or {@code null} for the default language row
     * @param total the total revision count
     * @return the composed header
     */
    private String composeHeader(DocumentReference ref, String currentVersion, Locale locale, long total)
    {
        return "History of " + canonical(ref) + translationSuffix(locale) + NEW_LINE
            + "Current version: " + currentVersion + NEW_LINE
            + "Total revisions: " + total + " (minor edits included, newest first)";
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
     * @param ref a resolved document reference
     * @return its serialized form, neutralized for the line grammar
     */
    private String canonical(DocumentReference ref)
    {
        return MCPToolSupport.stripLineBreaks(this.serializer.serialize(ref));
    }

    /**
     * Handles a failed history read uniformly: the root cause goes to the server logs, the agent gets
     * the fixed error message.
     *
     * @param ref the resolved reference whose history read failed
     * @param e the failure
     * @return the error result
     */
    private McpSchema.CallToolResult historyReadFailure(DocumentReference ref, XWikiException e)
    {
        this.logger.warn("MCP get_history tool failed to read the history of [{}]: [{}]", ref,
            ExceptionUtils.getRootCauseMessage(e));
        this.logger.debug("MCP get_history tool history read failure details", e);
        return MCPToolSupport.errorResult(HISTORY_READ_ERROR);
    }

    private boolean documentExists(DocumentReference ref, String reference)
    {
        try {
            return this.documentAccessBridge.exists(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_history tool failed to check existence of [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_history tool existence-check failure details", e);
            throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
                + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
        }
    }

    /**
     * Loads the addressed document row as its oldcore instance, which owns the versioning reads.
     *
     * @param ref the resolved (possibly locale-carrying) document reference
     * @param reference the original reference string, for error messages
     * @return the loaded document
     * @throws IllegalArgumentException with the agent-facing message when the load fails
     */
    private XWikiDocument loadDocument(DocumentReference ref, String reference)
    {
        Object doc = null;
        try {
            doc = this.documentAccessBridge.getDocumentInstance(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_history tool failed to load [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_history tool load failure details", e);
        }
        if (doc instanceof XWikiDocument xdoc) {
            return xdoc;
        }
        throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
            + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
    }

    /**
     * The validated mode-selecting and paging arguments of one call, produced together so the mode
     * dispatch receives one already-checked value.
     *
     * @param version the {@code version} argument, or {@code null}
     * @param from the {@code from} argument, or {@code null}
     * @param to the {@code to} argument, or {@code null}
     * @param offset the non-negative {@code offset} argument
     * @param limit the clamped {@code limit} argument
     * @version $Id$
     */
    private record ModeRequest(String version, String from, String to, int offset, int limit)
    {
    }
}
