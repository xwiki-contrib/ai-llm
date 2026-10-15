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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.MockedStatic;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPSourceText;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.tika.internal.TikaUtils;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.web.XWikiURLFactory;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPGetAttachmentTool}.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPGetAttachmentToolTest extends AbstractMCPToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String FILENAME_KEY = "filename";

    private static final String OFFSET_KEY = "offset";

    private static final String METADATA_KEY = "metadata";

    private static final String REF = "Sandbox.WebHome";

    private static final String CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String AUTHOR_CANONICAL = "xwiki:XWiki.PaulPantiru";

    private static final String NOTES_TXT = "notes.txt";

    private static final String TEXT_PLAIN = "text/plain";

    private static final String NOTES_CONTENT = "Line one\nLine two";

    private static final String DOWNLOAD_URL = "https://wiki.example/bin/download/Sandbox/WebHome/notes.txt";

    private static final DocumentReference DOC_REF = new DocumentReference("xwiki", "Sandbox", "WebHome");

    private static final DocumentReference AUTHOR_REF = new DocumentReference("xwiki", "XWiki", "PaulPantiru");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPGetAttachmentTool tool;

    @MockComponent
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    private MCPWikiReach wikiReach;

    private MockitoOldcore oldcore;

    @BeforeEach
    void setUp(MockitoOldcore mockitoOldcore) throws Exception
    {
        this.oldcore = mockitoOldcore;
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW))).thenReturn(DOC_REF);
        lenient().when(this.serializer.serialize(DOC_REF)).thenReturn(CANONICAL);
        lenient().when(this.serializer.serialize(AUTHOR_REF)).thenReturn(AUTHOR_CANONICAL);
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    /**
     * @param text a possibly very long tool result
     * @return its last 400 characters, for assertion messages that must not dump a giant window
     */
    private static String tailOf(String text)
    {
        return text.substring(Math.max(0, text.length() - 400));
    }

    /**
     * Stubs a successful extraction on the static Tika mock: the parsed text plus a shared Tika
     * instance answering the given max string length (the tool consults it for the capped-extraction
     * note after every successful extraction).
     *
     * @param tika the static Tika mock
     * @param extracted the text the extraction yields
     * @param cap the extractor's max string length
     */
    private static void stubExtracted(MockedStatic<TikaUtils> tika, String extracted, int cap)
    {
        tika.when(() -> TikaUtils.parseToString(any(InputStream.class), any(Metadata.class)))
            .thenReturn(extracted);
        Tika instance = mock(Tika.class);
        lenient().when(instance.getMaxStringLength()).thenReturn(cap);
        tika.when(TikaUtils::getTika).thenReturn(instance);
    }

    /**
     * A stream producing a fixed number of bytes on the fly (no data allocated), counting what was
     * consumed and whether it was closed - pins the bounded abandon and the try-with-resources of the
     * image read path.
     *
     * @version $Id$
     */
    private static final class CountingStream extends InputStream
    {
        private final long limit;

        private long produced;

        private boolean closed;

        CountingStream(long limit)
        {
            this.limit = limit;
        }

        @Override
        public int read()
        {
            if (this.produced >= this.limit) {
                return -1;
            }
            this.produced++;
            return 'A';
        }

        @Override
        public void close()
        {
            this.closed = true;
        }
    }

    /**
     * A byte-array stream remembering whether it was closed - pins the try-with-resources of the text
     * and extraction read paths.
     *
     * @version $Id$
     */
    private static final class ClosableStream extends ByteArrayInputStream
    {
        private boolean closed;

        ClosableStream(byte[] bytes)
        {
            super(bytes);
        }

        @Override
        public void close() throws IOException
        {
            this.closed = true;
            super.close();
        }
    }

    private XWikiDocument stubDocument() throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REF);
        doc.setVersion("3.2");
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(DOC_REF)).thenReturn(doc);
        return doc;
    }

    private XWikiAttachment addAttachment(XWikiDocument doc, String filename, byte[] bytes, String mimeType)
        throws Exception
    {
        XWikiAttachment attachment = new XWikiAttachment(doc, filename);
        attachment.setContent(new ByteArrayInputStream(bytes));
        attachment.setMimeType(mimeType);
        doc.setAttachment(attachment);
        return attachment;
    }

    private void stubUrlFactory() throws Exception
    {
        XWikiURLFactory urlFactory = mock(XWikiURLFactory.class);
        lenient().when(urlFactory.createAttachmentURL(any(), any(), any(), any(), any(), any(),
            any(XWikiContext.class))).thenReturn(new URL(DOWNLOAD_URL));
        this.oldcore.getXWikiContext().setURLFactory(urlFactory);
    }

    @Test
    void accessDeniedReturnsRefusalWithoutLoadingDocument() throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to view \"" + REF + "\"."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"), textOf(result));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void missingDocumentReturnsNotFound() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("No such document: \"" + REF + "\"."), textOf(result));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void missingAttachmentRefusalListsExistingNames() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, NOTES_TXT, NOTES_CONTENT.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);
        addAttachment(doc, "logo.png", new byte[] {1, 2, 3}, "image/png");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "missing.txt"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment \"missing.txt\" not found on \"" + REF + "\"."), text);
        // The platform's attachment list is filename-sorted, so the refusal lists names alphabetically.
        assertTrue(text.contains("Attachments on this document: logo.png, notes.txt."), text);
    }

    @Test
    void missingAttachmentRefusalSaysNoAttachmentsWhenNoneExist() throws Exception
    {
        stubDocument();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("This document has no attachments."), textOf(result));
    }

    @Test
    void filenameMatchingIsExactNotFuzzy() throws Exception
    {
        // The platform's fuzzy getAttachment would serve "report.pdf" for "report"; the tool must miss
        // instead and teach the exact name.
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "report.pdf", new byte[] {1, 2, 3}, "application/pdf");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "report"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment \"report\" not found"), text);
        assertTrue(text.contains("Attachments on this document: report.pdf."), text);
    }

    @Test
    void textAttachmentReturnsFullHeaderAndContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment =
            addAttachment(doc, NOTES_TXT, NOTES_CONTENT.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);
        attachment.setAuthorReference(AUTHOR_REF);
        attachment.setDate(new GregorianCalendar(2026, Calendar.AUGUST, 12, 14, 3).getTime());
        stubUrlFactory();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.startsWith("Attachment: notes.txt\n"), text);
        assertTrue(text.contains("Document: " + CANONICAL + " (version 3.2)"), text);
        assertTrue(text.contains("Mimetype: text/plain"), text);
        assertTrue(text.contains("Size: " + NOTES_CONTENT.length() + " bytes"), text);
        assertTrue(text.contains("Attachment version: 1.1"), text);
        assertTrue(text.contains("Author: " + AUTHOR_CANONICAL), text);
        assertTrue(text.contains("Date: 2026-08-12 14:03"), text);
        assertTrue(text.contains("Download: " + DOWNLOAD_URL), text);
        // The content follows the header after a blank line, in full and without a truncation note.
        assertTrue(text.endsWith("\n\n" + NOTES_CONTENT), text);
        assertFalse(text.contains("Output truncated"), text);
    }

    @Test
    void metadataOnlyReturnsHeaderWithoutOpeningTheContentStream() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment =
            spy(new XWikiAttachment(doc, NOTES_TXT));
        attachment.setContent(new ByteArrayInputStream(NOTES_CONTENT.getBytes(StandardCharsets.UTF_8)));
        attachment.setMimeType(TEXT_PLAIN);
        doc.setAttachment(attachment);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, METADATA_KEY, true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment: notes.txt"), text);
        assertFalse(text.contains("Line one"), text);
        verify(attachment, never()).getContentInputStream(any(XWikiContext.class));
    }

    @Test
    void truncatedReadCarriesContinuationOffsetThatResumesTheContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            if (i > 0) {
                content.append('\n');
            }
            content.append(String.format("line %05d", i));
        }
        addAttachment(doc, "build.log", content.toString().getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        String first = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "build.log"));

        assertTrue(first.contains("\n\nline 00000\n"), "The window must start at the first line");
        Matcher matcher = Pattern.compile("Output truncated at the ~\\d+-token cap; continue with offset=(\\d+)\\.")
            .matcher(first);
        assertTrue(matcher.find(), first);
        int nextOffset = Integer.parseInt(matcher.group(1));
        String lastEmitted = String.format("line %05d", nextOffset - 1);
        String firstOmitted = String.format("line %05d", nextOffset);
        assertTrue(first.contains(lastEmitted), first);
        assertFalse(first.contains(firstOmitted), first);

        String second = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "build.log", OFFSET_KEY, nextOffset));

        // The second window resumes exactly where the first one stopped, at a line boundary.
        assertTrue(second.contains("\n\n" + firstOmitted + "\n"), second);
        assertFalse(second.substring(second.indexOf("\n\n")).contains(lastEmitted), second);
    }

    @Test
    void offsetBeyondEndReturnsBeyondTheEndMessage() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, NOTES_TXT, "one\ntwo\nthree".getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, OFFSET_KEY, 10));

        assertTrue(text.contains("Text content has only 3 lines; offset 10 is beyond the end."), text);
        assertFalse(text.contains("one\ntwo"), text);
    }

    @Test
    void binaryAttachmentReturnsDownloadPointerInsteadOfContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        byte[] bytes = new byte[2048];
        byte[] marker = "SECRETBYTES".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, bytes, 0, marker.length);
        addAttachment(doc, "archive.zip", bytes, "application/zip");
        stubUrlFactory();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "archive.zip"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Size: 2 KB (2048 bytes)"), text);
        assertTrue(text.contains("Content is application/zip; not inlineable as text. "
            + "Use the Download URL above."), text);
        assertFalse(text.contains("SECRETBYTES"), "Binary content must never be inlined");
    }

    @Test
    void hostileFilenameIsFragmentGuardedInRefusalAndHeader() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String hostile = "evil\nname" + "x".repeat(250) + ".txt";
        addAttachment(doc, hostile, "safe".getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        // The miss refusal lists the stored name neutralized: no forged line, cut with an ellipsis.
        String refusal = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "missing.txt"));
        assertTrue(refusal.contains("evilname"), refusal);
        assertFalse(refusal.contains("evil\nname"), refusal);
        assertTrue(refusal.contains("…"), refusal);

        // A hit on the exact stored name echoes it neutralized in the header too.
        String header = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, hostile));
        assertTrue(header.startsWith("Attachment: evilname"), header);
        assertFalse(header.contains("evil\nname"), header);
        assertTrue(header.contains("…"), header);
    }

    @Test
    void declaredCharsetDecodesTheContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment =
            addAttachment(doc, NOTES_TXT, "café latin".getBytes(StandardCharsets.ISO_8859_1), TEXT_PLAIN);
        attachment.setCharset("ISO-8859-1");

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.contains("café latin"), text);
    }

    @Test
    void oversizedSingleLineIsHardCutWithMidLineNoteAndBoundedOutput() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String giant = "A".repeat(30_000);
        addAttachment(doc, "one-line.json", (giant + "\nsecond line").getBytes(StandardCharsets.UTF_8),
            "application/json");

        String first = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "one-line.json"));

        // The head of the line is emitted, hard-cut mid-line, and nothing past the cut leaks out.
        assertTrue(first.contains("AAAA"), tailOf(first));
        assertFalse(first.contains("second line"), "The window must stop inside the oversized line");
        assertTrue(first.contains("Output truncated MID-LINE at the ~"), tailOf(first));
        assertTrue(first.contains("continue with the next line at offset=1."), tailOf(first));
        // The whole result stays bounded: budget plus a small header/note slack, never the raw line size.
        assertTrue(first.length() <= MCPSourceText.MAX_OUTPUT_CHARS + 600,
            "Result length " + first.length() + " exceeds the bounded budget");

        String second = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "one-line.json", OFFSET_KEY, 1));

        // The continuation offset resumes at the line AFTER the cut one.
        assertTrue(second.endsWith("\n\nsecond line"), tailOf(second));
    }

    @Test
    void skipPhaseOverAnOversizedLineResumesAtTheRightLine() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String giant = "B".repeat(30_000);
        addAttachment(doc, "log.txt",
            (giant + "\nsecond line\nthird line").getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "log.txt", OFFSET_KEY, 1));

        // Skipping the giant first line counts it as exactly one line and emits the rest.
        assertTrue(text.endsWith("\n\nsecond line\nthird line"), tailOf(text));
        assertFalse(text.contains("BBBB"), "Skipped content must not be emitted");
    }

    @Test
    void unknownCharsetFallsBackToUtf8() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment =
            addAttachment(doc, NOTES_TXT, "café fallback".getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);
        attachment.setCharset("no-such-charset");

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.contains("café fallback"), text);
    }

    @Test
    void existenceCheckFailureReturnsErrorAndLogsWarning() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the document \"" + REF + "\"."), textOf(result));
        assertFalse(textOf(result).contains("boom"), "Root cause must not leak into the agent-facing message");
        assertTrue(this.logCapture.getMessage(0).contains("failed to check existence"),
            this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("boom"), this.logCapture.getMessage(0));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void existenceCheckFailureLogsTheReferenceWithoutItsLineBreak() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF + "\nforged line", FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        String warn = this.logCapture.getMessage(0);
        assertTrue(warn.contains("failed to check existence"), warn);
        assertFalse(warn.contains("\n"), warn);
        assertTrue(warn.contains("forged line"), warn);
    }

    @Test
    void documentLoadFailureReturnsErrorAndLogsWarning() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(DOC_REF)).thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF + "\nforged line", FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the document \"" + REF + "forged line\"."),
            textOf(result));
        assertFalse(textOf(result).contains("boom"), "Root cause must not leak into the agent-facing message");
        String warn = this.logCapture.getMessage(0);
        assertTrue(warn.contains("failed to load"), warn);
        assertTrue(warn.contains("boom"), warn);
        assertTrue(warn.contains("forged line"), warn);
        assertFalse(warn.contains("\n"), warn);
    }

    @Test
    void hostileMimetypeIsClampedInHeaderAndPointerLine() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String hostile = "x".repeat(5000);
        addAttachment(doc, "blob.bin", new byte[] {1, 2}, hostile);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "blob.bin"));

        // The stored mimetype is clamped to the fragment cap with an ellipsis, on both echo sites.
        assertTrue(text.contains("Mimetype: " + "x".repeat(200) + "…"), text.substring(0, 400));
        assertTrue(text.contains("Content is " + "x".repeat(200) + "…;"), text);
        assertFalse(text.contains("x".repeat(201)), "The mimetype must be cut at the fragment cap");
    }

    @Test
    void blankCharsetFallsBackToUtf8() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, NOTES_TXT, "café utf".getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.contains("café utf"), text);
    }

    @Test
    void emptyTextAttachmentSaysNoContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, NOTES_TXT, new byte[0], TEXT_PLAIN);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.contains("Attachment has no content."), text);
    }

    @Test
    void negativeOffsetReturnsError() throws Exception
    {
        stubDocument();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, OFFSET_KEY, -1));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Error: 'offset' must be >= 0."), textOf(result));
    }

    @Test
    void contentReadFailureLogsTheFilenameWithoutItsLineBreak() throws Exception
    {
        String forgedName = "notes\nforged line.txt";
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, forgedName));
        attachment.setMimeType(TEXT_PLAIN);
        doc.setAttachment(attachment);
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_ATTACHMENT, "boom"))
            .when(attachment).getContentInputStream(any(XWikiContext.class));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, forgedName));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the attachment content."), textOf(result));
        String warn = this.logCapture.getMessage(0);
        assertTrue(warn.contains("failed to read the content"), warn);
        assertFalse(warn.contains("\n"), warn);
        assertTrue(warn.contains("notesforged line.txt"), warn);
    }

    @Test
    void contentReadFailureReturnsErrorAndLogsWarning() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, NOTES_TXT));
        attachment.setMimeType(TEXT_PLAIN);
        doc.setAttachment(attachment);
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_ATTACHMENT, "boom"))
            .when(attachment).getContentInputStream(any(XWikiContext.class));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the attachment content."), textOf(result));
        assertFalse(textOf(result).contains("boom"), "Root cause must not leak into the agent-facing message");
        assertTrue(this.logCapture.getMessage(0).contains("failed to read the content"),
            this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("boom"), this.logCapture.getMessage(0));
    }

    @Test
    void pdfRoutesToTikaExtractionWithBannerAndResourceName() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "report.pdf", new byte[] {1, 2, 3}, "application/pdf");
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            stubExtracted(tika, "Extracted line one\nExtracted line two", 100_000);

            String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "report.pdf"));

            assertTrue(text.contains("Text extracted from application/pdf (formatting not preserved):\n\n"
                + "Extracted line one\nExtracted line two"), text);
            // The filename is passed as the Tika resource name so type detection can use it.
            tika.verify(() -> TikaUtils.parseToString(any(InputStream.class),
                argThat((Metadata metadata) ->
                    "report.pdf".equals(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)))));
        }
    }

    @Test
    void officeFamiliesRouteToExtractionWhileZipDoesNot() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "spec.docx", new byte[] {1},
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        addAttachment(doc, "notes.odt", new byte[] {2}, "application/vnd.oasis.opendocument.text");
        addAttachment(doc, "archive.zip", new byte[] {3}, "application/zip");
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            stubExtracted(tika, "Office text", 100_000);

            String docx = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "spec.docx"));
            String odt = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "notes.odt"));
            String zip = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "archive.zip"));

            assertTrue(docx.contains("Text extracted from "
                + "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), docx);
            assertTrue(odt.contains("Text extracted from application/vnd.oasis.opendocument.text"), odt);
            assertTrue(zip.contains("Content is application/zip; not inlineable as text."), zip);
            // Only the two office documents were parsed; the archive never reached Tika.
            tika.verify(() -> TikaUtils.parseToString(any(InputStream.class), any(Metadata.class)), times(2));
        }
    }

    @Test
    void extractionFailureDegradesGracefullyAndLogsWarning() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String forgedFilename = "locked\nforged line.pdf";
        addAttachment(doc, forgedFilename, new byte[] {1}, "application/pdf");
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            tika.when(() -> TikaUtils.parseToString(any(InputStream.class), any(Metadata.class)))
                .thenThrow(new TikaException("boom"));

            McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, forgedFilename));

            // A broken document degrades to the header plus a pointer - it is NOT a tool error.
            assertNotEquals(Boolean.TRUE, result.isError());
            String text = textOf(result);
            assertTrue(text.contains("Attachment: lockedforged line.pdf"), text);
            assertTrue(text.contains("Text extraction failed; use the Download URL above."), text);
            assertFalse(text.contains("boom"), "Root cause must not leak into the agent-facing message");
        }
        String warn = this.logCapture.getMessage(0);
        assertTrue(warn.contains("failed to extract text"), warn);
        assertTrue(warn.contains("boom"), warn);
        assertTrue(warn.contains("forged line"), warn);
        assertFalse(warn.contains("\n"), warn);
    }

    @Test
    void blankExtractionSaysNoTextCouldBeExtracted() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "scan.pdf", new byte[] {1}, "application/pdf");
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            tika.when(() -> TikaUtils.parseToString(any(InputStream.class), any(Metadata.class)))
                .thenReturn("  \n ");

            String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "scan.pdf"));

            assertTrue(text.contains("No text could be extracted; use the Download URL above."), text);
        }
    }

    @Test
    void extractionRespectsOffsetAndContinuationNote() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "big.pdf", new byte[] {1}, "application/pdf");
        StringBuilder extracted = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            if (i > 0) {
                extracted.append('\n');
            }
            extracted.append(String.format("ex %05d", i));
        }
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            stubExtracted(tika, extracted.toString(), 100_000);

            String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "big.pdf", OFFSET_KEY, 2));

            // The window starts at the requested extracted-text line and truncates with a continuation
            // offset, exactly like a raw text read.
            assertTrue(text.contains("(formatting not preserved):\n\nex 00002\n"), tailOf(text));
            Matcher matcher = Pattern
                .compile("Output truncated at the ~\\d+-token cap; continue with offset=(\\d+)\\.")
                .matcher(text);
            assertTrue(matcher.find(), tailOf(text));
            assertTrue(Integer.parseInt(matcher.group(1)) > 2, tailOf(text));
            // The extraction-cap note belongs to the FINAL window only, never to a mid-stream one.
            assertFalse(text.contains("Extraction was capped"), tailOf(text));
        }
    }

    @Test
    void metadataOnlyOnPdfNeverTouchesTikaOrTheStream() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, "report.pdf"));
        attachment.setMimeType("application/pdf");
        doc.setAttachment(attachment);
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            String text = callText(
                Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "report.pdf", METADATA_KEY, true));

            assertTrue(text.contains("Attachment: report.pdf"), text);
            tika.verifyNoInteractions();
        }
        verify(attachment, never()).getContentInputStream(any(XWikiContext.class));
    }

    @Test
    void pngIsReturnedAsViewableImageContentBlock() throws Exception
    {
        XWikiDocument doc = stubDocument();
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
        addAttachment(doc, "pixel.png", png, "image/png");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "pixel.png"));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals(2, result.content().size(), "Expected exactly [text, image] content blocks");
        McpSchema.TextContent textBlock = (McpSchema.TextContent) result.content().get(0);
        assertTrue(textBlock.text().contains("Attachment: pixel.png"), textBlock.text());
        assertTrue(textBlock.text().endsWith("\n\nImage content follows."), textBlock.text());
        McpSchema.ImageContent image = (McpSchema.ImageContent) result.content().get(1);
        assertEquals("image/png", image.mimeType());
        assertArrayEquals(png, Base64.getDecoder().decode(image.data()),
            "The image block must carry the original bytes base64-encoded");
    }

    @Test
    void declaredOversizeImageSkipsTheReadAndFallsBack() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, "photo.png"));
        attachment.setMimeType("image/png");
        doc.setAttachment(attachment);
        doReturn(3L * 1024 * 1024).when(attachment).getLongSize();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "photo.png"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Image is 3 MB, above the 2 MB inline cap; use the Download URL above."),
            text);
        assertEquals(1, result.content().size(), "No image block on the over-cap fallback");
        verify(attachment, never()).getContentInputStream(any(XWikiContext.class));
    }

    @Test
    void lyingDeclaredSizeStreamOverCapIsAbandoned() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, "huge.png"));
        attachment.setMimeType("image/png");
        doc.setAttachment(attachment);
        // The declared size claims unknown; the stream then yields more than the cap without ever
        // allocating real data.
        doReturn(-1L).when(attachment).getLongSize();
        CountingStream oversized = new CountingStream((long) MCPAttachmentSupport.MAX_IMAGE_BYTES + 2);
        doReturn(oversized).when(attachment).getContentInputStream(any(XWikiContext.class));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "huge.png"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Image is larger than the 2 MB inline cap; use the Download URL above."),
            text);
        assertEquals(1, result.content().size(), "No image block when the stream exceeded the cap");
        // The read abandoned the stream at the cap instead of draining it, and closed it.
        assertTrue(oversized.produced <= MCPAttachmentSupport.MAX_IMAGE_BYTES + 1L,
            "Read " + oversized.produced + " bytes; the bounded read must stop at the cap");
        assertTrue(oversized.closed, "The abandoned stream must still be closed");
    }

    @Test
    void lyingSmallDeclaredSizeGrowsToCapAndAbandons() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, "small.png"));
        attachment.setMimeType("image/png");
        doc.setAttachment(attachment);
        // The declared size claims 3 KB (so the read starts with a small buffer), but the stream yields
        // more than the CAP: the read must grow toward the cap - never refuse merely past the untrusted
        // declared size - and abandon only past the cap.
        doReturn(3072L).when(attachment).getLongSize();
        CountingStream oversized = new CountingStream((long) MCPAttachmentSupport.MAX_IMAGE_BYTES + 2);
        doReturn(oversized).when(attachment).getContentInputStream(any(XWikiContext.class));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "small.png"));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains(
            "Image is larger than the 2 MB inline cap; use the Download URL above."), textOf(result));
        assertTrue(oversized.produced <= MCPAttachmentSupport.MAX_IMAGE_BYTES + 1L,
            "Read " + oversized.produced + " bytes; the bounded read must stop at the cap");
        assertTrue(oversized.closed, "The abandoned stream must still be closed");
    }

    @Test
    void nonInlineableImageTypeFallsBackWithImageWording() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "scan.tiff", new byte[] {1, 2}, "image/tiff");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "scan.tiff"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Image type image/tiff is not inlineable; use the Download URL above."),
            text);
        assertEquals(1, result.content().size(), "No image block for a non-inlineable image type");
    }

    @Test
    void svgIsInlinedAsText() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "logo.svg", "<svg><circle r=\"4\"/></svg>".getBytes(StandardCharsets.UTF_8),
            "image/svg+xml");

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "logo.svg"));

        // SVG is XML text an agent can read and edit; it must never be routed as a vision image.
        assertTrue(text.endsWith("\n\n<svg><circle r=\"4\"/></svg>"), tailOf(text));
    }

    @Test
    void extractionCapReachedNotesThatTheDocumentContinues() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "long.pdf", new byte[] {1}, "application/pdf");
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            // The extractor cut silently at its 50-char cap: the extracted text's length reaches it.
            stubExtracted(tika, "x".repeat(50), 50);

            String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "long.pdf"));

            assertTrue(text.endsWith("\nExtraction was capped at ~50 chars; the document continues beyond "
                + "this point - use the Download URL above for the full file."), tailOf(text));
            assertTrue(text.contains("(formatting not preserved):\n\n" + "x".repeat(50) + "\n"), tailOf(text));
        }
    }

    @Test
    void extractionStoreFailureIsAnErrorNotAParseDegrade() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, "report.pdf"));
        attachment.setMimeType("application/pdf");
        doc.setAttachment(attachment);
        // The STORE fails to serve the content stream - unlike a parse failure, this is a real error.
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_ATTACHMENT, "boom"))
            .when(attachment).getContentInputStream(any(XWikiContext.class));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "report.pdf"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the attachment content."), textOf(result));
        assertFalse(textOf(result).contains("Text extraction failed"),
            "A store failure must not masquerade as a parse degrade");
        assertTrue(this.logCapture.getMessage(0).contains("failed to read the content"),
            this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("boom"), this.logCapture.getMessage(0));
    }

    @Test
    void rtfTextFormRoutesToExtraction() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "doc.rtf", new byte[] {1}, "text/rtf");
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            stubExtracted(tika, "Rtf text", 100_000);

            String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "doc.rtf"));

            // RTF served as text/rtf is control-word source, not readable text: extracted, not inlined.
            assertTrue(text.contains("Text extracted from text/rtf (formatting not preserved):\n\nRtf text"),
                text);
            tika.verify(() -> TikaUtils.parseToString(any(InputStream.class), any(Metadata.class)));
        }
    }

    @Test
    void textReadClosesTheContentStream() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, NOTES_TXT));
        attachment.setMimeType(TEXT_PLAIN);
        doc.setAttachment(attachment);
        ClosableStream stream = new ClosableStream("line one".getBytes(StandardCharsets.UTF_8));
        doReturn(stream).when(attachment).getContentInputStream(any(XWikiContext.class));

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.endsWith("\n\nline one"), tailOf(text));
        assertTrue(stream.closed, "The text read must close the content stream");
    }

    @Test
    void extractionClosesTheContentStream() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, "report.pdf"));
        attachment.setMimeType("application/pdf");
        doc.setAttachment(attachment);
        ClosableStream stream = new ClosableStream(new byte[] {1, 2, 3});
        doReturn(stream).when(attachment).getContentInputStream(any(XWikiContext.class));
        try (MockedStatic<TikaUtils> tika = mockStatic(TikaUtils.class)) {
            stubExtracted(tika, "Extracted", 100_000);

            String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "report.pdf"));

            assertTrue(text.contains("Extracted"), text);
        }
        assertTrue(stream.closed, "The extraction path must close the content stream");
    }

    @Test
    void toolDescriptionNamesTheContentBehaviors()
    {
        String description = this.tool.getToolDefinition().description();

        assertTrue(description.contains("extracted text"), description);
        assertTrue(description.contains("2 MB"), description);
        assertTrue(description.contains("image content"), description);
        assertTrue(description.contains("SVG"), description);
    }

    @Test
    void schemaDeclaresParamsInImportanceOrderWithReferenceAndFilenameRequired()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        Map<?, ?> properties = (Map<?, ?>) definition.inputSchema().get("properties");
        List<Object> keys = new ArrayList<>(properties.keySet());
        assertEquals(List.of(REFERENCE_KEY, FILENAME_KEY, OFFSET_KEY, METADATA_KEY), keys);
        assertEquals(List.of(REFERENCE_KEY, FILENAME_KEY), definition.inputSchema().get("required"));
    }

    @Test
    void summaryCategoryAndWriteFlagAreDeclared()
    {
        assertEquals("Read an attachment's content or metadata from a document.", this.tool.getSummary());
        assertEquals("Search & Navigation", this.tool.getCategory());
        assertFalse(this.tool.isWrite());
        assertTrue(this.tool.isEnabled());
    }

    @Test
    void reachOnMentionsCrossWikiInReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);

        String description = referenceDescription();

        assertTrue(description.contains("wiki-id prefix"), description);
        assertTrue(description.contains("xwiki:Sandbox.WebHome"), description);
    }

    @Test
    void reachOffDropsCrossWikiFromReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        String description = referenceDescription();

        assertFalse(description.contains("wiki-id prefix"), description);
        assertFalse(description.contains("xwiki:"), description);
    }
}
