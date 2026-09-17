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
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.diff.display.internal.DefaultInlineDiffDisplayer;
import org.xwiki.diff.display.internal.DefaultUnifiedDiffDisplayer;
import org.xwiki.diff.display.internal.LineSplitter;
import org.xwiki.diff.internal.DefaultDiffManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.criteria.impl.RevisionCriteria;
import com.xpn.xwiki.doc.DocumentRevisionProvider;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.rcs.XWikiRCSNodeInfo;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPGetHistoryTool}, with the real {@link MCPHistorySupport} and the real diff
 * components wired in, so the diff-mode assertions pin the actual unified hunk rendering.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({MCPHistorySupport.class, MCPTranslationSupport.class, DefaultDiffManager.class,
    LineSplitter.class, DefaultUnifiedDiffDisplayer.class, DefaultInlineDiffDisplayer.class})
class MCPGetHistoryToolTest extends AbstractMCPToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String LOCALE_KEY = "locale";

    private static final String VERSION_KEY = "version";

    private static final String FROM_KEY = "from";

    private static final String TO_KEY = "to";

    private static final String OFFSET_KEY = "offset";

    private static final String LIMIT_KEY = "limit";

    private static final String WIKI_KEY = "wiki";

    private static final String REF = "Sandbox.WebHome";

    private static final String CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String CURRENT_VERSION = "3.4";

    private static final String OLD_VERSION = "2.1";

    private static final String AUTHOR = "XWiki.PaulPantiru";

    private static final DocumentReference DOC_REF = new DocumentReference("xwiki", "Sandbox", "WebHome");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPGetHistoryTool tool;

    @MockComponent
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private MCPWikiReach wikiReach;

    @MockComponent
    private DocumentRevisionProvider revisionProvider;

    private XWikiContext xcontext;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.wikiReach.resolveSingleWiki(any())).thenReturn("xwiki");
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenReturn(DOC_REF);
        lenient().when(this.serializer.serialize(any())).thenReturn(CANONICAL);
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
        this.xcontext = mock(XWikiContext.class);
        lenient().when(this.contextProvider.get()).thenReturn(this.xcontext);
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    private XWikiDocument stubDocument(String currentVersion, String content) throws Exception
    {
        XWikiDocument doc = mock(XWikiDocument.class);
        lenient().when(doc.getVersion()).thenReturn(currentVersion);
        lenient().when(doc.getContent()).thenReturn(content);
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(DOC_REF)).thenReturn(doc);
        return doc;
    }

    private static XWikiRCSNodeInfo node(String author, String comment, boolean minor, Date date)
    {
        XWikiRCSNodeInfo revisionNode = mock(XWikiRCSNodeInfo.class);
        lenient().when(revisionNode.getAuthor()).thenReturn(author);
        lenient().when(revisionNode.getComment()).thenReturn(comment);
        lenient().when(revisionNode.isMinorEdit()).thenReturn(minor);
        lenient().when(revisionNode.getDate()).thenReturn(date);
        return revisionNode;
    }

    private void stubRevisionInfo(XWikiDocument doc, String version, XWikiRCSNodeInfo revisionNode)
        throws Exception
    {
        lenient().when(doc.getRevisionInfo(eq(version), any())).thenReturn(revisionNode);
    }

    private static Date date(int hour, int minute)
    {
        return new GregorianCalendar(2026, Calendar.AUGUST, 18, hour, minute).getTime();
    }

    /**
     * @param text a possibly very long tool result
     * @return its last 400 characters, for assertion messages that must not dump a giant window
     */
    private static String tailOf(String text)
    {
        return text.substring(Math.max(0, text.length() - 400));
    }

    // ---------------------------------------------------------------- list mode

    @Test
    void listModePagesNewestFirstWithContinuationHint() throws Exception
    {
        XWikiDocument doc = stubDocument("7.1", "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(7L);
        // The store answers ascending (oldest first) for the mirrored range; the tool reverses it.
        when(doc.getRevisions(any(RevisionCriteria.class), any()))
            .thenReturn(new ArrayList<>(List.of("2.1", "3.1", "3.2")));
        stubRevisionInfo(doc, "2.1", node(AUTHOR, "first", false, date(9, 0)));
        stubRevisionInfo(doc, "3.1", node(AUTHOR, "second", false, date(9, 30)));
        stubRevisionInfo(doc, "3.2", node(AUTHOR, "third", false, date(10, 0)));

        String text = callText(Map.of(REFERENCE_KEY, REF, OFFSET_KEY, 3, LIMIT_KEY, 3));

        // The range mirrors the platform's history paging: the |limit| revisions ending (total - offset)
        // from the start, pushed into the store.
        ArgumentCaptor<RevisionCriteria> criteria = ArgumentCaptor.forClass(RevisionCriteria.class);
        verify(doc).getRevisions(criteria.capture(), any());
        assertEquals(4, criteria.getValue().getRange().getStart());
        assertEquals(-3, criteria.getValue().getRange().getSize());
        assertTrue(text.contains("History of " + CANONICAL), text);
        assertTrue(text.contains("Current version: 7.1"), text);
        assertTrue(text.contains("Total revisions: 7 (minor edits included, newest first)"), text);
        // Newest first: 3.2 before 3.1 before 2.1.
        assertTrue(text.indexOf("3.2") < text.indexOf("3.1"), text);
        assertTrue(text.indexOf("3.1") < text.indexOf(OLD_VERSION), text);
        assertTrue(text.contains("Showing revisions 4-6 of 7. Continue with offset=6."), text);
    }

    @Test
    void listModeCriteriaIncludeMinorVersionsOnBothCountAndPageReads() throws Exception
    {
        XWikiDocument doc = stubDocument("1.2", "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(2L);
        when(doc.getRevisions(any(RevisionCriteria.class), any()))
            .thenReturn(new ArrayList<>(List.of("1.1", "1.2")));
        stubRevisionInfo(doc, "1.1", node(AUTHOR, "", false, date(9, 0)));
        stubRevisionInfo(doc, "1.2", node(AUTHOR, "", true, date(9, 5)));

        callText(Map.of(REFERENCE_KEY, REF));

        // This extension's updates are minor versions unless major=true: a criteria without minor versions
        // would hide them, so both the count and the page read must opt in.
        ArgumentCaptor<RevisionCriteria> countCriteria = ArgumentCaptor.forClass(RevisionCriteria.class);
        verify(doc).getRevisionsCount(countCriteria.capture(), any());
        assertTrue(countCriteria.getValue().getIncludeMinorVersions());
        ArgumentCaptor<RevisionCriteria> pageCriteria = ArgumentCaptor.forClass(RevisionCriteria.class);
        verify(doc).getRevisions(pageCriteria.capture(), any());
        assertTrue(pageCriteria.getValue().getIncludeMinorVersions());
    }

    @Test
    void listModeRowsRenderMinorMarkerDateAuthorAndNeutralizedComment() throws Exception
    {
        XWikiDocument doc = stubDocument("3.2", "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(1L);
        when(doc.getRevisions(any(RevisionCriteria.class), any()))
            .thenReturn(new ArrayList<>(List.of("3.2")));
        // The comment and author are wiki-authored: an embedded newline would forge an extra row, and a
        // bidi override would reorder how the line displays.
        stubRevisionInfo(doc, "3.2",
            node(AUTHOR + "\u202E", "[AI] fix\nForged row: \u202Eevil", true, date(10, 15)));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertTrue(text.contains("3.2 (minor)  2026-08-18 10:15  by " + AUTHOR + "  - [AI] fixForged row: evil"),
            text);
        assertFalse(text.contains("\u202E"), text);
        assertFalse(text.contains("fix\nForged"), text);
    }

    @Test
    void listModeOffsetBeyondTotalStatesTheTotalInsteadOfAnEmptyPage() throws Exception
    {
        XWikiDocument doc = stubDocument("1.1", "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(4L);

        String text = callText(Map.of(REFERENCE_KEY, REF, OFFSET_KEY, 9));

        assertTrue(text.contains("No revisions at offset=9; this document has 4 revisions."), text);
        verify(doc, never()).getRevisions(any(RevisionCriteria.class), any());
    }

    @Test
    void listModeLastPageOmitsContinuationHint() throws Exception
    {
        XWikiDocument doc = stubDocument("2.1", "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(3L);
        when(doc.getRevisions(any(RevisionCriteria.class), any()))
            .thenReturn(new ArrayList<>(List.of("1.1")));
        stubRevisionInfo(doc, "1.1", node(AUTHOR, "start", false, date(8, 0)));

        String text = callText(Map.of(REFERENCE_KEY, REF, OFFSET_KEY, 2, LIMIT_KEY, 5));

        assertTrue(text.contains("Showing revisions 3-3 of 3."), text);
        assertFalse(text.contains("Continue with offset="), text);
    }

    @Test
    void listModeClampsTheLimitIntoItsAcceptedRange() throws Exception
    {
        XWikiDocument doc = stubDocument("5.1", "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(5L);
        when(doc.getRevisions(any(RevisionCriteria.class), any()))
            .thenReturn(new ArrayList<>(List.of("5.1")), new ArrayList<>(List.of("5.1")));

        callText(Map.of(REFERENCE_KEY, REF, LIMIT_KEY, 0));
        callText(Map.of(REFERENCE_KEY, REF, LIMIT_KEY, 1000));

        ArgumentCaptor<RevisionCriteria> criteria = ArgumentCaptor.forClass(RevisionCriteria.class);
        verify(doc, times(2)).getRevisions(criteria.capture(), any());
        // limit=0 clamps up to 1 and limit=1000 clamps down to the 100 cap, both visible in the range.
        assertEquals(-1, criteria.getAllValues().get(0).getRange().getSize());
        assertEquals(-100, criteria.getAllValues().get(1).getRange().getSize());
    }

    @Test
    void listModeRendersABareVersionRowWhenTheArchiveNodeIsMissing() throws Exception
    {
        XWikiDocument doc = stubDocument(OLD_VERSION, "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(1L);
        when(doc.getRevisions(any(RevisionCriteria.class), any()))
            .thenReturn(new ArrayList<>(List.of(OLD_VERSION)));
        // getRevisionInfo is not stubbed: the archive node resolves to null, and the row degrades to
        // the version alone instead of failing the whole page.

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertTrue(text.contains("\n\n" + OLD_VERSION + "\nShowing revisions 1-1 of 1."), text);
    }

    // ---------------------------------------------------------------- version mode

    @Test
    void versionModeReturnsBannerThenWindowedContent() throws Exception
    {
        stubDocument(CURRENT_VERSION, "current body");
        XWikiDocument revision = mock(XWikiDocument.class);
        when(revision.getContent()).thenReturn("old line one\nold line two");
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(revision);

        String text = callText(Map.of(REFERENCE_KEY, REF, VERSION_KEY, OLD_VERSION));

        assertTrue(text.startsWith("HISTORICAL SNAPSHOT of " + CANONICAL + " at version " + OLD_VERSION
            + "; the CURRENT version is " + CURRENT_VERSION + "."), text);
        assertTrue(text.contains("base_version must come from the CURRENT document (get_document)"), text);
        // The content follows the banner, line-numbered like get_document.
        assertTrue(text.contains("     1\told line one\n     2\told line two"), text);
        assertTrue(text.contains("Showing lines 1-2 of 2."), text);
    }

    @Test
    void versionModeMissShowsTheNewestRevisions() throws Exception
    {
        XWikiDocument doc = stubDocument(CURRENT_VERSION, "");
        when(this.revisionProvider.getRevision(DOC_REF, "9.9")).thenReturn(null);
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(2L);
        when(doc.getRevisions(any(RevisionCriteria.class), any()))
            .thenReturn(new ArrayList<>(List.of("3.3", CURRENT_VERSION)));
        stubRevisionInfo(doc, "3.3", node(AUTHOR, "", false, date(9, 0)));
        stubRevisionInfo(doc, CURRENT_VERSION, node(AUTHOR, "", false, date(10, 0)));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No revision \"9.9\" of \"" + CANONICAL + "\"."), text);
        assertTrue(text.contains("Most recent revisions:"), text);
        // Newest first, so the current version leads the self-correction list.
        assertTrue(text.indexOf(CURRENT_VERSION) < text.indexOf("3.3"), text);
    }

    @Test
    void versionModeCutsAtTheBudgetWithTheExactContinuationOffset() throws Exception
    {
        stubDocument(CURRENT_VERSION, "current body");
        // 300 lines of 100 budget-relevant chars each (99 + the newline): the 24000-char budget holds
        // exactly the first 240, so the window must cut there and continue at line 241.
        XWikiDocument revision = mock(XWikiDocument.class);
        when(revision.getContent()).thenReturn(("y".repeat(99) + "\n").repeat(299) + "y".repeat(99));
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(revision);

        String text = callText(Map.of(REFERENCE_KEY, REF, VERSION_KEY, OLD_VERSION));

        assertTrue(text.contains("Showing lines 1-240 of 300."), tailOf(text));
        assertTrue(text.contains("Output truncated at the ~6000-token cap; continue with offset=241."),
            tailOf(text));
        assertFalse(text.contains("   241\t"), tailOf(text));
    }

    @Test
    void versionModeOffsetContinuesTheContentWindow() throws Exception
    {
        stubDocument(CURRENT_VERSION, "current body");
        XWikiDocument revision = mock(XWikiDocument.class);
        when(revision.getContent()).thenReturn("one\ntwo\nthree");
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(revision);

        String text = callText(Map.of(REFERENCE_KEY, REF, VERSION_KEY, OLD_VERSION, OFFSET_KEY, 3));

        assertTrue(text.contains("     3\tthree"), text);
        assertFalse(text.contains("\ttwo"), text);
        assertTrue(text.contains("Showing lines 3-3 of 3."), text);
    }

    // ---------------------------------------------------------------- version-string gate

    @Test
    void versionStringsFailingTheGateAreRejectedBeforeAnyUse() throws Exception
    {
        // The regex gate closes provider-prefix smuggling ("deleted:3" would address the deleted-documents
        // store through the platform's revision provider routing) and malformed archive version strings.
        for (String bad : List.of("deleted:3", "1.2.3", "x.y", "", "1234567890.1")) {
            McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, VERSION_KEY, bad));

            assertEquals(Boolean.TRUE, result.isError(), bad);
            assertTrue(textOf(result).contains("'version' must be a version of the form major.minor"),
                textOf(result));
        }
        verify(this.revisionProvider, never()).getRevision(any(DocumentReference.class), anyString());
    }

    @Test
    void fromAndToAreGatedLikeVersion() throws Exception
    {
        McpSchema.CallToolResult fromResult = call(Map.of(REFERENCE_KEY, REF, FROM_KEY, "deleted:3"));
        assertEquals(Boolean.TRUE, fromResult.isError());
        assertTrue(textOf(fromResult).contains("'from' must be a version of the form major.minor"),
            textOf(fromResult));

        McpSchema.CallToolResult toResult =
            call(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION, TO_KEY, "xar:2.1"));
        assertEquals(Boolean.TRUE, toResult.isError());
        assertTrue(textOf(toResult).contains("'to' must be a version of the form major.minor"),
            textOf(toResult));
    }

    @Test
    void versionIsMutuallyExclusiveWithFromAndTo() throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, VERSION_KEY, OLD_VERSION, FROM_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("'version' cannot be combined with 'from'/'to'"), textOf(result));
    }

    @Test
    void toWithoutFromIsRejected() throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, TO_KEY, OLD_VERSION));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("'to' requires 'from'"), textOf(result));
    }

    // ---------------------------------------------------------------- diff mode

    @Test
    void diffModeRendersHeaderAndRealUnifiedHunkAgainstCurrentByDefault() throws Exception
    {
        XWikiDocument doc = stubDocument(CURRENT_VERSION, "line one\nline CHANGED\nline three");
        lenient().when(doc.getTitle()).thenReturn("Same title");
        XWikiDocument fromDoc = mock(XWikiDocument.class);
        when(fromDoc.getContent()).thenReturn("line one\nline two\nline three");
        lenient().when(fromDoc.getTitle()).thenReturn("Same title");
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(fromDoc);

        String text = callText(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION));

        assertTrue(text.contains("--- " + CANONICAL + " (v" + OLD_VERSION + ")\n"
            + "+++ " + CANONICAL + " (v" + CURRENT_VERSION + ")\n"), text);
        // The real diff components render the standard unified hunk.
        assertTrue(text.contains("@@ -1,3 +1,3 @@\n line one\n-line two\n+line CHANGED\n line three\n"), text);
        assertFalse(text.contains("Title changed:"), text);
        // 'to' defaulted to the already-loaded current document: only 'from' went through the provider.
        verify(this.revisionProvider, never()).getRevision(DOC_REF, CURRENT_VERSION);
    }

    @Test
    void diffModeLoadsAnExplicitToRevision() throws Exception
    {
        stubDocument(CURRENT_VERSION, "current body");
        XWikiDocument fromDoc = mock(XWikiDocument.class);
        when(fromDoc.getContent()).thenReturn("alpha");
        XWikiDocument toDoc = mock(XWikiDocument.class);
        when(toDoc.getContent()).thenReturn("beta");
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(fromDoc);
        when(this.revisionProvider.getRevision(DOC_REF, "3.1")).thenReturn(toDoc);

        String text = callText(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION, TO_KEY, "3.1"));

        assertTrue(text.contains("--- " + CANONICAL + " (v" + OLD_VERSION + ")"), text);
        assertTrue(text.contains("+++ " + CANONICAL + " (v3.1)"), text);
        assertTrue(text.contains("-alpha\n+beta\n"), text);
    }

    @Test
    void diffModeSaysSoWhenContentsAreIdenticalAndStillNotesATitleChange() throws Exception
    {
        XWikiDocument doc = stubDocument(CURRENT_VERSION, "same body");
        when(doc.getTitle()).thenReturn("New title");
        XWikiDocument fromDoc = mock(XWikiDocument.class);
        when(fromDoc.getContent()).thenReturn("same body");
        when(fromDoc.getTitle()).thenReturn("Old title");
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(fromDoc);

        String text = callText(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION));

        assertTrue(text.contains("No content differences between " + OLD_VERSION + " and " + CURRENT_VERSION
            + "."), text);
        assertTrue(text.contains("Title changed: \"Old title\" -> \"New title\""), text);
        assertFalse(text.contains("@@"), text);
    }

    @Test
    void diffModeDropsALaterOverBudgetHunkWholeWithTheTruncationNote() throws Exception
    {
        // First a small change at the top (a small first hunk), then a rewritten 200-line block whose
        // hunk alone outweighs the whole output budget: the small hunk must be emitted whole and the
        // big one dropped whole, replaced by the note.
        StringBuilder fromContent = new StringBuilder();
        StringBuilder toContent = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            String filler = "x".repeat(90);
            if (i == 0) {
                fromContent.append("first old\n");
                toContent.append("first new\n");
            } else if (i >= 200) {
                fromContent.append(String.format("old block %05d %s\n", i, filler));
                toContent.append(String.format("new block %05d %s\n", i, filler));
            } else {
                fromContent.append(String.format("ctx %05d %s\n", i, filler));
                toContent.append(String.format("ctx %05d %s\n", i, filler));
            }
        }
        stubDocument(CURRENT_VERSION, toContent.toString());
        XWikiDocument fromDoc = mock(XWikiDocument.class);
        when(fromDoc.getContent()).thenReturn(fromContent.toString());
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(fromDoc);

        String text = callText(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION));

        assertTrue(text.contains("-first old\n+first new\n"), tailOf(text));
        assertTrue(text.contains("Diff truncated at the ~"), tailOf(text));
        assertFalse(text.contains("new block 00250"), tailOf(text));
    }

    @Test
    void diffModeCutsAGiantFirstHunkAtALineBoundaryWithinTheBudget() throws Exception
    {
        // A full-content rewrite is ONE giant hunk; without the line-boundary cap it would bypass the
        // output budget entirely.
        StringBuilder fromContent = new StringBuilder();
        StringBuilder toContent = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            String filler = "x".repeat(90);
            fromContent.append(String.format("old %05d %s\n", i, filler));
            toContent.append(String.format("new %05d %s\n", i, filler));
        }
        stubDocument(CURRENT_VERSION, toContent.toString());
        XWikiDocument fromDoc = mock(XWikiDocument.class);
        when(fromDoc.getContent()).thenReturn(fromContent.toString());
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(fromDoc);

        String text = callText(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION));

        assertTrue(text.endsWith("[diff truncated: the first hunk alone exceeds the output budget - "
            + "request a narrower revision pair (adjacent versions give the smallest diff)]"), tailOf(text));
        // The cut is at a LINE boundary: the note sits on its own line after a complete diff line.
        assertTrue(text.contains("\n-old 00000"), tailOf(text));
        assertFalse(text.contains("new 00299"), tailOf(text));
        assertTrue(text.length() < 26000, "diff escaped the output budget: " + text.length());
    }

    @Test
    void diffModeNormalizesCrlfContentBeforeDiffing() throws Exception
    {
        stubDocument(CURRENT_VERSION, "line one\r\nline CHANGED\r\n");
        XWikiDocument fromDoc = mock(XWikiDocument.class);
        when(fromDoc.getContent()).thenReturn("line one\r\nline two\r\n");
        when(this.revisionProvider.getRevision(DOC_REF, OLD_VERSION)).thenReturn(fromDoc);

        String text = callText(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION));

        // Both sides are normalized to LF before diffing: the hunk shows clean lines and no CR can make
        // identical-looking lines diff as changed.
        assertTrue(text.contains("-line two\n+line CHANGED\n"), text);
        assertFalse(text.contains("\r"), text);
    }

    @Test
    void offsetIsRejectedInDiffMode() throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION, OFFSET_KEY, 5));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("'offset' does not apply to a diff"), textOf(result));
    }

    @Test
    void limitIsRejectedOutsideListMode() throws Exception
    {
        McpSchema.CallToolResult withVersion =
            call(Map.of(REFERENCE_KEY, REF, VERSION_KEY, OLD_VERSION, LIMIT_KEY, 5));
        assertEquals(Boolean.TRUE, withVersion.isError());
        assertTrue(textOf(withVersion).contains("'limit' only applies to the revision list"),
            textOf(withVersion));

        McpSchema.CallToolResult withFrom =
            call(Map.of(REFERENCE_KEY, REF, FROM_KEY, OLD_VERSION, LIMIT_KEY, 5));
        assertEquals(Boolean.TRUE, withFrom.isError());
        assertTrue(textOf(withFrom).contains("'limit' only applies to the revision list"), textOf(withFrom));
    }

    @Test
    void diffModeMissNamesTheMissingSide() throws Exception
    {
        XWikiDocument doc = stubDocument(CURRENT_VERSION, "body");
        when(this.revisionProvider.getRevision(DOC_REF, "9.9")).thenReturn(null);
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(0L);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FROM_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("No revision \"9.9\" of \"" + CANONICAL + "\"."), textOf(result));
    }

    // ---------------------------------------------------------------- locale handling

    @Test
    void localeMissRefusesWithExistingTranslations() throws Exception
    {
        XWikiDocument doc = stubDocument("1.1", "");
        when(doc.getRealLocale()).thenReturn(Locale.ENGLISH);
        when(doc.getDefaultLocale()).thenReturn(Locale.ENGLISH);
        when(doc.getTranslationLocales(any())).thenReturn(List.of(Locale.GERMAN, Locale.ITALIAN));
        DocumentReference frRef = new DocumentReference(DOC_REF, Locale.FRENCH);
        when(this.documentAccessBridge.exists(frRef)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("no \"fr\" translation of \"" + CANONICAL + "\""), text);
        assertTrue(text.contains("Translations: de, it."), text);
        assertTrue(text.contains("Omit 'locale' for the default version."), text);
    }

    @Test
    void localeMissNamesTheDefaultLanguage() throws Exception
    {
        XWikiDocument doc = stubDocument("1.1", "");
        when(doc.getRealLocale()).thenReturn(Locale.ENGLISH);
        when(doc.getDefaultLocale()).thenReturn(Locale.ENGLISH);
        DocumentReference frRef = new DocumentReference(DOC_REF, Locale.FRENCH);
        when(this.documentAccessBridge.exists(frRef)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("The default language is en."), textOf(result));
    }

    @Test
    void localeReadUsesTheTranslationRowsOwnHistory() throws Exception
    {
        XWikiDocument doc = stubDocument("5.1", "");
        when(doc.getRealLocale()).thenReturn(Locale.ENGLISH);
        when(doc.getDefaultLocale()).thenReturn(Locale.ENGLISH);
        DocumentReference frRef = new DocumentReference(DOC_REF, Locale.FRENCH);
        when(this.documentAccessBridge.exists(frRef)).thenReturn(true);
        XWikiDocument frDoc = mock(XWikiDocument.class);
        when(frDoc.getVersion()).thenReturn("1.2");
        when(this.documentAccessBridge.getDocumentInstance(frRef)).thenReturn(frDoc);
        when(frDoc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(2L);
        when(frDoc.getRevisions(any(RevisionCriteria.class), any()))
            .thenReturn(new ArrayList<>(List.of("1.1", "1.2")));
        stubRevisionInfo(frDoc, "1.1", node(AUTHOR, "", false, date(9, 0)));
        stubRevisionInfo(frDoc, "1.2", node(AUTHOR, "", true, date(9, 30)));

        String text = callText(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr"));

        // The fr row has its own history: the header names the translation and ITS version, and the
        // archive reads went to the translation row, not the default document.
        assertTrue(text.contains("History of " + CANONICAL + " (fr translation)"), text);
        assertTrue(text.contains("Current version: 1.2"), text);
        verify(doc, never()).getRevisionsCount(any(RevisionCriteria.class), any());
    }

    // ---------------------------------------------------------------- access, wiki and failures

    @Test
    void authorizationDenialIsReturnedWithoutTouchingTheDocument() throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenThrow(new MCPAccessDeniedException("Access denied to the requested document."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Access denied to the requested document.", textOf(result));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void wikiParameterIsResolvedThroughTheReachGate() throws Exception
    {
        when(this.wikiReach.resolveSingleWiki("second")).thenReturn("second");
        XWikiDocument doc = stubDocument("1.1", "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any())).thenReturn(0L);

        callText(Map.of(REFERENCE_KEY, REF, WIKI_KEY, "second"));

        // The resolved target wiki becomes the resolution context of an unqualified reference.
        verify(this.documentAccess).resolveAndAuthorize(REF, Right.VIEW, new WikiReference("second"));
    }

    @Test
    void unreachableWikiRefusalIsPropagated() throws Exception
    {
        when(this.wikiReach.resolveSingleWiki("secret"))
            .thenThrow(new MCPAccessDeniedException("Cross-wiki access is not enabled on this endpoint."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, WIKI_KEY, "secret"));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Cross-wiki access is not enabled on this endpoint.", textOf(result));
    }

    @Test
    void missingDocumentIsRefused() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("No such document: \"" + REF + "\".", textOf(result));
    }

    @Test
    void archiveFailureSurfacesAsAnErrorResultWithTheCauseInTheLogs() throws Exception
    {
        XWikiDocument doc = stubDocument("1.1", "");
        when(doc.getRevisionsCount(any(RevisionCriteria.class), any()))
            .thenThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_UNKNOWN, "archive read failed"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).startsWith("Could not read the document history."), textOf(result));
        assertTrue(this.logCapture.getMessage(0)
            .startsWith("MCP get_history tool failed to read the history of"), this.logCapture.getMessage(0));
    }

    // ---------------------------------------------------------------- advertisement

    @Test
    void wikiParameterIsAdvertisedOnlyWithCrossWikiReach()
    {
        Map<?, ?> reachedProperties =
            (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");
        assertTrue(reachedProperties.containsKey(WIKI_KEY));

        when(this.wikiReach.isReachEnabled()).thenReturn(false);
        Map<?, ?> localProperties =
            (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");
        assertFalse(localProperties.containsKey(WIKI_KEY));
        assertNotEquals(reachedProperties, localProperties);
    }
}
