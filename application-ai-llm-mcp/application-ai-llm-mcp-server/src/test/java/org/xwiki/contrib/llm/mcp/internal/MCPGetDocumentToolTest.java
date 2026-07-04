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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.security.authorization.Right;
import org.xwiki.sheet.SheetManager;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.xml.html.DefaultHTMLCleanerComponentList;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
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
@DefaultHTMLCleanerComponentList
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
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private MCPServerConfiguration mcpConfig;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private MCPWikiReach wikiReach;

    @MockComponent
    private SheetManager sheetManager;

    @Mock
    private DocumentReference documentReference;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW)))
            .thenReturn(this.documentReference);
        when(this.serializer.serialize(any())).thenReturn(CANONICAL);
        // Same-wiki by default: the resolved reference lives in the endpoint's own wiki. The cross-wiki gate
        // test overrides this with the target wiki. Lenient so non-rendered tests, which never read the ref
        // wiki, do not fail on an unused stub.
        lenient().when(this.documentReference.getWikiReference()).thenReturn(new WikiReference("xwiki"));
        // Rendered content is allowed by default; the disabled-rendering test overrides this. Lenient so the
        // many non-rendered tests, which never reach the gate, do not fail on an unused stub.
        lenient().when(this.mcpConfig.isRenderedContentAllowed(any())).thenReturn(true);
        // By default the endpoint has cross-wiki reach, so the advertised reference description mentions it;
        // the reach-off test overrides this.
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
        // The source-path provenance note reads the context wiki id to decide whether it may advise a
        // rendered read; rendered-mode tests override the provider with their own context.
        XWikiContext defaultContext = mock(XWikiContext.class);
        lenient().when(defaultContext.getWikiId()).thenReturn("xwiki");
        lenient().when(this.contextProvider.get()).thenReturn(defaultContext);
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
        return this.tool.execute(
            McpSchema.CallToolRequest.builder(MCPGetDocumentTool.TOOL_ID).arguments(args).build());
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
    void renderedRequestRefusedKeyingOffSourceContextWikiNotTarget() throws Exception
    {
        stubDoc("{{velocity}}opaque source{{/velocity}}", MARKDOWN_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWikiId()).thenReturn("xwiki");
        // Rendering is disabled for the SOURCE (context) wiki, so the request is refused regardless of the wiki
        // the document itself lives in.
        when(this.mcpConfig.isRenderedContentAllowed("xwiki")).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "rendered", true));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Rendered content is disabled for this wiki"), textOf(result));
        // The gate consulted the source context wiki, not the target ref's wiki.
        verify(this.mcpConfig).isRenderedContentAllowed("xwiki");
        // The refusal happens before any rendering: the executed view must never be produced.
        verify(xdoc, never()).getRenderedContent(any(Syntax.class), any(XWikiContext.class));
    }

    @Test
    void renderedModeSwitchesContextWikiToTargetAndRestoresAfterRender() throws Exception
    {
        stubDoc("{{velocity}}opaque source{{/velocity}}", MARKDOWN_SYNTAX);
        // Cross-wiki read: the resolved reference lives in "targetwiki"; the endpoint's context wiki is "xwiki".
        when(this.documentReference.getWikiReference()).thenReturn(new WikiReference("targetwiki"));
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xcontext.getWikiId()).thenReturn("xwiki");
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("T");
        when(xdoc.getRenderedContent(Syntax.PLAIN_1_0, xcontext)).thenReturn("body");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "rendered", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The render runs in the target wiki: the context wiki is switched to it before rendering and restored
        // to the original wiki afterwards.
        InOrder ordered = inOrder(xcontext, xdoc);
        ordered.verify(xcontext).setWikiId("targetwiki");
        ordered.verify(xdoc).getRenderedContent(Syntax.PLAIN_1_0, xcontext);
        ordered.verify(xcontext).setWikiId("xwiki");
    }

    @Test
    void renderedPlainStackTraceFramesAreCollapsedToMarker() throws Exception
    {
        stubDoc("{{velocity}}fails{{/velocity}}", XWIKI_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("T");
        when(xdoc.getRenderedContent(Syntax.PLAIN_1_0, xcontext)).thenReturn(
            "Failed to execute the [velocity] macro. Click for details.\n\n"
                + "org.xwiki.rendering.macro.MacroExecutionException: Script rights required\n"
                + "\tat org.xwiki.rendering.A(A.java:11)\n"
                + "\tat org.xwiki.rendering.B(B.java:22)\n"
                + "\tat org.xwiki.rendering.C(C.java:33)\n"
                + "\tat org.xwiki.rendering.D(D.java:44)\n"
                + "\tat org.xwiki.rendering.E(E.java:55)\n"
                + "\tat org.xwiki.rendering.F(F.java:66)\n"
                + "\t... 12 more");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "rendered", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        // The human message and the exception header (causal chain) survive.
        assertTrue(text.contains("Failed to execute the [velocity] macro"), text);
        assertTrue(text.contains("MacroExecutionException: Script rights required"), text);
        // The frame spam is gone, replaced by a single marker.
        assertFalse(text.contains("at org.xwiki.rendering"), text);
        assertTrue(text.contains("stack frames omitted"), text);
    }

    @Test
    void renderedPlainIncidentalAtLineIsNotCollapsed() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("T");
        // Two anchored "at ...(...)" lines: a frame run below the collapse threshold of 3, so it must
        // survive verbatim rather than be replaced by a marker.
        when(xdoc.getRenderedContent(Syntax.PLAIN_1_0, xcontext)).thenReturn(
            "Recipe step one.\nat the bar(now)\nat foo.bar.baz(x)\nRecipe step two.");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "rendered", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("at the bar(now)"), text);
        assertTrue(text.contains("at foo.bar.baz(x)"), text);
        assertFalse(text.contains("stack frames omitted"), text);
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
    void formatHtmlReturnsCleanedHtmlWithBannerAndHtmlSyntax() throws Exception
    {
        stubDoc("{{velocity}}opaque source{{/velocity}}", XWIKI_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("Executed Title");
        when(xdoc.getRenderedContent(Syntax.HTML_5_0, xcontext)).thenReturn(
            "<div class=\"box warningmessage\" style=\"margin:1em\">Watch out</div>"
                + "<table><tr><td colspan=\"2\">cell</td></tr></table><script>alert(1)</script>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("RENDERED VIEW"), text);
        assertTrue(text.contains("Syntax: html/5.0"), text);
        assertTrue(text.contains("class=\"box warningmessage\""), text);
        assertTrue(text.contains("Watch out"), text);
        assertTrue(text.contains("colspan=\"2\""), text);
        assertFalse(text.contains("style="), "Presentation attributes must be stripped: " + text);
        assertFalse(text.contains("alert(1)"), "Script payload must be removed: " + text);
        verify(xdoc, never()).getRenderedContent(Syntax.PLAIN_1_0, xcontext);
    }

    @Test
    void formatValueIsCaseInsensitive() throws Exception
    {
        stubDoc("{{velocity}}opaque source{{/velocity}}", XWIKI_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("Executed Title");
        when(xdoc.getRenderedContent(Syntax.HTML_5_0, xcontext)).thenReturn("<p>body</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "HTML"));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Syntax: html/5.0"), textOf(result));
    }

    @Test
    void formatPlainMatchesDefaultRenderedOutput() throws Exception
    {
        stubDoc("{{velocity}}opaque source{{/velocity}}", XWIKI_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("Executed Title");
        when(xdoc.getRenderedContent(Syntax.PLAIN_1_0, xcontext)).thenReturn("Expanded body.");

        McpSchema.CallToolResult defaultResult = call(Map.of(REFERENCE_KEY, REF, "rendered", true));
        McpSchema.CallToolResult plainResult =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "plain"));

        assertNotEquals(Boolean.TRUE, plainResult.isError());
        assertEquals(textOf(defaultResult), textOf(plainResult));
        verify(xdoc, never()).getRenderedContent(Syntax.HTML_5_0, xcontext);
    }

    @Test
    void formatWithoutRenderedReturnsError() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "format", "html"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Error: 'format' requires rendered=true."), textOf(result));
    }

    @Test
    void formatInvalidValueReturnsAllowlistError() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "xml"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Error: 'format' must be one of: plain, html."), textOf(result));
    }

    @Test
    void formatIsDeclaredDirectlyAfterRenderedInSchema()
    {
        Map<?, ?> properties = (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");
        List<Object> keys = new ArrayList<>(properties.keySet());
        assertEquals(keys.indexOf("rendered") + 1, keys.indexOf("format"),
            "format must sit next to rendered in the advertised schema: " + keys);
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
    void outlineWithNoHeadingsAppendsRenderedOutlineHint() throws Exception
    {
        stubDoc("no heading lines here\njust prose", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No headings found"), text);
        assertTrue(text.contains("Sparse outline; if this page is script-driven (macros/Velocity), try "
            + "rendered=true, format=\"html\", outline=true for the executed view's outline."), text);
    }

    @Test
    void outlineWithOneHeadingAppendsRenderedOutlineHint() throws Exception
    {
        stubDoc("= Only Heading =\nbody", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("L1: Only Heading"), text);
        assertTrue(text.contains("Sparse outline"), text);
    }

    @Test
    void sparseOutlineHintOmittedWhenRenderingDisabledAtOneHeading() throws Exception
    {
        stubDoc("= Only Heading =\nbody", XWIKI_SYNTAX);
        when(this.mcpConfig.isRenderedContentAllowed("xwiki")).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("L1: Only Heading"), text);
        assertFalse(text.contains("Sparse outline"),
            "The hint must not steer into the rendered-disabled refusal: " + text);
    }

    @Test
    void sparseOutlineHintOmittedWhenRenderingDisabledAtNoHeadings() throws Exception
    {
        stubDoc("no heading lines here\njust prose", XWIKI_SYNTAX);
        when(this.mcpConfig.isRenderedContentAllowed("xwiki")).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No headings found"), text);
        assertFalse(text.contains("Sparse outline"),
            "The hint must not steer into the rendered-disabled refusal: " + text);
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
        assertFalse(text.contains("Sparse outline"), "Two or more headings are not a sparse outline: " + text);
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
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to view \"" + REF + "\"."));

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
    void nonExistentDocumentSuggestsSpaceHomeWhenItExists() throws Exception
    {
        DocumentReference spaceHomeReference = mock(DocumentReference.class);
        // The requested terminal document does not exist, but reinterpreting it as a space, its WebHome does.
        when(this.documentReference.getName()).thenReturn("GettingStarted");
        when(this.documentAccessBridge.exists(this.documentReference)).thenReturn(false);
        when(this.documentAccess.resolveAndAuthorize(CANONICAL + ".WebHome", Right.VIEW))
            .thenReturn(spaceHomeReference);
        when(this.documentAccessBridge.exists(spaceHomeReference)).thenReturn(true);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No such document"), text);
        assertTrue(text.contains("Did you mean \"" + CANONICAL + ".WebHome\""), text);
    }

    @Test
    void nonExistentDocumentOmitsSuggestionWhenSpaceHomeDoesNotExist() throws Exception
    {
        // Neither the requested document nor its would-be space home exists: no suggestion.
        when(this.documentReference.getName()).thenReturn("GettingStarted");
        when(this.documentAccessBridge.exists(any(DocumentReference.class))).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertFalse(textOf(result).contains("Did you mean"), textOf(result));
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
        assertTrue(((List<?>) definition.inputSchema().get("required")).contains(REFERENCE_KEY));
    }

    @Test
    void reachOnMentionsCrossWikiInReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);

        assertTrue(referenceDescription().contains("cross-wiki"), referenceDescription());
        assertTrue(referenceDescription().contains("\"xwiki:Sandbox.WebHome\""), referenceDescription());
    }

    @Test
    void reachOffDropsCrossWikiFromReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        assertFalse(referenceDescription().contains("cross-wiki"), referenceDescription());
        // The examples must be prefix-free so an agent on a reach-off endpoint never tries a prefixed ref.
        assertTrue(referenceDescription().contains("\"Sandbox.WebHome\""), referenceDescription());
        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertFalse(definition.inputSchema().toString().contains("xwiki:"),
            "Reach-off advertised schema must not contain wiki-prefixed examples");
        assertFalse(definition.description().contains("xwiki:"), definition.description());
    }

    private String referenceDescription()
    {
        Map<?, ?> properties = (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");
        Map<?, ?> reference = (Map<?, ?>) properties.get(REFERENCE_KEY);
        return (String) reference.get("description");
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

    private void stubRenderedHtml(String html) throws Exception
    {
        stubDoc("{{velocity}}opaque source{{/velocity}}", XWIKI_SYNTAX);
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("Executed Title");
        when(xdoc.getRenderedContent(Syntax.HTML_5_0, xcontext)).thenReturn(html);
    }

    private XWikiDocument stubEmptyBodyDocWithXObjects() throws Exception
    {
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getContent()).thenReturn("");
        lenient().when(doc.getTitle()).thenReturn("T");
        when(doc.getVersion()).thenReturn("1.1");
        when(doc.getDocumentReference()).thenReturn(this.documentReference);
        Syntax syntax = mock(Syntax.class);
        lenient().when(syntax.toIdString()).thenReturn(XWIKI_SYNTAX);
        when(doc.getSyntax()).thenReturn(syntax);
        // One deleted-object null slot next to a live object: the presence check must tolerate it.
        when(doc.getXObjects()).thenReturn(
            Map.of(mock(DocumentReference.class), Arrays.asList(null, mock(BaseObject.class))));
        when(this.documentAccessBridge.exists(any(DocumentReference.class))).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(any(DocumentReference.class))).thenReturn(doc);
        return doc;
    }

    @Test
    void sectionWithoutRenderedReturnsError() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "section", "#HFoo"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("requires rendered=true and format=\"html\""), textOf(result));
    }

    @Test
    void sectionWithRenderedPlainReturnsError() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "section", "#HFoo"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("requires rendered=true and format=\"html\""), textOf(result));
    }

    @Test
    void offsetWithHtmlFormatReturnsSteeringError() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "offset", 2));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("do not apply to format=\"html\""), text);
        assertTrue(text.contains("outline=true"), text);
    }

    @Test
    void limitWithHtmlFormatReturnsSteeringError() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "limit", 10));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("do not apply to format=\"html\""), textOf(result));
    }

    @Test
    void sectionCombinedWithOutlineReturnsError() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(
            Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#HFoo", "outline", true));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("cannot be combined with outline=true"), textOf(result));
    }

    @Test
    void blankSectionReturnsError() throws Exception
    {
        stubDoc("source", XWIKI_SYNTAX);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("must be a heading anchor"), textOf(result));
    }

    @Test
    void htmlOutlineListsDomAnchorsInsteadOfRegexHeadings() throws Exception
    {
        stubRenderedHtml("<h1 id=\"HTop\">Top</h1><p>body text</p><div><h2 id=\"HSub\">Sub</h2></div>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("#HTop: Top"), text);
        assertTrue(text.contains("  #HSub: Sub"), text);
        assertTrue(text.contains("tokens"), text);
        assertFalse(text.contains("body text"), "Outline must not include the content: " + text);
        assertFalse(text.contains("L1:"), "DOM outline must replace the line-regex outline: " + text);
    }

    @Test
    void htmlOutlineWithoutHeadingsSaysSo() throws Exception
    {
        stubRenderedHtml("<p>plain paragraph</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "outline", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("No headings found in the rendered HTML."), textOf(result));
    }

    @Test
    void largeRenderedHtmlAutoDegradesToDomOutline() throws Exception
    {
        stubRenderedHtml("<h2 id=\"HBig\">Big</h2><p>" + "lorem ".repeat(6000) + "</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("OUTLINE (a map of heading anchors)"), text);
        assertTrue(text.contains("#HBig: Big"), text);
        assertTrue(text.contains("section=\"#H...\""), text);
        assertFalse(text.contains("lorem"), "Auto-degraded outline must not include the content");
    }

    @Test
    void largeHeadinglessRenderedHtmlReturnsWholeBodyChunkMap() throws Exception
    {
        stubRenderedHtml("<p>ALPHA " + "lorem ".repeat(3000) + "</p><p>BETA " + "lorem ".repeat(3000)
            + "</p><p>GAMMA " + "lorem ".repeat(3000) + "</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Section \"#(intro)\" is ~"), text);
        assertTrue(text.contains("CHUNK MAP, NOT its content"), text);
        assertTrue(text.contains("section=\"#((intro)/K)\""), text);
        assertTrue(text.contains("#((intro)/1): "), text);
        assertTrue(text.contains("this map: version 3.1"), text);
        assertFalse(text.contains("cannot be read by section"),
            "The honest-footer capped head is replaced by the whole-body chunk map: " + text);
        assertFalse(text.contains("     1\t"), "A chunk map must not carry numbered content: " + text);
        assertFalse(text.contains("continue with offset="),
            "The line-based continuation hint must not appear for rendered HTML: " + text);
    }

    @Test
    void htmlSectionReturnsOnlyThatSectionWithHeaderAndFooter() throws Exception
    {
        stubRenderedHtml("<h2 id=\"HA\">Alpha</h2><p>alpha-body</p><h2 id=\"HB\">Beta</h2><p>beta-body</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#HA"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("alpha-body"), text);
        assertFalse(text.contains("beta-body"), text);
        assertTrue(text.contains("Section: #HA (document total ~"), text);
        assertTrue(text.contains("Showing section \"#HA\""), text);
        assertTrue(text.contains("     1\t"), "Section body must stay cat -n shaped: " + text);
    }

    @Test
    void htmlSectionAcceptsAnchorWithoutLeadingHash() throws Exception
    {
        stubRenderedHtml("<h2 id=\"HA\">Alpha</h2><p>alpha-body</p><h2 id=\"HB\">Beta</h2><p>beta-body</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "HA"));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("alpha-body"), textOf(result));
    }

    @Test
    void unknownSectionAnchorErrorEmbedsAvailableOutline() throws Exception
    {
        stubRenderedHtml("<h2 id=\"HA\">Alpha</h2><p>alpha-body</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#HNope"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No section with anchor \"#HNope\""), text);
        assertTrue(text.contains("Available sections:"), text);
        assertTrue(text.contains("#HA: Alpha"), text);
    }

    @Test
    void sectionOnHeadinglessRenderedHtmlSaysNoAnchors() throws Exception
    {
        stubRenderedHtml("<p>no headings here</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#HX"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("no heading anchors"), textOf(result));
    }

    @Test
    void overBudgetSectionDegradesToItsSubOutline() throws Exception
    {
        stubRenderedHtml("<h1 id=\"HTop\">Top</h1><h2 id=\"HSub\">Sub</h2><p>"
            + "lorem ".repeat(6000) + "</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#HTop"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("over the ~6000-token budget"), text);
        assertTrue(text.contains("sub-outline"), text);
        assertTrue(text.contains("#HSub: Sub"), text);
        assertFalse(text.contains("lorem"), "Sub-outline response must not include the content");
    }

    @Test
    void overBudgetLeafSectionReturnsChunkMapInsteadOfCappedHead() throws Exception
    {
        stubRenderedHtml("<h1 id=\"HTop\">Top</h1><p>PARA1 " + "lorem ".repeat(3000) + "</p><p>PARA2 "
            + "lorem ".repeat(3000) + "</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#HTop"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Section \"#HTop\" is ~"), text);
        assertTrue(text.contains("over the ~6000-token budget"), text);
        assertTrue(text.contains("CHUNK MAP, NOT its content"), text);
        assertTrue(text.contains("read a chunk with section=\"#(HTop/K)\""), text);
        assertTrue(text.contains("Chunks are positional and shift if the document is edited "
            + "(this map: version 3.1)"), text);
        assertTrue(text.contains("#(HTop/1): "), text);
        assertTrue(text.contains("#(HTop/2): "), text);
        assertFalse(text.contains("This section has no sub-headings"),
            "The capped head survives only as the borderline and atomic-floor fallback: " + text);
    }

    private void stubOverBudgetLeafSection() throws Exception
    {
        stubRenderedHtml("<h1 id=\"HTop\">Top</h1><p>PARA1 " + "lorem ".repeat(2000) + "</p><p>PARA2 "
            + "lorem ".repeat(2000) + "</p><p>PARA3 " + "lorem ".repeat(2000) + "</p>");
    }

    @Test
    void chunkFetchReturnsOneChunkWithLocatingHeaderAndMapFooter() throws Exception
    {
        stubOverBudgetLeafSection();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#(HTop/2)"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Chunk: #(HTop/2) of section #HTop (chunk 2 of 3, section ~"), text);
        assertTrue(text.contains("PARA2"), text);
        assertFalse(text.contains("PARA1"), "Chunk 2 must not contain chunk 1 content: " + text);
        assertFalse(text.contains("PARA3"), "Chunk 2 must not contain chunk 3 content: " + text);
        assertTrue(text.contains("     1\t"), "Chunk body must stay cat -n shaped: " + text);
        assertTrue(text.contains("Showing chunk 2 of 3"), text);
        assertTrue(text.contains("section=\"#(HTop/map1)\""), text);
    }

    @Test
    void outOfRangeChunkOrdinalReEmbedsMapPageOne() throws Exception
    {
        stubOverBudgetLeafSection();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#(HTop/9)"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No chunk 9 in section \"#HTop\"; valid chunks: 1-3."), text);
        assertTrue(text.contains("CHUNK MAP"), text);
        assertTrue(text.contains("#(HTop/1): "), text);
    }

    @Test
    void outOfRangeChunkOnUnderBudgetSectionGetsNeutralMapProse() throws Exception
    {
        // The re-embedded map of a within-budget section (e.g. a stale anchor after the section shrank)
        // must not claim the section exceeds the budget; it offers the whole-section fetch instead.
        stubRenderedHtml("<h1 id=\"HTop\">Top</h1><p>small body</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#(HTop/5)"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No chunk 5 in section \"#HTop\"; valid chunks: 1-1."), text);
        assertTrue(text.contains("This is the CHUNK MAP of section \"#HTop\" (~"), text);
        assertFalse(text.contains("over the ~6000-token budget"),
            "An under-budget section's map must not claim it exceeds the budget: " + text);
        assertTrue(text.contains("or the whole section (it fits the ~6000-token budget) with "
            + "section=\"#HTop\""), text);
    }

    @Test
    void mapPageAnchorReturnsTheChunkMap() throws Exception
    {
        stubOverBudgetLeafSection();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#(HTop/map1)"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("CHUNK MAP"), text);
        assertTrue(text.contains("#(HTop/3): "), text);
        // The estimated Size line is attributed to the section, exactly as on the auto map path.
        assertTrue(text.contains("Section: #HTop (document total ~"), text);
        assertFalse(text.contains("Chunk map truncated"),
            "A single-page map must not claim truncation: " + text);
    }

    @Test
    void outOfRangeMapPageReEmbedsMapPageOne() throws Exception
    {
        stubOverBudgetLeafSection();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#(HTop/map5)"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No page 5 in the chunk map of section \"#HTop\"; valid map pages: 1-1."),
            text);
        assertTrue(text.contains("#(HTop/1): "), text);
    }

    @Test
    void borderlineSectionWhoseSerializationOverflowsEmitsCappedHeadFallback() throws Exception
    {
        // The walk estimates the emoji at two UTF-16 units, but the serializer emits it as a ten-char
        // numeric character reference: the section's estimate fits the budget while its serialized
        // form exceeds it, exercising the capped-head fallback behind the chunk map.
        stubRenderedHtml("<h1 id=\"HTop\">Top</h1><p>" + "😀".repeat(4000) + "</p>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#HTop"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("[Output truncated at the ~"), text);
        // Chunk anchors resolve on a fresh request even here (the partition is estimate-driven), so the
        // footer steers to chunk 1 instead of claiming the section cannot be split.
        assertTrue(text.contains("Read it in chunks with section=\"#(HTop/1)\""), text);
        assertFalse(text.contains("cannot be split further"),
            "The cannot-split claim is reserved for the atomic-floor chunk fetch");
        assertFalse(text.contains("CHUNK MAP"), text);
    }

    @Test
    void atomicFloorChunkFetchEmitsCappedHeadWithCannotSplitFooter() throws Exception
    {
        // A single childless <pre> far over the output cap partitions into one atomic-floor chunk;
        // fetching it emits the capped head under the locating Chunk header, and here the static
        // cannot-be-split-further footer is genuinely true.
        stubRenderedHtml("<pre>ZZHEAD " + "x".repeat(30000) + " ZZTAIL</pre>");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html", "section", "#((intro)/1)"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Chunk: #((intro)/1) of section #(intro) (chunk 1 of 1"), text);
        assertTrue(text.contains("ZZHEAD"), text.substring(0, 400));
        assertFalse(text.contains("ZZTAIL"), "The oversized atomic chunk must be capped at the budget");
        assertTrue(text.contains("[Output truncated at the ~"), text.substring(text.length() - 400));
        assertTrue(text.contains("cannot be split further"), text.substring(text.length() - 400));
    }

    @Test
    void truncatedHtmlNeverEndsInASplitSurrogatePair() throws Exception
    {
        // The serializer emits supplementary characters as numeric character references (&#x1f600;), so
        // the truncation sites operate on surrogate-free strings; this pins that invariant (and the
        // cappedHead backstop) so a serializer change cannot silently start emitting malformed UTF-16.
        stubRenderedHtml("<p>" + "😀".repeat(4000) + "</p>");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "rendered", true, "format", "html"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("&#x1f600;"),
            "Expected supplementary characters as character references: " + text.substring(0, 400));
        int footerIndex = text.indexOf("\n[Output truncated");
        assertTrue(footerIndex > 0, text.substring(text.length() - 400));
        assertFalse(Character.isHighSurrogate(text.charAt(footerIndex - 1)),
            "Truncation must not split a surrogate pair");
    }

    @Test
    void sourceReadOfEmptyBodySheetPageCarriesSheetNote() throws Exception
    {
        DocumentModelBridge doc = stubDoc("", XWIKI_SYNTAX);
        DocumentReference sheetRef = mock(DocumentReference.class);
        when(this.sheetManager.getSheets(doc, "view")).thenReturn(List.of(sheetRef));
        when(this.documentAccessBridge.isDocumentViewable(sheetRef)).thenReturn(true);
        when(this.serializer.serialize(sheetRef)).thenReturn("xwiki:XWiki.XWikiUserSheet");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("produced by the sheet \"xwiki:XWiki.XWikiUserSheet\""), text);
        assertTrue(text.contains("editing the body will not change what users see"), text);
        assertTrue(text.contains("Document has no content."), text);
    }

    @Test
    void sourceSheetNoteOmitsRenderedAdviceWhenRenderingDisabled() throws Exception
    {
        DocumentModelBridge doc = stubDoc("", XWIKI_SYNTAX);
        DocumentReference sheetRef = mock(DocumentReference.class);
        when(this.sheetManager.getSheets(doc, "view")).thenReturn(List.of(sheetRef));
        when(this.documentAccessBridge.isDocumentViewable(sheetRef)).thenReturn(true);
        when(this.serializer.serialize(sheetRef)).thenReturn("xwiki:XWiki.XWikiUserSheet");
        when(this.mcpConfig.isRenderedContentAllowed(any())).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("produced by the sheet \"xwiki:XWiki.XWikiUserSheet\""), text);
        assertTrue(text.contains("Editing the body will not change what users see."), text);
        assertFalse(text.contains("rendered=true"),
            "The note must not advise a rendered read when rendering is disabled: " + text);
    }

    @Test
    void renderedPlainReadOfEmptyBodySheetPageCarriesMirrorNoteWithHtmlHint() throws Exception
    {
        DocumentModelBridge doc = stubDoc("", XWIKI_SYNTAX);
        DocumentReference sheetRef = mock(DocumentReference.class);
        when(this.sheetManager.getSheets(doc, "view")).thenReturn(List.of(sheetRef));
        when(this.documentAccessBridge.isDocumentViewable(sheetRef)).thenReturn(true);
        when(this.serializer.serialize(sheetRef)).thenReturn("xwiki:XWiki.XWikiUserSheet");
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        when(this.contextProvider.get()).thenReturn(xcontext);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(this.documentReference, xcontext)).thenReturn(xdoc);
        when(xdoc.getRenderedTitle(Syntax.PLAIN_1_0, xcontext)).thenReturn("T");
        when(xdoc.getRenderedContent(Syntax.PLAIN_1_0, xcontext)).thenReturn("profile view");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, "rendered", true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("this view is produced by the sheet \"xwiki:XWiki.XWikiUserSheet\""), text);
        assertTrue(text.contains("body edits will not change it"), text);
        // The plain renderer drops a sheet's raw-HTML output, so the plain path adds the format hint.
        assertTrue(text.contains("use format=\"html\""), text);
    }

    @Test
    void emptyBodyWithXObjectsButNoSheetCarriesStructuredDataNote() throws Exception
    {
        stubEmptyBodyDocWithXObjects();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("lives in its structured data (xobjects)"), textOf(result));
    }

    @Test
    void emptyBodyWithoutSheetOrXObjectsCarriesNoNote() throws Exception
    {
        stubDoc("", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertFalse(textOf(result).contains("Note: the body source is empty"), textOf(result));
    }

    @Test
    void sheetManagerIsNeverConsultedWhenBodyHasContent() throws Exception
    {
        stubDoc("some content", XWIKI_SYNTAX);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        verify(this.sheetManager, never()).getSheets(any(), anyString());
    }

    @Test
    void sheetLookupFailureIsSwallowedAndFallsBackToXObjectsNote() throws Exception
    {
        XWikiDocument doc = stubEmptyBodyDocWithXObjects();
        when(this.sheetManager.getSheets(doc, "view")).thenThrow(new RuntimeException("sheet boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("lives in its structured data (xobjects)"), text);
        assertFalse(text.contains("sheet boom"), "Lookup failure must not leak: " + text);
    }

    @Test
    void nonViewableSheetFallsBackToXObjectsNote() throws Exception
    {
        XWikiDocument doc = stubEmptyBodyDocWithXObjects();
        DocumentReference sheetRef = mock(DocumentReference.class);
        when(this.sheetManager.getSheets(doc, "view")).thenReturn(List.of(sheetRef));
        when(this.documentAccessBridge.isDocumentViewable(sheetRef)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertFalse(text.contains("produced by the sheet \""),
            "A sheet the caller may not view must never be named: " + text);
        assertTrue(text.contains("lives in its structured data (xobjects)"), text);
    }

    @Test
    void sectionIsDeclaredDirectlyAfterFormatInSchema()
    {
        Map<?, ?> properties = (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");
        List<Object> keys = new ArrayList<>(properties.keySet());
        assertEquals(keys.indexOf("format") + 1, keys.indexOf("section"),
            "section must sit next to format in the advertised schema: " + keys);
    }

    @Test
    void descriptionMentionsOutlineSectionWorkflowForHtml()
    {
        String description = this.tool.getToolDefinition().description();
        assertTrue(description.contains("offset/limit do not apply"), description);
        assertTrue(description.contains("section=\"#H...\""), description);
    }

    @Test
    void manPageDocumentsHtmlOutlineSectionAndEmptyBodyNotes()
    {
        String manPage = this.tool.getManPage();
        assertTrue(manPage.contains("#(intro)"), manPage);
        assertTrue(manPage.contains("offset/limit do not apply"), manPage);
        assertTrue(manPage.contains("NOTES"), manPage);
        assertTrue(manPage.contains("xobjects"), manPage);
    }

    @Test
    void manPageDocumentsChunkFetchWhileDescriptionStaysChunkFree()
    {
        String manPage = this.tool.getManPage();
        assertTrue(manPage.contains("CHUNK MAP"), manPage);
        assertTrue(manPage.contains("section=\"#((h3)/2)\""), manPage);
        // Chunking is discoverable through the map responses; the always-paid tool description must
        // not grow for it.
        String description = this.tool.getToolDefinition().description();
        assertFalse(description.contains("chunk"), description);
        assertFalse(description.contains("Chunk"), description);
    }
}
