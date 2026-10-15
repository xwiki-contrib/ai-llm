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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.link.LinkException;
import org.xwiki.link.LinkStore;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.PageReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.QueryException;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPGetLinksTool}, with the real {@link MCPLinksSupport} wired in, so the assertions
 * pin the whole backlink pipeline (dedupe, reach filter, sort, ceiling, authorization, batched
 * visibility) and the live outgoing extraction with its PAGE-family conversion.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({MCPLinksSupport.class, MCPTranslationSupport.class})
class MCPGetLinksToolTest extends AbstractMCPToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String DIRECTION_KEY = "direction";

    private static final String LOCALE_KEY = "locale";

    private static final String LIMIT_KEY = "limit";

    private static final String OFFSET_KEY = "offset";

    private static final String SHOW_HIDDEN_KEY = "showHidden";

    private static final String WIKI_KEY = "wiki";

    private static final String OUT = "out";

    private static final String BOTH = "both";

    private static final String WIKI = "xwiki";

    private static final String REF = "Sandbox.WebHome";

    private static final String CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String NAMES_BIND = "names";

    private static final String IN_HEADER = "Links of " + CANONICAL + " (direction: in)";

    private static final DocumentReference DOC_REF = new DocumentReference(WIKI, "Sandbox", "WebHome");

    private static final DocumentReference LINKING_A = new DocumentReference(WIKI, "AAA", "One");

    private static final DocumentReference LINKING_B = new DocumentReference(WIKI, "BBB", "Two");

    private static final DocumentReference LINKING_C = new DocumentReference(WIKI, "CCC", "Three");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPGetLinksTool tool;

    @MockComponent
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private MCPWikiReach wikiReach;

    @MockComponent
    private MCPRowQuery rowQuery;

    @MockComponent
    private LinkStore linkStore;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @MockComponent
    private DocumentReferenceResolver<EntityReference> documentResolver;

    @MockComponent
    private EntityReferenceResolver<EntityReference> referenceConverter;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<PageReference> currentDocumentResolver;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    private XWikiContext xcontext;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.wikiReach.resolveSingleWiki(any())).thenReturn(WIKI);
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
        lenient().when(this.wikiReach.canReachWiki(WIKI)).thenReturn(true);
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenReturn(DOC_REF);
        this.xcontext = mock(XWikiContext.class);
        lenient().when(this.contextProvider.get()).thenReturn(this.xcontext);
        lenient().when(this.xcontext.getWikiId()).thenReturn(WIKI);
        lenient().when(this.serializer.serialize(any()))
            .thenAnswer(invocation -> fullName(invocation.getArgument(0)));
        lenient().when(this.localSerializer.serialize(any()))
            .thenAnswer(invocation -> localName(invocation.getArgument(0)));
        lenient().when(this.documentResolver.resolve(any(EntityReference.class)))
            .thenAnswer(invocation -> new DocumentReference((EntityReference) invocation.getArgument(0)));
        lenient().when(this.documentAccessBridge.exists(any(DocumentReference.class))).thenReturn(true);
        lenient().when(this.rowQuery.isAuthorized(any())).thenReturn(true);
        // Default batched visibility answer: every queried name exists and is not hidden. Tests for
        // hidden and stale entries override it.
        lenient().when(this.rowQuery.rows(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenAnswer(invocation -> {
                List<String> names = invocation.getArgument(3);
                List<Object[]> rows = new ArrayList<>();
                for (String name : names) {
                    rows.add(new Object[] {name, Boolean.FALSE});
                }
                return rows;
            });
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    /**
     * Serializes a reference the way the full serializer does: wiki prefix plus the dotted local name.
     *
     * @param reference the reference to serialize
     * @return the wiki-prefixed dotted name
     */
    private static String fullName(EntityReference reference)
    {
        EntityReference wiki = reference.extractReference(EntityType.WIKI);
        return (wiki != null ? wiki.getName() + ":" : "") + localName(reference);
    }

    /**
     * @param text a possibly very long tool result
     * @return its last 400 characters, for assertion messages that must not dump a giant window
     */
    private static String tailOf(String text)
    {
        return text.substring(Math.max(0, text.length() - 400));
    }

    private void stubBacklinks(EntityReference... references) throws Exception
    {
        when(this.linkStore.resolveBackLinkedEntities(DOC_REF))
            .thenReturn(new LinkedHashSet<>(List.of(references)));
    }

    private XWikiDocument stubOutDocument(DocumentReference reference, Set<EntityReference> links)
        throws Exception
    {
        XWikiDocument document = mock(XWikiDocument.class);
        lenient().when(document.getUniqueLinkedEntities(this.xcontext)).thenReturn(links);
        when(this.documentAccessBridge.getDocumentInstance(reference)).thenReturn(document);
        return document;
    }

    // ---------------------------------------------------------------- backlinks (direction in)

    @Test
    void localeVariantsOfALinkingPageCollapseToOneRow() throws Exception
    {
        DocumentReference plain = new DocumentReference(WIKI, "Help", "Linking");
        stubBacklinks(plain, new DocumentReference(plain, Locale.FRENCH),
            new DocumentReference(plain, Locale.GERMAN));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertTrue(text.contains(IN_HEADER), text);
        assertTrue(text.contains("Incoming links: 1 backlink\n"), text);
        assertTrue(text.contains("\nHelp.Linking"), text);
        assertEquals(text.indexOf("Help.Linking"), text.lastIndexOf("Help.Linking"), text);
        assertTrue(text.contains("Showing backlinks 1-1 of 1."), text);
    }

    @Test
    void outOfReachWikiIsDroppedBeforeAuthorizationIsConsulted() throws Exception
    {
        DocumentReference foreign = new DocumentReference("secret", "Secret", "Page");
        when(this.wikiReach.canReachWiki("secret")).thenReturn(false);
        stubBacklinks(LINKING_A, foreign);

        String text = callText(Map.of(REFERENCE_KEY, REF));

        // Leaking a cross-wiki document's existence on a reach-off endpoint would be a security bug:
        // the foreign reference must never even reach the authorization check.
        verify(this.rowQuery, never()).isAuthorized(foreign);
        assertFalse(text.contains("Secret.Page"), text);
        assertTrue(text.contains("Incoming links: 1 backlink\n"), text);
        assertTrue(text.contains("AAA.One"), text);
    }

    @Test
    void deniedReferenceIsDroppedAndNotCounted() throws Exception
    {
        stubBacklinks(LINKING_A, LINKING_B);
        when(this.rowQuery.isAuthorized(LINKING_B)).thenReturn(false);

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertFalse(text.contains("BBB.Two"), text);
        // The count is authorized-only: the denied document's existence is not disclosed by arithmetic.
        assertTrue(text.contains("Incoming links: 1 backlink\n"), text);
    }

    @Test
    void hiddenDocumentIsDroppedFromTheRowsButCountedInTheHeading() throws Exception
    {
        stubBacklinks(LINKING_A, LINKING_B);
        when(this.rowQuery.rows(anyString(), eq(WIKI), eq(NAMES_BIND), any(), anyInt()))
            .thenReturn(List.<Object[]>of(new Object[] {"AAA.One", Boolean.FALSE},
                new Object[] {"BBB.Two", Boolean.TRUE}));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        // The hidden page is never listed, but the broken-link-repair use case needs to know that
        // hidden pages link here, so it is counted - in get_tree's "+N hidden" style.
        assertFalse(text.contains("BBB.Two"), text);
        assertTrue(text.contains("Incoming links: 1 backlink (+1 hidden)\n"), text);
    }

    @Test
    void staleIndexEntryAbsentFromTheBatchIsDroppedUncounted() throws Exception
    {
        stubBacklinks(LINKING_A, LINKING_B);
        when(this.rowQuery.rows(anyString(), eq(WIKI), eq(NAMES_BIND), any(), anyInt()))
            .thenReturn(List.<Object[]>of(new Object[] {"AAA.One", Boolean.FALSE}));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertFalse(text.contains("BBB.Two"), text);
        assertTrue(text.contains("Incoming links: 1 backlink\n"), text);
        // A stale entry is not a hidden page: it must not inflate the hidden count.
        assertFalse(text.contains("hidden"), text);
    }

    @Test
    void hiddenOnlyBacklinksAreStatedAsACountInsteadOfTheTeachingNote() throws Exception
    {
        stubBacklinks(LINKING_A);
        when(this.rowQuery.rows(anyString(), eq(WIKI), eq(NAMES_BIND), any(), anyInt()))
            .thenReturn(List.<Object[]>of(new Object[] {"AAA.One", Boolean.TRUE}));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertTrue(text.contains("Incoming links: 0 backlinks (+1 hidden)"), text);
        assertTrue(text.contains("No visible backlinks (1 hidden page links here)."), text);
        // The teaching note would be a lie here: backlinks ARE indexed, they are just hidden.
        assertFalse(text.contains("No indexed backlinks"), text);
    }

    @Test
    void showHiddenListsHiddenRowsMarkedInSortOrder() throws Exception
    {
        stubBacklinks(LINKING_A, LINKING_B, LINKING_C);
        when(this.rowQuery.rows(anyString(), eq(WIKI), eq(NAMES_BIND), any(), anyInt()))
            .thenReturn(List.<Object[]>of(new Object[] {"AAA.One", Boolean.FALSE},
                new Object[] {"BBB.Two", Boolean.TRUE},
                new Object[] {"CCC.Three", Boolean.FALSE}));

        String text = callText(Map.of(REFERENCE_KEY, REF, SHOW_HIDDEN_KEY, true));

        // The hidden page joins the list at its sorted position, marked so an agent can tell which
        // links a reader browsing with the default preference never sees; the count line switches to
        // get_tree's "hidden included" suffix instead of the "(+N hidden)" count.
        assertTrue(text.contains("Incoming links: 3 backlinks, hidden included\n"), text);
        assertTrue(text.contains("AAA.One\nBBB.Two (hidden)\nCCC.Three"), text);
        assertFalse(text.contains("(+"), text);
        assertTrue(text.contains("Showing backlinks 1-3 of 3."), text);
    }

    @Test
    void showHiddenPagesAcrossTheMergedList() throws Exception
    {
        stubBacklinks(LINKING_A, LINKING_B, LINKING_C);
        when(this.rowQuery.rows(anyString(), eq(WIKI), eq(NAMES_BIND), any(), anyInt()))
            .thenReturn(List.<Object[]>of(new Object[] {"AAA.One", Boolean.FALSE},
                new Object[] {"BBB.Two", Boolean.TRUE},
                new Object[] {"CCC.Three", Boolean.FALSE}));

        String firstPage = callText(Map.of(REFERENCE_KEY, REF, SHOW_HIDDEN_KEY, true, LIMIT_KEY, 2));
        String secondPage = callText(Map.of(REFERENCE_KEY, REF, SHOW_HIDDEN_KEY, true, LIMIT_KEY, 2,
            OFFSET_KEY, 2));

        // Hidden rows page like any other row of the merged list.
        assertTrue(firstPage.contains("AAA.One\nBBB.Two (hidden)"), firstPage);
        assertFalse(firstPage.contains("CCC.Three"), firstPage);
        assertTrue(firstPage.contains("Showing backlinks 1-2 of 3. Continue with offset=2."), firstPage);
        assertTrue(secondPage.contains("CCC.Three"), secondPage);
        assertFalse(secondPage.contains("BBB.Two"), secondPage);
        assertTrue(secondPage.contains("Showing backlinks 3-3 of 3."), secondPage);
    }

    @Test
    void showHiddenListsHiddenOnlyBacklinksInsteadOfTheCountMessage() throws Exception
    {
        stubBacklinks(LINKING_A);
        when(this.rowQuery.rows(anyString(), eq(WIKI), eq(NAMES_BIND), any(), anyInt()))
            .thenReturn(List.<Object[]>of(new Object[] {"AAA.One", Boolean.TRUE}));

        String text = callText(Map.of(REFERENCE_KEY, REF, SHOW_HIDDEN_KEY, true));

        assertTrue(text.contains("Incoming links: 1 backlink, hidden included"), text);
        assertTrue(text.contains("AAA.One (hidden)"), text);
        assertFalse(text.contains("No visible backlinks"), text);
        assertFalse(text.contains("(+"), text);
    }

    @Test
    void showHiddenIsRejectedWithOutDirection() throws Exception
    {
        for (Object value : List.of(Boolean.TRUE, Boolean.FALSE)) {
            McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT,
                SHOW_HIDDEN_KEY, value));

            assertEquals(Boolean.TRUE, result.isError(), String.valueOf(value));
            assertTrue(textOf(result).contains("'showHidden' only applies to the backlink list"),
                textOf(result));
        }
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void visibilityNamesAreChunkedIntoMultipleQueries() throws Exception
    {
        List<EntityReference> references = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            references.add(new DocumentReference(WIKI, String.format("Chunk%03d", i), "Page"));
        }
        stubBacklinks(references.toArray(new EntityReference[0]));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        // 600 names split into 500 + 100: two visibility queries against the one wiki, so the bound
        // in-list stays below the database expression-list caps.
        verify(this.rowQuery, times(2)).rows(anyString(), eq(WIKI), eq(NAMES_BIND), any(), anyInt());
        assertTrue(text.contains("Incoming links: 600 backlinks"), text);
    }

    @Test
    void backlinksAreSortedBySerializedReference() throws Exception
    {
        stubBacklinks(LINKING_C, LINKING_A, LINKING_B);

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertTrue(text.indexOf("AAA.One") < text.indexOf("BBB.Two"), text);
        assertTrue(text.indexOf("BBB.Two") < text.indexOf("CCC.Three"), text);
    }

    @Test
    void offsetAndLimitPageTheSortedListWithAContinuationHint() throws Exception
    {
        stubBacklinks(LINKING_C, LINKING_A, LINKING_B);

        String text = callText(Map.of(REFERENCE_KEY, REF, LIMIT_KEY, 1, OFFSET_KEY, 1));

        assertTrue(text.contains("\nBBB.Two"), text);
        assertFalse(text.contains("AAA.One"), text);
        assertFalse(text.contains("CCC.Three"), text);
        assertTrue(text.contains("Showing backlinks 2-2 of 3. Continue with offset=2."), text);
    }

    @Test
    void lastPageOmitsTheContinuationHint() throws Exception
    {
        stubBacklinks(LINKING_A, LINKING_B);

        String text = callText(Map.of(REFERENCE_KEY, REF, LIMIT_KEY, 50));

        assertTrue(text.contains("Showing backlinks 1-2 of 2."), text);
        assertFalse(text.contains("Continue with offset="), text);
    }

    @Test
    void offsetBeyondTotalStatesTheTotalInsteadOfAnEmptyPage() throws Exception
    {
        stubBacklinks(LINKING_A);

        String text = callText(Map.of(REFERENCE_KEY, REF, OFFSET_KEY, 9));

        assertTrue(text.contains("No backlinks at offset=9; this document has 1 backlink."), text);
    }

    @Test
    void rawSetBeyondTheCeilingReportsAFloorCountAndStopsScanning() throws Exception
    {
        List<EntityReference> references = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            references.add(new DocumentReference(WIKI, String.format("Space%04d", i), "Page"));
        }
        stubBacklinks(references.toArray(new EntityReference[0]));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertTrue(text.contains("Incoming links: 2000+ backlinks"), text);
        assertTrue(text.contains("The backlink scan hit the 2000-reference ceiling"), text);
        // The 2001st reference is beyond the deterministic scan window and is never authorized.
        verify(this.rowQuery, times(2000)).isAuthorized(any());
    }

    @Test
    void rawSetOfExactlyTheCeilingIsAnExactCountNotAFloor() throws Exception
    {
        List<EntityReference> references = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            references.add(new DocumentReference(WIKI, String.format("Space%04d", i), "Page"));
        }
        stubBacklinks(references.toArray(new EntityReference[0]));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        // Capped means MORE than the ceiling was answered: exactly 2000 is a complete scan, so the
        // count is exact - no "+" floor mark and no ceiling note.
        assertTrue(text.contains("Incoming links: 2000 backlinks"), text);
        assertFalse(text.contains("2000+"), text);
        assertFalse(text.contains("ceiling"), text);
        verify(this.rowQuery, times(2000)).isAuthorized(any());
    }

    @Test
    void limitIsClampedAndANegativeOffsetFoldsToZero() throws Exception
    {
        List<EntityReference> references = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            references.add(new DocumentReference(WIKI, String.format("Space%04d", i), "Page"));
        }
        stubBacklinks(references.toArray(new EntityReference[0]));

        String oversized = callText(Map.of(REFERENCE_KEY, REF, LIMIT_KEY, 500));
        assertTrue(oversized.contains("Showing backlinks 1-200 of 250. Continue with offset=200."),
            oversized);

        String undersized = callText(Map.of(REFERENCE_KEY, REF, LIMIT_KEY, 0));
        assertTrue(undersized.contains("Showing backlinks 1-1 of 250. Continue with offset=1."),
            undersized);

        String negativeOffset = callText(Map.of(REFERENCE_KEY, REF, OFFSET_KEY, -5));
        assertTrue(negativeOffset.contains("Showing backlinks 1-50 of 250. Continue with offset=50."),
            negativeOffset);
    }

    @Test
    void pagingFooterSurvivesAnOutputBudgetTruncation() throws Exception
    {
        // 150 rows of ~190 characters exceed the 24000-character output budget, so the row block is
        // cut - but the paging footer is appended AFTER the cut and must survive it.
        List<EntityReference> references = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            references.add(
                new DocumentReference(WIKI, String.format("S%03d", i) + "x".repeat(180), "P"));
        }
        stubBacklinks(references.toArray(new EntityReference[0]));

        String text = callText(Map.of(REFERENCE_KEY, REF, LIMIT_KEY, 200));

        assertTrue(text.contains("Output truncated at the ~6000-token cap."), tailOf(text));
        assertFalse(text.contains("S149"), tailOf(text));
        int footerIndex = text.indexOf("Showing backlinks 1-150 of 150.");
        assertTrue(footerIndex > text.indexOf("Output truncated at the ~6000-token cap."), tailOf(text));
    }

    @Test
    void missingTargetStillListsItsBacklinksWithTheRepairNote() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(false);
        stubBacklinks(LINKING_A);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Document does not exist; listing pages that link to it."), text);
        assertTrue(text.contains("AAA.One"), text);
    }

    @Test
    void emptyBacklinksCarryTheIndexingTeachingNote() throws Exception
    {
        stubBacklinks();

        String text = callText(Map.of(REFERENCE_KEY, REF));

        assertTrue(text.contains("Incoming links: 0 backlinks"), text);
        assertTrue(text.contains("No indexed backlinks. Note: links are indexed asynchronously on save"),
            text);
        assertTrue(text.contains("links generated by macros at render time are never in the index."), text);
    }

    @Test
    void wikiAuthoredNamesAreNeutralizedInTheRows() throws Exception
    {
        stubBacklinks(new DocumentReference(WIKI, "Bad\u202EName", "Pa\nge"));

        String text = callText(Map.of(REFERENCE_KEY, REF));

        // A bidi override could reorder how the row displays; a newline would forge an extra row.
        assertFalse(text.contains("\u202E"), text);
        assertTrue(text.contains("BadName.Page"), text);
        assertTrue(text.contains("Incoming links: 1 backlink\n"), text);
    }

    // ---------------------------------------------------------------- outgoing (direction out)

    @Test
    void outModeGroupsLinkedDocumentsAndAttachmentsSorted() throws Exception
    {
        DocumentReference target = new DocumentReference(WIKI, "Help", "Target");
        Set<EntityReference> links = new LinkedHashSet<>(List.of(target,
            new AttachmentReference("file.png", target), new PageReference(WIKI, "Guide")));
        stubOutDocument(DOC_REF, links);
        when(this.currentDocumentResolver.resolve(any(PageReference.class)))
            .thenReturn(new DocumentReference(WIKI, "Guide", "WebHome"));

        String text = callText(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT));

        assertTrue(text.contains("Links of " + CANONICAL + " (direction: out)"), text);
        assertTrue(text.contains("Outgoing links: 2 linked documents, 1 linked attachments"), text);
        // The PAGE reference is converted to the document it currently designates.
        assertTrue(text.contains("Documents:\nGuide.WebHome\nHelp.Target"), text);
        assertTrue(text.contains("Attachments:\nHelp.Target.file.png"), text);
        assertTrue(text.indexOf("Documents:") < text.indexOf("Attachments:"), text);
    }

    @Test
    void outModeConvertsAPageAttachmentToAnAttachmentReference() throws Exception
    {
        DocumentReference guideDoc = new DocumentReference(WIKI, "Guide", "WebHome");
        EntityReference pageAttachment =
            new EntityReference("shot.png", EntityType.PAGE_ATTACHMENT, new PageReference(WIKI, "Guide"));
        stubOutDocument(DOC_REF, Set.of(pageAttachment));
        when(this.currentDocumentResolver.resolve(any(PageReference.class))).thenReturn(guideDoc);
        when(this.referenceConverter.resolve(eq(pageAttachment), eq(EntityType.ATTACHMENT)))
            .thenReturn(new AttachmentReference("shot.png", guideDoc));

        String text = callText(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT));

        assertTrue(text.contains("Attachments:\nGuide.WebHome.shot.png"), text);
        assertTrue(text.contains("0 linked documents, 1 linked attachments"), text);
    }

    @Test
    void outModeWithoutLinksSaysSoWithTheStaticOnlyNote() throws Exception
    {
        stubOutDocument(DOC_REF, Set.of());

        String text = callText(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT));

        assertTrue(text.contains("Outgoing links: none."), text);
        assertTrue(text.contains("links generated by macros at render time never appear here."), text);
    }

    @Test
    void outModeOnAMissingDocumentIsRefused() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("No such document: \"" + REF + "\".", textOf(result));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void outModeFailedExtractionIsRefusedWithoutAStackDump() throws Exception
    {
        XWikiDocument document = stubOutDocument(DOC_REF, Set.of());
        when(document.getUniqueLinkedEntities(this.xcontext)).thenReturn(null);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Could not extract the links of \"" + REF + "\".", textOf(result));
    }

    @Test
    void outModeLocaleReadsTheTranslationRowsOwnLinks() throws Exception
    {
        XWikiDocument defaultDoc = stubOutDocument(DOC_REF, Set.of());
        when(defaultDoc.getRealLocale()).thenReturn(Locale.ENGLISH);
        when(defaultDoc.getDefaultLocale()).thenReturn(Locale.ENGLISH);
        DocumentReference frRef = new DocumentReference(DOC_REF, Locale.FRENCH);
        when(this.documentAccessBridge.exists(frRef)).thenReturn(true);
        stubOutDocument(frRef, Set.of(new DocumentReference(WIKI, "Aide", "Cible")));

        String text = callText(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT, LOCALE_KEY, "fr"));

        assertTrue(text.contains("Links of " + CANONICAL + " (fr translation) (direction: out)"), text);
        assertTrue(text.contains("Aide.Cible"), text);
        verify(defaultDoc, never()).getUniqueLinkedEntities(any());
    }

    @Test
    void outModeLocaleMissRefusesWithExistingTranslations() throws Exception
    {
        XWikiDocument defaultDoc = stubOutDocument(DOC_REF, Set.of());
        when(defaultDoc.getRealLocale()).thenReturn(Locale.ENGLISH);
        when(defaultDoc.getDefaultLocale()).thenReturn(Locale.ENGLISH);
        when(defaultDoc.getTranslationLocales(any())).thenReturn(List.of(Locale.GERMAN));
        DocumentReference frRef = new DocumentReference(DOC_REF, Locale.FRENCH);
        when(this.documentAccessBridge.exists(frRef)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT,
            LOCALE_KEY, "fr"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("no \"fr\" translation of \"" + CANONICAL + "\""), text);
        assertTrue(text.contains("Translations: de."), text);
        assertTrue(text.contains("Omit 'locale' for the default version."), text);
    }

    @Test
    void outModeLocaleMissNamesTheDefaultLanguage() throws Exception
    {
        XWikiDocument defaultDoc = stubOutDocument(DOC_REF, Set.of());
        when(defaultDoc.getRealLocale()).thenReturn(Locale.ENGLISH);
        when(defaultDoc.getDefaultLocale()).thenReturn(Locale.ENGLISH);
        DocumentReference frRef = new DocumentReference(DOC_REF, Locale.FRENCH);
        when(this.documentAccessBridge.exists(frRef)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT,
            LOCALE_KEY, "fr"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("The default language is en."), textOf(result));
    }

    // ---------------------------------------------------------------- both

    @Test
    void bothRendersIncomingThenOutgoing() throws Exception
    {
        stubBacklinks(LINKING_A);
        stubOutDocument(DOC_REF, Set.of(new DocumentReference(WIKI, "Help", "Target")));

        String text = callText(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, BOTH));

        assertTrue(text.contains("Links of " + CANONICAL + " (direction: both)"), text);
        assertTrue(text.contains("Incoming links: 1 backlink\n"), text);
        assertTrue(text.contains("Help.Target"), text);
        assertTrue(text.indexOf("Incoming links:") < text.indexOf("Outgoing links:"), text);
    }

    @Test
    void bothOnAMissingDocumentKeepsBacklinksAndNotesTheMissingOutSection() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(false);
        stubBacklinks(LINKING_A);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, BOTH));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("AAA.One"), text);
        assertTrue(text.contains(
            "Outgoing links: the document does not exist, so it has no content to read links from."), text);
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void bothModePagingFooterRendersAfterTheOutgoingSection() throws Exception
    {
        stubBacklinks(LINKING_C, LINKING_A, LINKING_B);
        stubOutDocument(DOC_REF, Set.of(new DocumentReference(WIKI, "Help", "Target")));

        String text = callText(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, BOTH, LIMIT_KEY, 1));

        // The paging footer is appended after the whole budgeted body (so it survives a budget cut),
        // which in both mode places it after the outgoing section - the deliberate placement.
        assertTrue(text.contains("Showing backlinks 1-1 of 3. Continue with offset=1."), text);
        assertTrue(text.indexOf("Outgoing links:") < text.indexOf("Showing backlinks 1-1 of 3."), text);
    }

    // ---------------------------------------------------------------- argument validation

    @Test
    void unknownDirectionIsRejected() throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, DIRECTION_KEY, "sideways"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("'direction' must be \"in\", \"out\" or \"both\""),
            textOf(result));
        verify(this.linkStore, never()).resolveBackLinkedEntities(any());
    }

    @Test
    void localeIsRejectedOutsideOutMode() throws Exception
    {
        for (Map<String, Object> args : List.of(
            Map.<String, Object>of(REFERENCE_KEY, REF, LOCALE_KEY, "fr"),
            Map.<String, Object>of(REFERENCE_KEY, REF, DIRECTION_KEY, BOTH, LOCALE_KEY, "fr"))) {
            McpSchema.CallToolResult result = call(args);

            assertEquals(Boolean.TRUE, result.isError(), String.valueOf(args));
            assertTrue(textOf(result).contains("'locale' only applies to direction=\"out\""),
                textOf(result));
        }
        verify(this.linkStore, never()).resolveBackLinkedEntities(any());
    }

    @Test
    void limitAndOffsetAreRejectedInOutMode() throws Exception
    {
        for (Map<String, Object> args : List.of(
            Map.<String, Object>of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT, LIMIT_KEY, 5),
            Map.<String, Object>of(REFERENCE_KEY, REF, DIRECTION_KEY, OUT, OFFSET_KEY, 5))) {
            McpSchema.CallToolResult result = call(args);

            assertEquals(Boolean.TRUE, result.isError(), String.valueOf(args));
            assertTrue(textOf(result).contains("'limit'/'offset' only page the backlink list"),
                textOf(result));
        }
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    // ---------------------------------------------------------------- access, wiki and failures

    @Test
    void authorizationDenialIsReturnedWithoutTouchingTheIndex() throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenThrow(new MCPAccessDeniedException("Access denied to the requested document."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Access denied to the requested document.", textOf(result));
        verify(this.linkStore, never()).resolveBackLinkedEntities(any());
    }

    @Test
    void wikiParameterIsResolvedThroughTheReachGate() throws Exception
    {
        when(this.wikiReach.resolveSingleWiki("second")).thenReturn("second");
        stubBacklinks();

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
    void existenceCheckFailureLogsTheReferenceWithoutItsLineBreak() throws Exception
    {
        when(this.documentAccessBridge.exists(any(DocumentReference.class)))
            .thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF + "\nforged line"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the document"), textOf(result));
        String warn = this.logCapture.getMessage(0);
        assertTrue(warn.contains("MCP get_links tool failed to check existence"), warn);
        assertFalse(warn.contains("\n"), warn);
        assertTrue(warn.contains("forged line"), warn);
    }

    @Test
    void linkIndexFailureSurfacesAsAFixedErrorWithTheCauseInTheLogs() throws Exception
    {
        DocumentReference forgedRef = new DocumentReference(WIKI, "Sandbox", "WebHome\nforged line");
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenReturn(forgedRef);
        when(this.linkStore.resolveBackLinkedEntities(forgedRef))
            .thenThrow(new LinkException("solr core unavailable"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).startsWith("Could not read the link index."), textOf(result));
        String warn = this.logCapture.getMessage(0);
        assertTrue(warn.startsWith("MCP get_links tool failed to read the backlinks of"), warn);
        assertTrue(warn.contains("forged line"), warn);
        assertFalse(warn.contains("\n"), warn);
    }

    @Test
    void batchedVisibilityFailureSurfacesOnTheSameFixedErrorChannel() throws Exception
    {
        stubBacklinks(LINKING_A);
        // doThrow instead of when..thenThrow: re-stubbing would otherwise run the default answer with
        // null arguments during stub registration.
        doThrow(new QueryException("db down", null, null)).when(this.rowQuery)
            .rows(anyString(), anyString(), anyString(), any(), anyInt());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).startsWith("Could not read the link index."), textOf(result));
        assertTrue(this.logCapture.getMessage(0)
            .startsWith("MCP get_links tool failed to read the backlinks of"), this.logCapture.getMessage(0));
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

    @Test
    void showHiddenIsAdvertisedRegardlessOfReach()
    {
        Map<?, ?> reachedProperties =
            (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");
        assertTrue(reachedProperties.containsKey(SHOW_HIDDEN_KEY));

        when(this.wikiReach.isReachEnabled()).thenReturn(false);
        Map<?, ?> localProperties =
            (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");
        assertTrue(localProperties.containsKey(SHOW_HIDDEN_KEY));
    }
}
