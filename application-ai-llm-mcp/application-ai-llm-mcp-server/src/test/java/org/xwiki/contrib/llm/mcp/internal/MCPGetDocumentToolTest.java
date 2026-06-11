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

import java.util.Map;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPGetDocumentTool}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPGetDocumentToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String REF = "Help.GettingStarted";

    private static final String CANONICAL = "xwiki:Help.GettingStarted";

    private static final String XWIKI_SYNTAX = "xwiki/2.1";

    private static final String MARKDOWN_SYNTAX = "markdown/1.1";

    private static final String PLAIN_SYNTAX = "plain/1.0";

    private static final String VIEW_URL = "https://wiki.example/bin/view/Space/Page";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPGetDocumentTool tool;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private ContextualAuthorizationManager authorization;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @Mock
    private DocumentReference documentReference;

    @BeforeEach
    void setUp()
    {
        when(this.referenceResolver.resolve(anyString())).thenReturn(this.documentReference);
        when(this.authorization.hasAccess(eq(Right.VIEW), any())).thenReturn(true);
        when(this.serializer.serialize(any())).thenReturn(CANONICAL);
    }

    private DocumentModelBridge stubDoc(String content, String syntaxId) throws Exception
    {
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        lenient().when(doc.getContent()).thenReturn(content);
        lenient().when(doc.getTitle()).thenReturn("Getting Started");
        lenient().when(doc.getVersion()).thenReturn("3.1");
        lenient().when(doc.getDocumentReference()).thenReturn(this.documentReference);
        Syntax syntax = mock(Syntax.class);
        lenient().when(syntax.toIdString()).thenReturn(syntaxId);
        lenient().when(doc.getSyntax()).thenReturn(syntax);
        when(this.documentAccessBridge.exists(any(DocumentReference.class))).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(any(DocumentReference.class))).thenReturn(doc);
        lenient().when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), isNull(), isNull(), eq(true)))
            .thenReturn(VIEW_URL);
        return doc;
    }

    private static String textOf(McpSchema.CallToolResult result)
    {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private McpSchema.CallToolResult call(Map<String, Object> args)
    {
        return this.tool.execute(new McpSchema.CallToolRequest(MCPGetDocumentTool.TOOL_ID, args));
    }

    @Test
    void renderedModeReturnsExecutedPlainTextWithBannerAndRenderedSyntax() throws Exception
    {
        // Source syntax is markdown, but rendered output is plain text - the header must report the rendered
        // syntax (plain/1.0), and the body must be the executed render, not the raw source.
        stubDoc("{{velocity}}opaque source{{/velocity}}", MARKDOWN_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("Executed Title");
        when(xdoc.getRenderedContent(Syntax.PLAIN_1_0, xcontext)).thenReturn("Rendered heading\n\nExpanded body.");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "rendered", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("RENDERED VIEW"), text);
        assertTrue(text.contains("Syntax: " + PLAIN_SYNTAX), text);
        // The header title is the rendered title, not the doc's raw getTitle().
        assertTrue(text.contains("Title: Executed Title"), text);
        assertTrue(text.contains("Rendered heading"), text);
        assertTrue(text.contains("Expanded body."), text);
        assertFalse(text.contains("opaque source"), "Rendered mode must not return the raw source");
    }

    @Test
    void renderedModeRenderFailureReturnsErrorAndLogsWarning() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext))
            .thenThrow(new XWikiException(0, 0, "boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "rendered", true));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the document"), textOf(result));
        assertTrue(this.logCapture.getMessage(0).contains("MCP get_document tool failed to render"));
    }

    @Test
    void smallDocumentReturnsFullNumberedBodyWithHeader() throws Exception
    {
        stubDoc("Line one\nLine two\nLine three", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Reference: " + CANONICAL), text);
        assertTrue(text.contains("Title: Getting Started"));
        assertTrue(text.contains("Syntax: " + XWIKI_SYNTAX));
        assertTrue(text.contains("Version: 3.1"));
        assertTrue(text.contains("3 lines"));
        assertTrue(text.contains("     1\tLine one"));
        assertTrue(text.contains("     3\tLine three"));
    }

    @Test
    void largeDocumentDegradesToOutlineWithMapWarningAndNoBody() throws Exception
    {
        String filler = "padding line that makes the document large\n".repeat(600);
        String content = "= Top =\n" + filler + "== Sub ==\n" + filler;
        stubDoc(content, XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("OUTLINE"), text);
        assertTrue(text.contains("NOT its content"));
        assertTrue(text.contains("L1: Top"));
        assertTrue(text.contains("Sub"));
        assertFalse(text.contains("padding line that makes the document large"),
            "Large doc must not include the body content");
    }

    @Test
    void largeHeadinglessReturnsHeadWindow() throws Exception
    {
        String content = "plain content line with no heading marker\n".repeat(700);
        stubDoc(content, XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("     1\tplain content line with no heading marker"), text);
        assertTrue(text.contains("continue with offset="), text);
        assertTrue(text.contains("no headings"), text);
        assertFalse(text.contains("No headings found; read with offset/limit."),
            "Auto-degrade must emit a head window, not the empty outline message");
        assertFalse(text.contains("   700\tplain content line with no heading marker"),
            "The window must be truncated below the last line");
    }

    @Test
    void explicitOutlineOnHeadinglessLargeStillSaysNoHeadings() throws Exception
    {
        String content = "plain content line with no heading marker\n".repeat(200);
        stubDoc(content, XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No headings found"), text);
        assertFalse(text.contains("\tplain content line with no heading marker"),
            "Explicit outline path must not dump body content");
    }

    @Test
    void outlineTitleStripsStyleAndLinkMarkup() throws Exception
    {
        stubDoc("= (% class=\"card-title\" %)[[Installation>>Documentation.AdminGuide.Installation.WebHome]](%%) =",
            XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("L1: Installation"), text);
        assertFalse(text.contains("(%"), text);
        assertFalse(text.contains("%)"), text);
        assertFalse(text.contains("[["), text);
        assertFalse(text.contains(">>"), text);
    }

    @Test
    void outlineTitleKeepsLinkTargetWhenNoLabel() throws Exception
    {
        stubDoc("= [[Documentation.AdminGuide.Authentication.WebHome]] =", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("L1: Documentation.AdminGuide.Authentication.WebHome"), text);
        assertFalse(text.contains("[["), text);
        assertFalse(text.contains("]]"), text);
    }

    @Test
    void outlineTitlePlainHeadingUnchanged() throws Exception
    {
        stubDoc("== Algorithm ==", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("L1: Algorithm"), text);
    }

    @Test
    void explicitRangeReturnsAbsoluteNumberedSliceWithFooter() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                sb.append('\n');
            }
            sb.append("content-").append(i);
        }
        stubDoc(sb.toString(), XWIKI_SYNTAX);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "offset", 3, "limit", 2));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("     3\tcontent-3"), text);
        assertTrue(text.contains("     4\tcontent-4"));
        assertFalse(text.contains("content-2"));
        assertFalse(text.contains("content-5"));
        assertTrue(text.contains("Showing lines 3-4 of 10."));
    }

    @Test
    void offsetOneNoLimitReturnsFullDocumentEvenWhenLarge() throws Exception
    {
        String content = "x line here\n".repeat(500);
        stubDoc(content, XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "offset", 1));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("     1\tx line here"));
        assertTrue(text.contains("   500\tx line here"));
        assertFalse(text.contains("OUTLINE"));
    }

    @Test
    void outlineTrueReturnsHeadingsWithLineMarkers() throws Exception
    {
        stubDoc("= Intro =\nbody\n== Details ==\nmore", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("L1: Intro"), text);
        assertTrue(text.contains("L3: Details"));
        assertFalse(text.contains("body"));
    }

    @Test
    void markdownHeadingsAreDetected() throws Exception
    {
        stubDoc("# Title\ntext\n## Section", MARKDOWN_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        String text = textOf(result);
        assertTrue(text.contains("L1: Title"), text);
        assertTrue(text.contains("L3: Section"));
    }

    @Test
    void accessDeniedReturnsErrorWithoutLoadingDocument() throws Exception
    {
        when(this.authorization.hasAccess(eq(Right.VIEW), any())).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void nonExistentDocumentReturnsNotFound() throws Exception
    {
        when(this.documentAccessBridge.exists(any(DocumentReference.class))).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("No such document"), textOf(result));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void existenceCheckFailureIsHandled() throws Exception
    {
        when(this.documentAccessBridge.exists(any(DocumentReference.class)))
            .thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the document"));
        assertFalse(textOf(result).contains("boom"), "Root cause must not leak into the agent-facing message");
        assertTrue(this.logCapture.getMessage(0).contains("MCP get_document tool failed to check existence"));
        assertTrue(this.logCapture.getMessage(0).contains("boom"));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void documentLoadFailureReturnsErrorAndLogsWarning() throws Exception
    {
        when(this.documentAccessBridge.exists(any(DocumentReference.class))).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(any(DocumentReference.class)))
            .thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the document"));
        assertFalse(textOf(result).contains("boom"), "Root cause must not leak into the agent-facing message");
        assertTrue(this.logCapture.getMessage(0).contains("MCP get_document tool failed to load"));
        assertTrue(this.logCapture.getMessage(0).contains("boom"));
    }

    @Test
    void loadFailureWarnMessageHasNoStackTrace() throws Exception
    {
        when(this.documentAccessBridge.exists(any(DocumentReference.class))).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(any(DocumentReference.class)))
            .thenThrow(new RuntimeException("boom"));

        call(Map.of(REFERENCE_KEY, REF));

        String warn = this.logCapture.getMessage(0);
        assertFalse(warn.contains("\tat "), "WARN message must not contain a stack frame");
        assertFalse(warn.contains("at org."), "WARN message must not contain a stack frame");
    }

    @Test
    void offsetPastEndReturnsError() throws Exception
    {
        stubDoc("only one line", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "offset", 99));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("exceeds document length"));
    }

    @Test
    void offsetBelowOneReturnsError() throws Exception
    {
        stubDoc("a\nb", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "offset", 0));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("must be >= 1"));
    }

    @Test
    void emptyContentReportsNoContent() throws Exception
    {
        stubDoc("", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Document has no content."));
        assertTrue(text.contains("0 lines"));
    }

    @Test
    void missingReferenceReturnsError()
    {
        McpSchema.CallToolResult result = call(Map.of());

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains(REFERENCE_KEY));
    }

    @Test
    void nullSyntaxIsReportedAsUnknown() throws Exception
    {
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getContent()).thenReturn("a line");
        when(doc.getTitle()).thenReturn("T");
        when(doc.getVersion()).thenReturn("1.1");
        when(doc.getDocumentReference()).thenReturn(this.documentReference);
        when(doc.getSyntax()).thenReturn(null);
        when(this.documentAccessBridge.exists(any(DocumentReference.class))).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(any(DocumentReference.class))).thenReturn(doc);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertTrue(textOf(result).contains("Syntax: unknown"));
    }

    @Test
    void toolDefinitionRequiresReference()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertEquals(MCPGetDocumentTool.TOOL_ID, definition.name());
        assertTrue(definition.inputSchema().required().contains(REFERENCE_KEY));
    }

    @Test
    void isEnabledReturnsTrueByDefault()
    {
        assertTrue(this.tool.isEnabled());
    }

    @Test
    void limitZeroReturnsError() throws Exception
    {
        stubDoc("a\nb\nc", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "offset", 1, "limit", 0));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("limit"), text);
        assertTrue(text.contains(">= 1"));
    }

    @Test
    void limitNegativeReturnsError() throws Exception
    {
        stubDoc("a\nb\nc", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "offset", 1, "limit", -1));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("limit"), text);
        assertTrue(text.contains(">= 1"));
    }

    @Test
    void nonNumericOffsetReturnsError() throws Exception
    {
        stubDoc("a\nb\nc", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "offset", "abc"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("integer"), textOf(result));
    }

    @Test
    void nonBooleanOutlineReturnsError() throws Exception
    {
        stubDoc("a\nb\nc", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", 42));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("boolean"), textOf(result));
    }

    @Test
    void rangeFooterClampsAtEof() throws Exception
    {
        stubDoc("l1\nl2\nl3\nl4\nl5", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "offset", 2, "limit", 999));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("     2\tl2"), text);
        assertTrue(text.contains("     5\tl5"));
        assertTrue(text.contains("Showing lines 2-5 of 5"));
    }

    @Test
    void outlineUnavailableForUnknownSyntax() throws Exception
    {
        stubDoc("<p>not a heading</p>", "html/5.0");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertTrue(textOf(result).contains("Outline unavailable for syntax"), textOf(result));
    }

    @Test
    void hugeLimitClampsWithoutOverflow() throws Exception
    {
        stubDoc("a\nb\nc\nd", XWIKI_SYNTAX);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "offset", 1, "limit", Integer.MAX_VALUE));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("     1\ta"), text);
        assertTrue(text.contains("     4\td"));
        assertTrue(text.contains("Showing lines 1-4 of 4"));
    }

    @Test
    void fullReadTruncatesAtOutputCeiling() throws Exception
    {
        // 400 lines of ~100 chars = ~40k chars, well above the 24k output ceiling.
        StringBuilder sb = new StringBuilder();
        int totalDocLines = 400;
        for (int i = 1; i <= totalDocLines; i++) {
            if (i > 1) {
                sb.append('\n');
            }
            sb.append("LINE").append(i).append('-').append("x".repeat(95));
        }
        stubDoc(sb.toString(), XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "offset", 1));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("continue with offset="), text);
        assertFalse(text.contains("Showing lines 1-" + totalDocLines + " of " + totalDocLines),
            "Footer must report an actualEnd below the total line count");
        assertFalse(text.contains("LINE" + totalDocLines + "-"),
            "The last document line must not be present in the truncated output");
    }

    @Test
    void headerIncludesVerbatimViewUrl() throws Exception
    {
        stubDoc("Line one\nLine two", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("URL: " + VIEW_URL), text);
        int refIndex = text.indexOf("Reference: ");
        int urlIndex = text.indexOf("URL: ");
        int titleIndex = text.indexOf("Title: ");
        assertTrue(refIndex < urlIndex && urlIndex < titleIndex,
            "URL line must sit after Reference and before Title: " + text);
        assertFalse(text.contains("/xwiki/"), "No /xwiki/ prefix may be added to the factory URL: " + text);
    }

    @Test
    void urlLineOmittedWhenFactoryFails() throws Exception
    {
        stubDoc("Line one\nLine two", XWIKI_SYNTAX);
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), isNull(), isNull(), eq(true)))
            .thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertFalse(text.contains("URL:"), "URL line must be omitted when the factory fails: " + text);
        assertTrue(text.contains("Reference: " + CANONICAL), text);
        assertTrue(text.contains("Title: Getting Started"), text);
    }

    @Test
    void crlfContentIsNormalizedToLf() throws Exception
    {
        stubDoc("alpha\r\nbeta\r\ngamma", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertFalse(text.contains("\r"), "Emitted content must not contain a carriage return");
        assertTrue(text.contains("3 lines"), text);
        assertTrue(text.contains("     1\talpha"));
        assertTrue(text.contains("     3\tgamma"));
    }
}
