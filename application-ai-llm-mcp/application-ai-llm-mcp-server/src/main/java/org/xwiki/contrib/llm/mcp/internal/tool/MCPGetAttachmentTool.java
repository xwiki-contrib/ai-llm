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
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that reads one attachment of an XWiki document for an agent: text attachments (SVG included)
 * are inlined under the shared output budget with offset continuation, PDF and office documents return
 * their Tika-extracted text windowed the same way, images up to the inline cap are returned as viewable
 * MCP image content, and every other type returns the metadata header with a download URL instead of
 * content.
 *
 * <p>The attachment is addressed by its EXACT stored filename (as listed by {@code get_document}'s
 * {@code Attachments:} header line); the platform's fuzzy filename fallback is deliberately not used, so
 * a miss teaches the real names instead of silently serving a similarly-named file.</p>
 *
 * <p>Resolution and authorization both go through {@link MCPDocumentAccess#resolveAndAuthorize(String,
 * Right)} for {@link Right#VIEW} before the document is loaded, so the per-wiki space filter is applied
 * and the existence of a protected document is never leaked.</p>
 *
 * @version $Id$
 * @since 0.10
 */
@Component
@Named(MCPGetAttachmentTool.TOOL_ID)
@Singleton
public class MCPGetAttachmentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "get_attachment";

    private static final String REFERENCE_PARAM = "reference";

    private static final String FILENAME_PARAM = "filename";

    private static final String OFFSET_PARAM = "offset";

    private static final String METADATA_PARAM = "metadata";

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    /**
     * The agent-facing message shared by the existence-check and load failures, mirroring the
     * {@code get_document} wording so a broken document reads the same through both tools.
     */
    private static final String COULD_NOT_READ_PREFIX = "Could not read the document ";

    /**
     * The agent-facing message of a failed content read; the root cause stays in the server logs.
     */
    private static final String CONTENT_READ_ERROR = "Could not read the attachment content.";

    /**
     * The offset-assignment fragment closing both continuation notes, directly followed by the offset
     * value.
     */
    private static final String OFFSET_EQUALS = OFFSET_PARAM + "=";

    /**
     * Opens the truncation note of a text read cut at a line boundary at the output budget, completed by
     * the continuation offset; the wording follows the {@code get_document} continuation note.
     */
    private static final String CONTINUATION_PREFIX = "Output truncated at the ~"
        + MCPSourceText.MAX_OUTPUT_TOKENS + "-token cap; continue with " + OFFSET_EQUALS;

    /**
     * Opens the truncation note of a window whose first line alone exceeds the output budget and was cut
     * MID-LINE, completed by the continuation offset - which names the NEXT line, the only line-addressed
     * continuation an offset parameter can express (the cut line's tail is drained, never buffered).
     */
    private static final String LINE_CUT_CONTINUATION_PREFIX = "Output truncated MID-LINE at the ~"
        + MCPSourceText.MAX_OUTPUT_TOKENS + "-token cap (single line longer than the budget); the rest of "
        + "this line is not retrievable by offset - continue with the next line at " + OFFSET_EQUALS;

    /**
     * Body of a text read whose emitted content is empty (an empty stored file).
     */
    private static final String NO_CONTENT_BODY = "Attachment has no content.";

    /**
     * Shared tail of the degrade bodies pointing the agent at the header's Download URL.
     */
    private static final String DOWNLOAD_POINTER_TAIL = "; use the Download URL above.";

    /**
     * Body of a graceful extraction degrade: the document could not be parsed (encrypted, corrupt, no
     * parser available). The root cause stays in the server logs.
     */
    private static final String EXTRACTION_FAILED_BODY = "Text extraction failed" + DOWNLOAD_POINTER_TAIL;

    /**
     * Body of an extraction that parsed fine but yielded no text (a scanned or image-only document).
     */
    private static final String NO_EXTRACTED_TEXT_BODY = "No text could be extracted" + DOWNLOAD_POINTER_TAIL;

    /**
     * The inline-cap phrase shared by the two over-cap image bodies, so their naming of the cap cannot
     * drift apart.
     */
    private static final String INLINE_CAP_PHRASE = " the 2 MB inline cap";

    /**
     * Tail of the over-cap image body whose head names the declared size ({@code Image is 3.4 MB, ...}).
     */
    private static final String IMAGE_OVER_CAP_TAIL = ", above" + INLINE_CAP_PHRASE + DOWNLOAD_POINTER_TAIL;

    /**
     * Body of the over-cap degrade of an image whose declared size was small or unknown but whose
     * stream yielded more than the cap: no trustworthy size can be named.
     */
    private static final String IMAGE_STREAM_OVER_CAP_BODY =
        "Image is larger than" + INLINE_CAP_PHRASE + DOWNLOAD_POINTER_TAIL;

    /**
     * The text-block line announcing that the image content block follows it in the result.
     */
    private static final String IMAGE_MARKER_BODY = "Image content follows.";

    /**
     * Opens the note appended to the FINAL extraction window when the extractor's own character cap was
     * reached: the extracted text ends there but the document continues, which the window itself cannot
     * show (the cut is silent - see {@link MCPAttachmentSupport#extractionCap()}). Completed by the cap
     * value and {@link #EXTRACTION_CAPPED_NOTE_SUFFIX}.
     */
    private static final String EXTRACTION_CAPPED_NOTE_PREFIX = "Extraction was capped at ~";

    /**
     * Closes the extraction-cap note opened by {@link #EXTRACTION_CAPPED_NOTE_PREFIX}.
     */
    private static final String EXTRACTION_CAPPED_NOTE_SUFFIX =
        " chars; the document continues beyond this point - use the Download URL above for the full file.";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description
     * so no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPGetAttachmentTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private EntityReferenceSerializer<String> serializer;

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
        String referenceDescription = "The document holding the attachment, e.g. \"Help.GettingStarted\" or \""
            + (crossWiki ? "xwiki:" : "") + "Sandbox.WebHome\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(FILENAME_PARAM, "Exact attachment filename, as listed by get_document.")
            .integer(OFFSET_PARAM, "Line offset into text content for continuing a truncated read. Default 0.")
            .bool(METADATA_PARAM, "Return only the metadata header and download URL, skipping content. "
                + "Default false.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.advertised(this.wikiReach.isReachEnabled()).inputSchema())
            .description("Read an attachment from a document. Text attachments (text/*, JSON, XML, YAML, "
                + "SVG, scripts) are returned inline under the ~" + MCPSourceText.MAX_OUTPUT_TOKENS + "-token "
                + "output budget, with offset continuation for longer files; PDF and office documents "
                + "return their extracted text (formatting not preserved); images up to 2 MB (PNG, JPEG, "
                + "GIF, WebP) are returned as viewable image content; every other type returns the metadata "
                + "header with a download URL instead of content. The filename must match exactly, as "
                + "listed by get_document's Attachments header line.")
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
        return "Read an attachment's content or metadata from a document.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                The filename must match EXACTLY: pick it from the Attachments header line of a
                get_document read. There is no fuzzy matching - a near-miss is refused with the
                names that do exist on the document.

                Text attachments (text/*, JSON, XML, YAML, SVG, scripts) are inlined under the
                output token budget; a longer file is cut at a line boundary with a continuation
                note - pass its offset to read the next window.

                PDF, RTF and office documents (Word, Excel, PowerPoint, OpenDocument) return
                their text EXTRACTED by the wiki's document parser: formatting, layout and
                embedded images are lost. The extracted text is windowed under the same budget
                with the same offset continuation. An encrypted or unparseable document degrades
                to the metadata header with its Download URL. Extraction itself is capped (about
                100000 characters); a capped read's final window says the document continues -
                the Download URL serves the full file.

                Images up to 2 MB (PNG, JPEG, GIF, WebP) are returned as viewable image content
                next to the metadata header. Larger images, other image formats and every other
                type return the metadata header with a Download URL instead of content.

                metadata=true is a cheap probe: the header and Download URL only, the content is
                never read. The Document header line echoes the document version - use it as
                base_version for a follow-up write to the same document.

            EXAMPLES
                Read a text attachment:  reference="Sandbox.WebHome", filename="notes.txt"
                Extract a PDF's text:    reference="Sandbox.WebHome", filename="report.pdf"
                View an image:  reference="Sandbox.WebHome", filename="diagram.png"
                Metadata only:  reference="Sandbox.WebHome", filename="archive.zip", metadata=true
                Continuation:   reference="Sandbox.WebHome", filename="build.log", offset=250
                            (the offset comes from the previous read's truncation note)

            SEE ALSO
                man get_document        Lists a document's attachments (the Attachments header line)
                                        and shows the document version.
                man write_attachment    Attach a file or overwrite an existing attachment.
                man delete_attachment   Delete an attachment (moves it to the attachment recycle bin).
                man                     (no argument) List all tools and reference pages.
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
     * Runs one read: parses the arguments, resolves and authorizes the document, loads it, resolves the
     * exact attachment and dispatches to the response composition.
     *
     * @param args the tool call arguments
     * @return the tool result
     * @throws IllegalArgumentException with an agent-facing message on invalid arguments or a failed
     *     load
     */
    private McpSchema.CallToolResult read(Map<String, Object> args)
    {
        String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
        String filename = PARAMS.parser().requireString(args, FILENAME_PARAM);
        int offset = PARAMS.parser().integer(args, OFFSET_PARAM, 0);
        if (offset < 0) {
            return MCPToolSupport.errorResult(MCPToolSupport.ERROR_PREFIX + OFFSET_PARAM + "' must be >= 0.");
        }
        boolean metadataOnly = PARAMS.parser().bool(args, METADATA_PARAM);

        DocumentReference ref;
        try {
            ref = this.documentAccess.resolveAndAuthorize(reference, Right.VIEW);
        } catch (MCPAccessDeniedException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }

        if (!documentExists(ref, reference)) {
            return MCPToolSupport.errorResult(
                "No such document: " + QUOTE + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
        }
        XWikiDocument xdoc = loadDocument(ref, reference);
        XWikiAttachment attachment = xdoc.getExactAttachment(filename);
        if (attachment == null) {
            return MCPToolSupport.errorResult(MCPAttachmentSupport.missingAttachmentMessage(filename,
                reference, xdoc.getAttachmentList()));
        }
        return respond(xdoc, attachment, offset, metadataOnly);
    }

    /**
     * Composes the response: the metadata header always, then nothing more ({@code metadata=true}) or
     * the mimetype-routed content (see {@link #contentResult}).
     *
     * @param xdoc the loaded document
     * @param attachment the resolved attachment
     * @param offset the number of content lines to skip
     * @param metadataOnly whether to skip the content entirely
     * @return the tool result
     */
    private McpSchema.CallToolResult respond(XWikiDocument xdoc, XWikiAttachment attachment, int offset,
        boolean metadataOnly)
    {
        XWikiContext xcontext = this.contextProvider.get();
        String mimeType = attachment.getMimeType(xcontext);
        String header = composeHeader(xdoc, attachment, mimeType, xcontext);
        if (metadataOnly) {
            return MCPToolSupport.result(header);
        }
        return contentResult(header, attachment, xcontext, offset, mimeType);
    }

    /**
     * Routes the content below the header by mimetype: text (SVG included) is windowed inline, PDF and
     * office documents return their Tika-extracted text, inlineable images return an MCP image content
     * block, a non-inlineable image type is refused with image-specific wording, and everything else
     * gets the download pointer.
     *
     * @param header the composed metadata header
     * @param attachment the resolved attachment
     * @param xcontext the XWiki context
     * @param offset the number of content lines to skip
     * @param mimeType the attachment's resolved mimetype
     * @return the tool result
     */
    private McpSchema.CallToolResult contentResult(String header, XWikiAttachment attachment,
        XWikiContext xcontext, int offset, String mimeType)
    {
        if (MCPAttachmentSupport.isTextMimeType(mimeType)) {
            return textContentResult(header, attachment, xcontext, offset);
        }
        if (MCPAttachmentSupport.isExtractableMimeType(mimeType)) {
            return extractedTextResult(header, attachment, xcontext, offset, mimeType);
        }
        if (MCPAttachmentSupport.isInlineableImageMimeType(mimeType)) {
            return imageContentResult(header, attachment, xcontext, mimeType);
        }
        if (MCPAttachmentSupport.isImageMimeType(mimeType)) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + "Image type "
                + MCPTextGuards.fragment(mimeType) + " is not inlineable" + DOWNLOAD_POINTER_TAIL);
        }
        return MCPToolSupport.result(header + DOUBLE_NEW_LINE + "Content is "
            + MCPTextGuards.fragment(mimeType)
            + "; not inlineable as text. Use the Download URL above.");
    }

    /**
     * Reads and appends the budgeted text-content window below the header, with the continuation note on
     * a truncated read and the beyond-the-end message on an offset past the last line. The header counts
     * toward the shared output budget, so the window fills only what remains under it.
     *
     * @param header the composed metadata header
     * @param attachment the resolved attachment
     * @param xcontext the XWiki context
     * @param offset the number of content lines to skip
     * @return the tool result
     */
    private McpSchema.CallToolResult textContentResult(String header, XWikiAttachment attachment,
        XWikiContext xcontext, int offset)
    {
        MCPAttachmentSupport.TextWindow window;
        try {
            window = MCPAttachmentSupport.readTextWindow(attachment, xcontext, offset, contentBudget(header));
        } catch (Exception e) {
            return contentReadFailure(attachment, e);
        }
        return windowResult(header, window, offset, null);
    }

    /**
     * Extracts the text of a PDF or office attachment and appends it, windowed, below the header and
     * the extraction banner. A PARSE failure (encrypted, corrupt, no parser available) degrades
     * gracefully to the header with a pointer at the download URL - a broken document is a normal
     * outcome of this path, not a tool error; a STORE failure (the content stream could not be opened
     * or read) is a real error, routed to the same failure result as the raw-text path's. When the
     * extractor's own character cap was reached, the final window carries a note that the document
     * continues (the cut is silent - a clean-looking last window would otherwise be a lie).
     *
     * @param header the composed metadata header
     * @param attachment the resolved attachment
     * @param xcontext the XWiki context
     * @param offset the number of extracted-text lines to skip
     * @param mimeType the attachment's resolved mimetype, for the banner
     * @return the tool result
     */
    private McpSchema.CallToolResult extractedTextResult(String header, XWikiAttachment attachment,
        XWikiContext xcontext, int offset, String mimeType)
    {
        String extracted;
        try {
            extracted = MCPAttachmentSupport.extractText(attachment, xcontext);
        } catch (XWikiException e) {
            return contentReadFailure(attachment, e);
        } catch (Exception e) {
            this.logger.warn("MCP get_attachment tool failed to extract text from [{}]: [{}]",
                attachment.getFilename(), ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_attachment tool text-extraction failure details", e);
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + EXTRACTION_FAILED_BODY);
        }
        if (StringUtils.isBlank(extracted)) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + NO_EXTRACTED_TEXT_BODY);
        }
        String head = header + NEW_LINE + "Text extracted from " + MCPTextGuards.fragment(mimeType)
            + " (formatting not preserved):";
        MCPAttachmentSupport.TextWindow window;
        try {
            window = MCPAttachmentSupport.readTextWindow(extracted, offset, contentBudget(head));
        } catch (Exception e) {
            return contentReadFailure(attachment, e);
        }
        return windowResult(head, window, offset, extractionCappedNote(extracted));
    }

    /**
     * Builds the note the FINAL extraction window carries when the extracted text's length reached the
     * extractor's own character cap, or {@code null} when it did not (or the cap is unlimited).
     *
     * @param extracted the extracted text
     * @return the note, or {@code null} when no note applies
     */
    private static String extractionCappedNote(String extracted)
    {
        int cap = MCPAttachmentSupport.extractionCap();
        if (cap > 0 && extracted.length() >= cap) {
            return EXTRACTION_CAPPED_NOTE_PREFIX + cap + EXTRACTION_CAPPED_NOTE_SUFFIX;
        }
        return null;
    }

    /**
     * Reads an inlineable image below the cap and returns it as an MCP image content block next to the
     * header. The cap is enforced twice: a declared size above it skips the read entirely, and the
     * bounded read abandons a stream that yields more than the cap (a declared size that lied),
     * degrading to the download pointer either way.
     *
     * @param header the composed metadata header
     * @param attachment the resolved attachment
     * @param xcontext the XWiki context
     * @param mimeType the attachment's resolved mimetype
     * @return the tool result
     */
    private McpSchema.CallToolResult imageContentResult(String header, XWikiAttachment attachment,
        XWikiContext xcontext, String mimeType)
    {
        long declaredSize = attachment.getLongSize();
        if (declaredSize > MCPAttachmentSupport.MAX_IMAGE_BYTES) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + "Image is "
                + MCPAttachmentSupport.humanSize(declaredSize) + IMAGE_OVER_CAP_TAIL);
        }
        byte[] imageBytes;
        try {
            imageBytes = MCPAttachmentSupport.readImageBytes(attachment, xcontext);
        } catch (Exception e) {
            return contentReadFailure(attachment, e);
        }
        if (imageBytes == null) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + IMAGE_STREAM_OVER_CAP_BODY);
        }
        return MCPAttachmentSupport.imageResult(header + DOUBLE_NEW_LINE + IMAGE_MARKER_BODY, imageBytes,
            mimeType);
    }

    /**
     * Composes the result of a window read below the given head block (the header, plus the extraction
     * banner on the extraction path): the continuation note on a truncated read, the beyond-the-end
     * message on an offset past the last line, and the no-content body on an empty stored file. The
     * optional end note is appended only to a FINAL window - one that reached the end of the content -
     * so a mid-stream window never carries it.
     *
     * @param head the header block the window follows
     * @param window the read outcome
     * @param offset the number of content lines that were skipped
     * @param endNote a note appended to the final window, or {@code null} for none
     * @return the tool result
     */
    private McpSchema.CallToolResult windowResult(String head, MCPAttachmentSupport.TextWindow window,
        int offset, String endNote)
    {
        if (window.beyondEnd()) {
            return MCPToolSupport.result(head + DOUBLE_NEW_LINE + "Text content has only "
                + window.totalLines() + " lines; offset " + offset + " is beyond the end.");
        }
        if (window.truncated()) {
            String notePrefix = window.lineCut() ? LINE_CUT_CONTINUATION_PREFIX : CONTINUATION_PREFIX;
            return MCPToolSupport.result(head + DOUBLE_NEW_LINE + window.content() + NEW_LINE
                + notePrefix + window.nextOffset() + PERIOD);
        }
        if (window.content().isEmpty()) {
            return MCPToolSupport.result(head + DOUBLE_NEW_LINE + NO_CONTENT_BODY);
        }
        String tail = endNote != null ? NEW_LINE + endNote : "";
        return MCPToolSupport.result(head + DOUBLE_NEW_LINE + window.content() + tail);
    }

    /**
     * Computes the character budget left for content under the given head block: the head counts toward
     * the shared output budget, so the window fills only what remains under it.
     *
     * @param head the header block the content follows
     * @return the remaining character budget, at least 1
     */
    private static int contentBudget(String head)
    {
        return Math.max(1, MCPSourceText.MAX_OUTPUT_CHARS - head.length());
    }

    /**
     * Handles a failed content read uniformly across the content paths: the root cause goes to the
     * server logs, the agent gets the fixed error message.
     *
     * @param attachment the attachment whose read failed
     * @param e the failure
     * @return the error result
     */
    private McpSchema.CallToolResult contentReadFailure(XWikiAttachment attachment, Exception e)
    {
        this.logger.warn("MCP get_attachment tool failed to read the content of [{}]: [{}]",
            attachment.getFilename(), ExceptionUtils.getRootCauseMessage(e));
        this.logger.debug("MCP get_attachment tool content-read failure details", e);
        return MCPToolSupport.errorResult(CONTENT_READ_ERROR);
    }

    /**
     * Composes the metadata header, one field per line, every wiki-authored value neutralized
     * (fragment-guarded filename and mimetype, line-break-stripped serialized references and stored
     * strings). The
     * {@code Author:}, {@code Date:} and {@code Download:} lines are omitted when their value is
     * unavailable, mirroring how {@code get_document} omits its URL line.
     *
     * @param xdoc the loaded document
     * @param attachment the resolved attachment
     * @param mimeType the attachment's resolved mimetype
     * @param xcontext the XWiki context
     * @return the composed header
     */
    private String composeHeader(XWikiDocument xdoc, XWikiAttachment attachment, String mimeType,
        XWikiContext xcontext)
    {
        StringBuilder header = new StringBuilder();
        header.append("Attachment: ").append(MCPTextGuards.fragment(attachment.getFilename())).append(NEW_LINE);
        header.append("Document: ")
            .append(MCPToolSupport.stripLineBreaks(this.serializer.serialize(xdoc.getDocumentReference())))
            .append(" (version ").append(xdoc.getVersion()).append(')').append(NEW_LINE);
        header.append("Mimetype: ").append(MCPTextGuards.fragment(mimeType)).append(NEW_LINE);
        header.append("Size: ").append(sizeDescription(attachment.getLongSize())).append(NEW_LINE);
        header.append("Attachment version: ").append(MCPToolSupport.stripLineBreaks(attachment.getVersion()));
        if (attachment.getAuthorReference() != null) {
            header.append(NEW_LINE).append("Author: ")
                .append(MCPToolSupport.stripLineBreaks(this.serializer.serialize(
                    attachment.getAuthorReference())));
        }
        if (attachment.getDate() != null) {
            header.append(NEW_LINE).append("Date: ")
                .append(MCPAttachmentSupport.formatDate(attachment.getDate()));
        }
        String downloadUrl = safeDownloadUrl(xdoc, attachment.getFilename(), xcontext);
        if (downloadUrl != null) {
            header.append(NEW_LINE).append("Download: ").append(downloadUrl);
        }
        return header.toString();
    }

    /**
     * Formats the {@code Size:} value: the human-readable size, with the exact byte count in parentheses
     * when it adds information (from one KB up; below that the human form IS the byte count, and a
     * negative count means the stored size is unknown).
     *
     * @param bytes the attachment's byte count, negative when unknown
     * @return the formatted size value
     */
    private static String sizeDescription(long bytes)
    {
        String human = MCPAttachmentSupport.humanSize(bytes);
        if (bytes < MCPAttachmentSupport.ONE_KILOBYTE) {
            return human;
        }
        return human + " (" + bytes + " bytes)";
    }

    /**
     * Builds the external download URL of the attachment, returning {@code null} instead of propagating
     * a URL-building failure: the line is a convenience, never worth failing the read over.
     *
     * @param xdoc the loaded document
     * @param filename the attachment's stored filename
     * @param xcontext the XWiki context
     * @return the download URL, or {@code null} when it could not be built
     */
    private String safeDownloadUrl(XWikiDocument xdoc, String filename, XWikiContext xcontext)
    {
        try {
            return xdoc.getExternalAttachmentURL(filename, "download", xcontext);
        } catch (Exception e) {
            this.logger.debug("MCP get_attachment tool could not build the download URL", e);
            return null;
        }
    }

    private boolean documentExists(DocumentReference ref, String reference)
    {
        try {
            return this.documentAccessBridge.exists(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_attachment tool failed to check existence of [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_attachment tool existence-check failure details", e);
            throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
                + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
        }
    }

    /**
     * Loads the addressed document as its oldcore instance, which owns the attachment list and the
     * attachment URL building.
     *
     * @param ref the resolved document reference
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
            this.logger.warn("MCP get_attachment tool failed to load [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_attachment tool load failure details", e);
        }
        if (doc instanceof XWikiDocument xdoc) {
            return xdoc;
        }
        throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
            + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
    }
}
