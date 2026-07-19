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

import java.util.Locale;
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
import org.xwiki.contrib.llm.mcp.MCPSourceText;
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
 * MCP tool that creates an XWiki document or replaces the full content of an existing one, the
 * whole-body counterpart of {@link MCPEditDocumentTool}'s exact-string search-and-replace.
 *
 * <p>This is a default tool bundled with the MCP server module. The {@code content} argument is the
 * complete new document source: on a new document it becomes the body of version 1.1; on an existing
 * document it replaces the body entirely, gated by a mandatory {@code base_version} (the version the
 * agent read with {@code get_document}), so an overwrite is always a deliberate read-modify-write
 * and never a blind clobber. The incoming content is line-ending normalized with
 * {@link MCPSourceText#normalizeLineEndings(String)}, matching the representation the read and edit
 * tools operate on.</p>
 *
 * <p>Resolution and authorization both go through {@link MCPDocumentAccess} for the edit right before
 * the document is loaded, so the per-wiki space filter is applied and the existence of a protected
 * document is never leaked. The save goes through {@link com.xpn.xwiki.api.Document} so author
 * attribution and save-time rights are applied.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPWriteDocumentTool.TOOL_ID)
@Singleton
public class MCPWriteDocumentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "write_document";

    private static final String REFERENCE_PARAM = "reference";

    private static final String CONTENT_PARAM = "content";

    private static final String TITLE_PARAM = "title";

    private static final String LOCALE_PARAM = "locale";

    private static final String BASE_VERSION_PARAM = "base_version";

    private static final String COMMENT_PARAM = "comment";

    private static final String MAJOR_PARAM = "major";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String DOCUMENT_QUOTE_PREFIX = "Document " + QUOTE;

    /**
     * The retry instruction closing this tool's version-conflict messages.
     */
    private static final String RETRY = "retry.";

    /**
     * The generated version-comment summary of a non-creating save.
     */
    private static final String REPLACED_CONTENT = "Replaced content";

    /**
     * Opening of the no-op result: the given content and title match the written row exactly, so no
     * version is created. The row is named via {@link MCPWriteSupport#currentRowSubject(Locale)}.
     */
    private static final String NO_CHANGES_PREFIX = "No changes: the given content is identical to ";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description so no
     * cross-wiki capability is surfaced. The {@code content} parameter is declared for the schema but read raw
     * in {@link #requireRawContent(Map)}, as the accessors trim string values and trimming would alter the
     * saved body.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPWriteDocumentTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private MCPWikiReach wikiReach;

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example and the cross-wiki sentence in
     * the {@code reference} description only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document reference to create or overwrite, e.g. \"Sandbox.WebHome\" "
            + "or \"" + (crossWiki ? "xwiki:" : "") + "Help.Foo\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(CONTENT_PARAM, "The complete new document source.")
            .string(TITLE_PARAM, "Optional new title. Kept unchanged when omitted.")
            .string(LOCALE_PARAM, "Write a specific translation of the document, e.g. "
                + MCPToolSupport.LOCALE_FORMS + " (exact-match, like get_document). Omit for the default "
                + "language version, which must exist before a translation can be created.")
            .string(BASE_VERSION_PARAM, "The version shown by get_document for the same locale; omit "
                + "when creating (a new document or a new translation).")
            .string(COMMENT_PARAM, "Version comment shown in the document history. Stored prefixed with "
                + "[AI]. Default: a generic [AI] comment.")
            .bool(MAJOR_PARAM, "Set true to record this save as a major version. Default false (minor). "
                + "Creation is always major.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = PARAMS.advertised(this.wikiReach.isReachEnabled());
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description("Create a new XWiki document or replace the full content of an existing one. Write "
                + "XWiki 2.1 syntax, NOT Markdown - `man xwiki-syntax` is the reference. Overwriting requires "
                + "base_version from get_document. Pass locale to create or overwrite a translation. For "
                + "small targeted changes prefer edit_document.")
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
        return "Authoring";
    }

    @Override
    public String getSummary()
    {
        return "Create a document or replace its entire content.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                write_document replaces the ENTIRE document body with content; objects, attachments
                and rights set on the document are untouched. For targeted changes use edit_document
                instead.
                Write XWiki syntax: "= Heading =", "[[Label>>Target]]" - NOT "# Heading" or
                "[label](url)".
                A translation write (locale="fr") targets that language row exactly, like
                get_document: the default language version must exist first, a new translation needs
                a multilingual wiki, and base_version is per language row - pass the Version read
                with the SAME locale (omit it when creating the translation). Content and title are
                per-row; objects, attachments and rights stay with the page.

            EXAMPLES
                Create:      reference="Sandbox.New", title="Hello",
                             content="= Hello =\\n\\nBody."
                Rewrite:     get_document reference="Sandbox.WebHome" (note its Version line),
                             then write_document reference="Sandbox.WebHome", base_version="4.3",
                             content="= Rewritten =\\n\\nNew body."
                New translation: write_document reference="Sandbox.WebHome", locale="fr",
                             content="= Bonjour =\\n\\nCorps." (no base_version; the default
                             language version must already exist)
                Update translation: get_document reference="Sandbox.WebHome", locale="fr" (note its
                             Version line), then write_document reference="Sandbox.WebHome",
                             locale="fr", base_version="1.1", content="..."
                Conflict:    a "Version conflict" result means the document changed since you read
                             it - re-read it with get_document and retry with the current version.

            SEE ALSO
                man edit_document   Targeted exact-string edits; preferred for small changes.
                man get_document    Read a document's source and current version.
                man xwiki-syntax    XWiki 2.1 syntax reference for writing page source.
                man                 (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
            String rawContent = requireRawContent(args);
            if (rawContent.length() > MCPWriteSupport.MAX_CONTENT_CHARS) {
                throw new IllegalArgumentException("Error: the content exceeds the maximum size ("
                    + MCPWriteSupport.MAX_CONTENT_CHARS + " characters).");
            }
            String content = MCPSourceText.normalizeLineEndings(rawContent);
            String title = PARAMS.parser().string(args, TITLE_PARAM);
            Locale locale = MCPToolSupport.parseLocale(PARAMS.parser().string(args, LOCALE_PARAM), LOCALE_PARAM);
            String baseVersion = PARAMS.parser().string(args, BASE_VERSION_PARAM);
            String comment = PARAMS.parser().string(args, COMMENT_PARAM);
            boolean major = PARAMS.parser().bool(args, MAJOR_PARAM);

            DocumentReference ref = MCPWriteSupport.resolveForEdit(this.documentAccess, reference);

            return writeAndSave(ref, new Request(reference, content, title, locale, baseVersion, comment, major));
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP write_document tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP write_document tool failure details", e);
            return MCPToolSupport.errorResult(MCPWriteSupport.SAVE_FAILED_MESSAGE);
        }
    }

    private McpSchema.CallToolResult writeAndSave(DocumentReference ref, Request request) throws XWikiException
    {
        return MCPWriteSupport.inTargetWiki(this.contextProvider.get(), ref, (xcontext, xdoc) -> {
            MCPWriteSupport.WriteTarget target =
                MCPWriteSupport.resolveWriteTarget(xcontext, xdoc, request.reference(), request.locale());
            if (target.refusal() != null) {
                return target.refusal();
            }
            return saveTarget(xcontext, xdoc, target, ref, request);
        });
    }

    /**
     * Writes the resolved target row (the default document, or the exact translation row) and saves it
     * as a single version. A translation creation copies the page's syntax from the default document
     * before the save - a deliberate divergence from the UI, which leaves the wiki default syntax: a
     * translation is the same page in another language, so it must parse under the same syntax as the
     * content it translates (a new row's syntax is unset, and {@code XWikiDocument#getSyntax} lazily
     * reports the wiki default for an unset syntax, so the explicit copy
     * wins). The default document is never cloned into the translation row: objects are keyed by full
     * name across rows, so a clone would re-save the default document's objects onto the translation.
     *
     * @param xcontext the XWiki context, switched to the target wiki
     * @param defaultDoc the loaded default document, the syntax source of a translation creation
     * @param target the resolved row to write
     * @param ref the resolved document reference
     * @param request the parsed arguments
     * @return the tool result
     * @throws XWikiException when the save fails
     */
    private McpSchema.CallToolResult saveTarget(XWikiContext xcontext, XWikiDocument defaultDoc,
        MCPWriteSupport.WriteTarget target, DocumentReference ref, Request request) throws XWikiException
    {
        XWikiDocument xdoc = target.doc();
        boolean creating = xdoc.isNew();
        String oldVersion = xdoc.getVersion();

        McpSchema.CallToolResult workflowProblem = checkBaseVersion(request, target.locale(), creating, oldVersion);
        if (workflowProblem != null) {
            return workflowProblem;
        }

        boolean titleChanged = request.title() != null && !request.title().equals(xdoc.getTitle());
        if (!creating && request.content().equals(MCPSourceText.normalizeLineEndings(xdoc.getContent()))
            && !titleChanged) {
            return MCPToolSupport.result(NO_CHANGES_PREFIX
                + MCPWriteSupport.currentRowSubject(target.locale()) + ". Nothing was saved.");
        }

        if (creating && target.locale() != null) {
            xdoc.setSyntax(defaultDoc.getSyntax());
        }
        Document apiDoc = new Document(xdoc, xcontext);
        apiDoc.setContent(request.content());
        if (titleChanged) {
            apiDoc.setTitle(request.title());
        }
        apiDoc.save(MCPWriteSupport.buildComment(request.comment(), creating, REPLACED_CONTENT),
            MCPWriteSupport.isMinorEdit(creating, request.major()));

        return MCPToolSupport.result(buildSuccessResult(new Outcome(ref, target.locale(), creating,
            request.title() != null, titleChanged, oldVersion, apiDoc.getVersion(), syntaxIdOf(xdoc)),
            request.content()));
    }

    /**
     * Checks the written row's state against the {@code base_version} workflow before anything is
     * written: an overwrite must carry the version the agent read (so the agent has provably read the
     * row first), a creation must not carry one, and a stale version is refused. The lock is row-scoped:
     * a translation write compares against the translation row's own version, and its messages steer
     * the agent to read with the same locale.
     *
     * <p>The version check is best-effort: a concurrent save landing between this check and the save
     * below can still win. It protects the agent's read-modify-write loop against stale reads, not
     * transactional integrity.</p>
     *
     * @param request the parsed arguments
     * @param locale the written translation row's locale, or {@code null} for a default-language write
     * @param creating whether the written row does not exist yet
     * @param currentVersion the written row's current version
     * @return an error result describing the problem, or {@code null} when the save may proceed
     */
    private McpSchema.CallToolResult checkBaseVersion(Request request, Locale locale, boolean creating,
        String currentVersion)
    {
        if (creating) {
            if (request.baseVersion() != null) {
                return MCPToolSupport.errorResult(locale == null
                    ? MCPWriteSupport.missingDocumentBaseVersionError(request.reference())
                    : MCPWriteSupport.missingTranslationBaseVersionError(request.reference(), locale));
            }
            return null;
        }
        if (request.baseVersion() == null) {
            return MCPToolSupport.errorResult(alreadyExistsMessage(request.reference(), locale));
        }
        if (!request.baseVersion().equals(currentVersion)) {
            String subject = locale == null ? MCPWriteSupport.DOCUMENT_SUBJECT
                : MCPWriteSupport.translationSubject(locale);
            return MCPToolSupport.errorResult(
                MCPWriteSupport.versionConflictError(subject, currentVersion, request.baseVersion(), RETRY));
        }
        return null;
    }

    /**
     * Formats the read-first refusal for an overwrite sent without {@code base_version}. The
     * translation variant names the row and tells the agent to read with the same locale, so the
     * version it fetches is the row's own.
     *
     * @param reference the original reference string, echoed neutralized in the message
     * @param locale the written translation row's locale, or {@code null} for a default-language write
     * @return the agent-facing error message
     */
    private static String alreadyExistsMessage(String reference, Locale locale)
    {
        if (locale == null) {
            return DOCUMENT_QUOTE_PREFIX + MCPTextGuards.fragment(reference) + QUOTE
                + " already exists. To overwrite it, first read "
                + "it with get_document and pass the base_version it shows. For small changes prefer "
                + "edit_document.";
        }
        String localeDisplay = MCPToolSupport.stripLineBreaks(locale.toString());
        return "The " + localeDisplay + " translation of " + QUOTE + MCPTextGuards.fragment(reference)
            + QUOTE + " already exists. To "
            + "overwrite it, first read it with get_document and locale=" + QUOTE + localeDisplay + QUOTE
            + ", and pass the base_version it shows. For small changes prefer edit_document.";
    }

    /**
     * Reads the {@code content} argument without the support layer's trimming: the value is the complete
     * document source, so leading and trailing whitespace (e.g. a final newline) is significant and must
     * be preserved byte-for-byte.
     *
     * @param args the tool call arguments
     * @return the raw content string, never {@code null} (an empty string is a valid empty body)
     * @throws IllegalArgumentException with the agent-facing message if the value is absent or not a string
     */
    private static String requireRawContent(Map<String, Object> args)
    {
        Object value = args.get(CONTENT_PARAM);
        if (value == null) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + CONTENT_PARAM
                + "' parameter is required.");
        }
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + CONTENT_PARAM
                + "' parameter must be a string.");
        }
        return str;
    }

    /**
     * Builds the success text of a save, from the outcome carrier plus the saved content (consulted
     * only for the script-macro note, so the carrier stays a rendering-only value).
     *
     * @param outcome the save outcome
     * @param savedContent the final saved content
     * @return the success text
     */
    private String buildSuccessResult(Outcome outcome, String savedContent)
    {
        String canonicalRef = this.serializer.serialize(outcome.ref());
        String target = outcome.locale() == null ? MCPWriteSupport.DOCUMENT_NOUN
            : MCPWriteSupport.translationSubject(outcome.locale()) + " of ";
        StringBuilder sb = new StringBuilder();
        if (outcome.creating()) {
            sb.append("Created ").append(target).append(canonicalRef).append(PERIOD).append(NEW_LINE);
            sb.append(MCPWriteSupport.VERSION_PREFIX).append(outcome.newVersion()).append(NEW_LINE);
            sb.append("Syntax: ").append(outcome.syntaxId());
            if (outcome.titleGiven()) {
                sb.append(NEW_LINE).append("Title set.");
            }
        } else {
            sb.append("Overwrote ").append(target).append(canonicalRef).append(PERIOD).append(NEW_LINE);
            sb.append(MCPWriteSupport.VERSION_PREFIX).append(outcome.oldVersion()).append(" -> ")
                .append(outcome.newVersion());
            if (outcome.titleChanged()) {
                sb.append(NEW_LINE).append("Title updated.");
            }
        }
        String urlLine = MCPWriteSupport.buildReviewLine(this.documentAccessBridge, this.logger, outcome.ref(),
            outcome.creating(), outcome.oldVersion(), outcome.newVersion(), outcome.locale());
        if (urlLine != null) {
            sb.append(NEW_LINE).append(urlLine);
        }
        String scriptNote = MCPWriteSupport.scriptMacroNote(savedContent);
        if (scriptNote != null) {
            sb.append(NEW_LINE).append(scriptNote);
        }
        return sb.toString();
    }

    /**
     * Returns the syntax identifier of the given document, for the creation result (the agent learns
     * which syntax the wiki assigned to the new document).
     *
     * @param xdoc the document
     * @return the syntax identifier, or {@code "unknown"} when the document has no syntax set
     */
    private static String syntaxIdOf(XWikiDocument xdoc)
    {
        var syntax = xdoc.getSyntax();
        return syntax != null ? syntax.toIdString() : "unknown";
    }

    /**
     * The parsed and validated arguments of a write call, bundled so the target resolution, the
     * base-version check and the save share one immutable view of the request.
     *
     * @param reference the original reference string, echoed in error messages
     * @param content the complete new source, LF-normalized
     * @param title the requested title, or {@code null} when not requested
     * @param locale the validated translation locale, or {@code null} for a default-language write
     * @param baseVersion the version the agent read, or {@code null} when none was given
     * @param comment the agent-supplied version comment, or {@code null}
     * @param major whether the caller asked for a major version
     * @version $Id$
     */
    private record Request(String reference, String content, String title, Locale locale, String baseVersion,
        String comment, boolean major)
    {
    }

    /**
     * The carrier of everything the success result needs after a save, so that result rendering is a
     * pure function of this value.
     *
     * @param ref the resolved document reference, used for the canonical display reference and the
     *     review URL
     * @param locale the written translation row's locale, or {@code null} for a default-language write
     * @param creating whether the row was created rather than overwritten
     * @param titleGiven whether a title argument was supplied (relevant only on create)
     * @param titleChanged whether the title actually changed
     * @param oldVersion the row's version before the save
     * @param newVersion the row's version after the save
     * @param syntaxId the saved row's syntax identifier, shown on create
     * @version $Id$
     */
    private record Outcome(DocumentReference ref, Locale locale, boolean creating, boolean titleGiven,
        boolean titleChanged, String oldVersion, String newVersion, String syntaxId)
    {
    }
}
