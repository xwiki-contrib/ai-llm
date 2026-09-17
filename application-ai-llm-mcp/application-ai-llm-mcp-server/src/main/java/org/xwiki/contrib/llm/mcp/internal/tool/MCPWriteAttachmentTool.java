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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.attachment.validation.AttachmentValidator;
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
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that attaches a file to a document or overwrites an existing attachment's content (the prior
 * content stays in the attachment's own history). Text content is the mainline (stored UTF-8);
 * {@code content_base64} carries small binary files.
 *
 * <p>This is a default tool bundled with the MCP server module. The {@code base_version} discipline
 * mirrors {@code write_object}: required when the document exists (an attachment save writes a document
 * revision), refused when creating one - the document is then created carrying the attachment.
 * Rights- and configuration-bearing documents are refused outright (the sensitive-document denylist
 * shared with the other destructive tools), and every upload is checked against the wiki's attachment
 * policy (maximum size, mimetype lists) before anything is saved.</p>
 *
 * <p>Resolution and authorization both go through {@link MCPDocumentAccess} for the edit right before
 * the document is loaded, so the per-wiki space filter is applied and the existence of a protected
 * document is never leaked. The attachment is staged on the tool's own clone of the loaded document (the
 * loaded instance may be the store cache's, so it is never mutated in place) and the save goes through
 * {@link com.xpn.xwiki.api.Document} so author attribution and save-time rights are applied.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPWriteAttachmentTool.TOOL_ID)
@Singleton
public class MCPWriteAttachmentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "write_attachment";

    private static final String REFERENCE_PARAM = "reference";

    private static final String FILENAME_PARAM = "filename";

    private static final String CONTENT_PARAM = "content";

    private static final String CONTENT_BASE64_PARAM = "content_base64";

    private static final String BASE_VERSION_PARAM = "base_version";

    private static final String COMMENT_PARAM = "comment";

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    private static final String ON_DOCUMENT_INFIX = " on document ";

    private static final String AS_ATTACHMENT_VERSION_INFIX = " as attachment version ";

    /**
     * Shared tail of the refusal messages, homed in {@link MCPAttachmentWriteSupport} so the tool's
     * refusals and the validation refusals share one sentence.
     */
    private static final String NOTHING_SAVED = MCPAttachmentWriteSupport.NOTHING_SAVED;

    /**
     * The refusal for a call carrying neither or both content forms: the two are exclusive by design,
     * so the agent picks the one matching the file.
     */
    private static final String NEED_EXACTLY_ONE_CONTENT = "Error: provide exactly one of '" + CONTENT_PARAM
        + "' (text) or '" + CONTENT_BASE64_PARAM + "' (small binary file)." + NOTHING_SAVED;

    /**
     * The refusal for a present-but-empty {@code content_base64}: zero bytes are legitimate, but the
     * explicit-empty spelling of the tool is {@code content=""} - steering there beats a misleading
     * exactly-one-of message.
     */
    private static final String EMPTY_BASE64_ERROR = MCPToolSupport.ERROR_PREFIX + CONTENT_BASE64_PARAM
        + "' is empty. To create an empty file, pass " + CONTENT_PARAM + "=\"\" instead." + NOTHING_SAVED;

    /**
     * Shared tail of the over-cap refusals: this tool is deliberately the small-file door (large base64
     * tool arguments are corrupted or truncated by some MCP clients), so bigger files go through the
     * wiki UI.
     */
    private static final String OVER_CAP_SUFFIX = "' is longer than " + MCPWriteSupport.MAX_CONTENT_CHARS
        + " characters. write_attachment is the small-file door; upload larger files through the wiki UI "
        + "(the document's Attachments tab)." + NOTHING_SAVED;

    /**
     * The character encoding text content is stored with, recorded on the attachment.
     */
    private static final String UTF_8 = "UTF-8";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description so
     * no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPWriteAttachmentTool::params);

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
     * The attachment validator, resolved lazily: the default implementation ships with the standard
     * flavor as an installed extension, so its availability is a per-wiki runtime fact - a wiki without
     * it fails CLOSED at call time (see {@link MCPAttachmentWriteSupport#validateOrRefuse}) instead of
     * failing this component's instantiation.
     */
    @Inject
    private Provider<AttachmentValidator> attachmentValidatorProvider;

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example and the cross-wiki
     * sentence in the {@code reference} description only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document to attach to, e.g. \"Sandbox.WebHome\" or \""
            + (crossWiki ? "xwiki:" : "") + "Help.Media\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(FILENAME_PARAM, "Filename of the attachment, e.g. \"notes.txt\". Attaching "
                + "over an existing filename replaces its content (prior versions stay in the attachment's "
                + "history).")
            .string(CONTENT_PARAM, "The file content as TEXT, stored UTF-8 verbatim (line endings and "
                + "trailing whitespace preserved) - the mainline for text files. An explicitly empty "
                + "string creates an empty file. Exactly one of content and content_base64 is required.")
            .string(CONTENT_BASE64_PARAM, "The file content as base64, for SMALL binary files only; larger "
                + "files belong in the wiki UI. Exactly one of content and content_base64 is required.")
            .string(BASE_VERSION_PARAM, "The document version you read (shown by get_document and "
                + "get_attachment). Required when the document already exists - an attachment save writes "
                + "a document revision; omit it when creating a new document. The save is refused if the "
                + "document has changed since you read it.")
            .string(COMMENT_PARAM, "Version comment shown in the document history. Stored prefixed with "
                + "[AI]. Default: a generic [AI] comment.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        MCPToolSupport schema = PARAMS.advertised(this.wikiReach.isReachEnabled());
        return McpSchema.Tool.builder(TOOL_ID, schema.inputSchema())
            .description("Attach a file to a document, or overwrite an existing attachment (prior versions "
                + "are kept in the attachment's history). Text content is the mainline, stored UTF-8; "
                + "content_base64 carries SMALL binary files. Updating an existing document requires "
                + "base_version; omit it to create the document carrying the attachment.")
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
        return "Attach a file to a document, or overwrite an existing attachment.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                Pass EXACTLY one of content (text, stored UTF-8 verbatim - line endings and
                trailing whitespace preserved; content="" creates an empty file) or
                content_base64 (base64, for small binary files). This tool is the
                small-file door: some MCP clients corrupt or truncate large base64 tool
                arguments, and files beyond the character cap belong in the wiki UI (the
                document's Attachments tab).

                base_version is required whenever the document exists - even when the attachment
                is NEW, because attaching writes a document revision. Read the document first
                (get_document shows the version) and pass what you read. Omit base_version when
                the document does not exist yet: it is then created carrying the attachment.

                Attaching over an existing filename replaces the content; the prior content stays
                in the attachment's own history. Every upload is checked against the wiki's
                attachment policy (maximum size, mimetype lists) before anything is saved. Pages
                that define access rights or wiki configuration are refused.

            EXAMPLES
                Attach a text file:  reference="Sandbox.WebHome", base_version="3.2",
                                     filename="notes.txt", content="line one\\nline two"
                Small binary:        reference="Sandbox.WebHome", base_version="3.2",
                                     filename="pixel.png", content_base64="iVBORw0KGgo..."
                Overwrite:           reference="Sandbox.WebHome", base_version="3.3",
                                     filename="notes.txt", content="updated text"

            SEE ALSO
                man get_attachment      Read an attachment's content or metadata (and its version).
                man get_document        Lists a document's attachments and shows the base_version.
                man delete_attachment   Delete an attachment (moves it to the attachment recycle bin).
                man                     (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            Request parsed = parseRequest(args);
            validateFilename(parsed.filename());
            boolean text = parsed.content() != null;
            byte[] bytes = decodeBytes(parsed.content(), parsed.contentBase64());

            DocumentReference ref = MCPWriteSupport.resolveForEdit(this.documentAccess, parsed.reference());

            return attachAndSave(ref, parsed, bytes, text);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        } catch (XWikiException e) {
            this.logger.warn("MCP write_attachment tool failed: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP write_attachment tool failure details", e);
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
            PARAMS.parser().requireString(args, FILENAME_PARAM),
            optionalRawContent(args),
            PARAMS.parser().stringOrEmpty(args, CONTENT_BASE64_PARAM),
            PARAMS.parser().string(args, BASE_VERSION_PARAM),
            PARAMS.parser().string(args, COMMENT_PARAM));
    }

    /**
     * Reads the {@code content} argument raw, mirroring {@code write_document}'s raw content read: the
     * shared accessors trim string values, and trimming would alter the saved bytes (trailing newlines
     * and edge whitespace ARE file content). Line endings are deliberately NOT normalized either - a
     * file's CRLF bytes are preserved as sent. A present-but-empty value is kept: an explicit
     * {@code content=""} creates a zero-byte attachment (a legitimate marker file), following the
     * explicit-empty-clears precedent of the title parameter.
     *
     * @param args the tool call arguments
     * @return the verbatim content, or {@code null} when the argument is absent
     * @throws IllegalArgumentException with the agent-facing message when the value is not a string
     */
    private static String optionalRawContent(Map<String, Object> args)
    {
        Object value = args.get(CONTENT_PARAM);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + CONTENT_PARAM
                + "' parameter must be a string.");
        }
        return str;
    }

    /**
     * Refuses a filename carrying a path or parameter separator (following the platform upload action's
     * rule), any ISO control character (which could forge lines in listings and echoes), or any Unicode
     * bidirectional formatting character (which could reorder how the stored name DISPLAYS - a name
     * whose bytes end {@code .exe} rendering as {@code .txt}). The platform's own attach call would
     * silently strip a path prefix at the FIRST separator only, which is surprising enough that an
     * explicit refusal teaches better than sanitizing.
     *
     * @param filename the requested filename
     * @throws IllegalArgumentException with the agent-facing message when the filename is invalid
     */
    private static void validateFilename(String filename)
    {
        boolean separator =
            filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0 || filename.indexOf(';') >= 0;
        if (separator
            || filename.chars().anyMatch(c -> Character.isISOControl(c) || isBidiFormatting(c))) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + FILENAME_PARAM
                + "' must not contain \"/\", \"\\\", \";\", control characters or directional "
                + "formatting characters: " + QUOTE + MCPTextGuards.fragment(filename) + QUOTE + PERIOD
                + NOTHING_SAVED);
        }
    }

    /**
     * Tests for the bidirectional formatting characters refused in filenames: the embeddings and
     * overrides U+202A..U+202E, the isolates U+2066..U+2069 and the marks U+200E/U+200F. Deliberately
     * NOT the whole format category: ZWJ (U+200D) and ZWNJ (U+200C) are legitimate in emoji and
     * Indic/Persian filenames and keep passing.
     *
     * @param codePoint the code point to test
     * @return whether the code point is a bidirectional formatting character
     */
    private static boolean isBidiFormatting(int codePoint)
    {
        if (codePoint >= 0x202A && codePoint <= 0x202E) {
            return true;
        }
        if (codePoint >= 0x2066 && codePoint <= 0x2069) {
            return true;
        }
        return codePoint == 0x200E || codePoint == 0x200F;
    }

    /**
     * Resolves the exactly-one-of content forms into the attachment bytes: text becomes UTF-8 bytes
     * verbatim (a zero-byte or whitespace-only file included), base64 is strictly decoded, and both
     * forms are capped BEFORE any decoding or locking (pure computation on the arguments). Exclusivity
     * is decided on PRESENCE: a present-but-empty {@code content_base64} is refused with a pointer at
     * the {@code content=""} spelling of an empty file rather than folded into absence.
     *
     * @param content the verbatim text content, or {@code null} when absent
     * @param contentBase64 the trimmed base64 content (possibly empty), or {@code null} when absent
     * @return the attachment bytes
     * @throws IllegalArgumentException with the agent-facing message when the forms are not exclusive,
     *     a cap is exceeded or the base64 is invalid or empty
     */
    private static byte[] decodeBytes(String content, String contentBase64)
    {
        if ((content == null) == (contentBase64 == null)) {
            throw new IllegalArgumentException(NEED_EXACTLY_ONE_CONTENT);
        }
        if (content != null) {
            if (content.length() > MCPWriteSupport.MAX_CONTENT_CHARS) {
                throw new IllegalArgumentException(
                    MCPToolSupport.ERROR_PREFIX + CONTENT_PARAM + OVER_CAP_SUFFIX);
            }
            return content.getBytes(StandardCharsets.UTF_8);
        }
        if (contentBase64.isEmpty()) {
            throw new IllegalArgumentException(EMPTY_BASE64_ERROR);
        }
        if (contentBase64.length() > MCPWriteSupport.MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                MCPToolSupport.ERROR_PREFIX + CONTENT_BASE64_PARAM + OVER_CAP_SUFFIX);
        }
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(MCPToolSupport.ERROR_PREFIX + CONTENT_BASE64_PARAM
                + "' is not valid base64." + NOTHING_SAVED, e);
        }
    }

    /**
     * Runs the guarded write inside the target wiki: the sensitive-document refusal fires first (before
     * the version check, so a denylisted target is refused regardless of version), then the
     * {@code base_version} discipline, then the attachment is staged on the tool's own clone, validated
     * against the wiki's attachment policy, and saved as a single new document version.
     *
     * @param ref the resolved and authorized document reference
     * @param request the parsed arguments
     * @param bytes the attachment bytes
     * @param text whether the content was given as text (stored UTF-8)
     * @return the tool result
     * @throws XWikiException when loading or saving fails
     */
    private McpSchema.CallToolResult attachAndSave(DocumentReference ref, Request request, byte[] bytes,
        boolean text) throws XWikiException
    {
        return MCPWriteSupport.inTargetWiki(this.contextProvider.get(), ref, (xcontext, xdoc) -> {
            boolean creating = xdoc.isNew();
            String oldVersion = xdoc.getVersion();

            if (MCPWriteSupport.isSensitiveDocument(xcontext, ref, this.localSerializer)) {
                return sensitiveRefusal(ref);
            }
            McpSchema.CallToolResult versionProblem =
                checkBaseVersion(request.reference(), request.baseVersion(), creating, oldVersion);
            if (versionProblem != null) {
                return versionProblem;
            }

            XWikiDocument editable = xdoc.clone();
            boolean updatingAttachment = editable.getExactAttachment(request.filename()) != null;
            XWikiAttachment attachment = MCPAttachmentWriteSupport.setAttachmentContent(editable,
                request.filename(), bytes, xcontext);

            McpSchema.CallToolResult policyRefusal = MCPAttachmentWriteSupport
                .validateOrRefuse(this.attachmentValidatorProvider, attachment, xcontext);
            if (policyRefusal != null) {
                return policyRefusal;
            }

            attachment.resetMimeType(xcontext);
            if (text) {
                attachment.setCharset(UTF_8);
            } else {
                // A text-to-binary overwrite must not keep the previous content's stale UTF-8 charset.
                attachment.setCharset(null);
            }
            String comment = MCPWriteSupport.buildComment(request.comment(), creating,
                changeSummary(updatingAttachment, request.filename()));
            attachment.setComment(comment);

            Document apiDoc = new Document(editable, xcontext);
            try {
                apiDoc.save(comment, MCPWriteSupport.isMinorEdit(creating, false));
            } catch (XWikiException e) {
                McpSchema.CallToolResult rerouted = MCPWriteSupport.reroutedSaveFailure(e, xcontext, ref,
                    null, creating, alreadyExistsMessage(request.reference()), this.logger);
                if (rerouted != null) {
                    return rerouted;
                }
                throw e;
            }

            // The echoed attachment facts must come from the SAVED instance: on an overwrite the real
            // store bumps the attachment version (updateContentArchive) on the api wrapper's INTERNAL
            // clone - the instance actually saved - not on the staged one, so reading the staged
            // instance after the save would echo the pre-bump version.
            XWikiAttachment saved = savedAttachment(xcontext, ref, request.filename(), attachment);

            return MCPToolSupport.result(buildSuccessResult(ref, creating, updatingAttachment, oldVersion,
                apiDoc.getVersion(), saved, xcontext));
        });
    }

    /**
     * Re-reads the saved attachment for the result echo, so the echoed version, size, mimetype AND the
     * download URL all come from the instance the store actually persisted (the document cache holds it
     * after the save) and can never disagree with each other. Falls back to the staged instance rather
     * than failing a successful save when the re-read misses.
     *
     * @param xcontext the XWiki context, still switched to the target wiki
     * @param ref the saved document's reference
     * @param filename the attachment filename
     * @param staged the staged attachment instance, the fallback
     * @return the saved attachment, or the staged one when the re-read misses
     */
    private XWikiAttachment savedAttachment(XWikiContext xcontext, DocumentReference ref, String filename,
        XWikiAttachment staged)
    {
        try {
            XWikiAttachment saved =
                xcontext.getWiki().getDocument(ref, xcontext).getExactAttachment(filename);
            if (saved != null) {
                return saved;
            }
        } catch (XWikiException e) {
            this.logger.debug("MCP write_attachment tool could not re-read the saved attachment", e);
        }
        return staged;
    }

    /**
     * Formats the refusal for a rights- or configuration-bearing target document, mirroring the other
     * destructive tools' wording.
     *
     * @param ref the resolved document reference
     * @return the refusal result
     */
    private McpSchema.CallToolResult sensitiveRefusal(DocumentReference ref)
    {
        String url = MCPWriteSupport.safeDocumentUrl(this.documentAccessBridge, this.logger, ref, null);
        return MCPToolSupport.errorResult("Refusing to attach to " + QUOTE
            + MCPTextGuards.fragment(this.serializer.serialize(ref)) + QUOTE
            + ": this page defines access rights or wiki configuration. If you really intend to change "
            + "it, do it manually in the wiki UI" + (url != null ? ": " + url : PERIOD));
    }

    /**
     * Checks the document's state against the {@code base_version} workflow before anything is written,
     * mirroring {@code write_object}: an update must carry the version the agent read (an attachment
     * save writes a document revision, so this holds even for a NEW attachment), a creation must not
     * carry one, and a stale version is refused.
     *
     * <p>The check runs inside the per-document lock of {@link MCPWriteSupport#inTargetWiki}, serialized with
     * every other MCP write to this document on this server; only a save made outside the MCP server (wiki
     * UI, REST) or on another cluster node can still land between the check and the save. It protects the
     * agent's read-modify-write loop, not cross-node transactional integrity.</p>
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
            return MCPToolSupport.errorResult(alreadyExistsMessage(reference));
        }
        if (!baseVersion.equals(currentVersion)) {
            return MCPToolSupport.errorResult(
                MCPWriteSupport.versionConflictError(MCPWriteSupport.DOCUMENT_SUBJECT, currentVersion,
                    baseVersion, "retry."));
        }
        return null;
    }

    /**
     * Formats the read-first refusal for an update sent without {@code base_version}. Also the
     * re-routed result of a creating save that lost a create race (the document provably exists by the
     * time the failure is handled), so the two paths can never drift apart in wording.
     *
     * @param reference the original reference string, echoed neutralized in the message
     * @return the agent-facing error message
     */
    private static String alreadyExistsMessage(String reference)
    {
        return "Document " + QUOTE + MCPTextGuards.fragment(reference) + QUOTE + " already exists. "
            + "First read it with get_document and pass the base_version it shows, so the attachment "
            + "change is based on a recent read.";
    }

    /**
     * Builds this tool's generated update description for the version comment (used when the agent
     * supplied no comment on a non-creating save).
     *
     * @param updatingAttachment whether the attachment already existed
     * @param filename the attachment filename
     * @return the update description
     */
    private static String changeSummary(boolean updatingAttachment, String filename)
    {
        String name = MCPTextGuards.fragment(filename);
        return updatingAttachment ? "updated attachment " + name : "attached " + name;
    }

    /**
     * Builds the success result: what happened to which attachment (with its stored size, mimetype and
     * attachment version read back after the save), the document version (transition) with the
     * {@code base_version} hint, the download URL and the review line.
     *
     * @param ref the saved document's reference
     * @param creatingDocument whether the document itself was created
     * @param updatingAttachment whether the attachment already existed
     * @param oldVersion the document version before the save
     * @param newVersion the document version after the save
     * @param attachment the saved attachment
     * @param xcontext the XWiki context, for the mimetype and download URL
     * @return the result text
     */
    private String buildSuccessResult(DocumentReference ref, boolean creatingDocument,
        boolean updatingAttachment, String oldVersion, String newVersion, XWikiAttachment attachment,
        XWikiContext xcontext)
    {
        String canonicalRef = MCPToolSupport.stripLineBreaks(this.serializer.serialize(ref));
        String name = QUOTE + MCPTextGuards.fragment(attachment.getFilename()) + QUOTE;
        String sizeAndType = " (" + MCPAttachmentSupport.humanSize(attachment.getLongSize()) + ", "
            + MCPTextGuards.fragment(attachment.getMimeType(xcontext)) + ")";
        String attachmentVersion = MCPToolSupport.stripLineBreaks(attachment.getVersion());
        StringBuilder sb = new StringBuilder();
        if (creatingDocument) {
            sb.append("Created document ").append(canonicalRef).append(" with attachment ").append(name)
                .append(sizeAndType).append(AS_ATTACHMENT_VERSION_INFIX).append(attachmentVersion)
                .append(PERIOD).append(NEW_LINE);
            sb.append(MCPWriteSupport.VERSION_PREFIX).append(newVersion)
                .append(MCPWriteSupport.baseVersionHint(newVersion));
        } else {
            if (updatingAttachment) {
                sb.append("Updated attachment ").append(name).append(sizeAndType).append(" to version ")
                    .append(attachmentVersion).append(ON_DOCUMENT_INFIX).append(canonicalRef)
                    .append(" (previous revisions are kept in its history).");
            } else {
                sb.append("Attached ").append(name).append(sizeAndType).append(AS_ATTACHMENT_VERSION_INFIX)
                    .append(attachmentVersion).append(ON_DOCUMENT_INFIX).append(canonicalRef).append(PERIOD);
            }
            sb.append(NEW_LINE).append(MCPWriteSupport.VERSION_PREFIX).append(oldVersion).append(" -> ")
                .append(newVersion).append(MCPWriteSupport.baseVersionHint(newVersion));
        }
        String downloadUrl = safeDownloadUrl(attachment, xcontext);
        if (downloadUrl != null) {
            sb.append(NEW_LINE).append("Download: ").append(downloadUrl);
        }
        String urlLine = MCPWriteSupport.buildReviewLine(this.documentAccessBridge, this.logger, ref,
            creatingDocument, oldVersion, newVersion);
        if (urlLine != null) {
            sb.append(NEW_LINE).append(urlLine);
        }
        return sb.toString();
    }

    /**
     * Builds the external download URL of the saved attachment, returning {@code null} instead of
     * propagating a URL-building failure: the line is a convenience, never worth failing the save's
     * result over.
     *
     * @param attachment the saved attachment, owned by the saved document
     * @param xcontext the XWiki context
     * @return the download URL, or {@code null} when it could not be built
     */
    private String safeDownloadUrl(XWikiAttachment attachment, XWikiContext xcontext)
    {
        try {
            return attachment.getDoc().getExternalAttachmentURL(attachment.getFilename(), "download", xcontext);
        } catch (Exception e) {
            this.logger.debug("MCP write_attachment tool could not build the download URL", e);
            return null;
        }
    }

    /**
     * The parsed and validated arguments of one call, bundled so the guard, stage and format steps share
     * one immutable view of the request.
     *
     * @param reference the raw {@code reference} argument
     * @param filename the requested attachment filename
     * @param content the verbatim text content (possibly empty), or {@code null} when absent
     * @param contentBase64 the trimmed base64 content (possibly empty), or {@code null} when absent
     * @param baseVersion the version the agent read, or {@code null} when none was given
     * @param comment the agent-supplied version comment, or {@code null}
     * @version $Id$
     */
    private record Request(String reference, String filename, String content, String contentBase64,
        String baseVersion, String comment)
    {
    }
}
