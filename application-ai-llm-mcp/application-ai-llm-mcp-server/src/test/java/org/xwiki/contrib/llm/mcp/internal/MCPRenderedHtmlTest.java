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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.mockito.MockitoComponentManager;
import org.xwiki.xml.html.DefaultHTMLCleanerComponentList;
import org.xwiki.xml.html.HTMLCleaner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MCPRenderedHtml}, against the real platform {@link HTMLCleaner}.
 *
 * @version $Id$
 */
@ComponentTest
@DefaultHTMLCleanerComponentList
class MCPRenderedHtmlTest
{
    private static final String INTRO = "(intro)";

    private static final Pattern TOKEN_COUNT = Pattern.compile("~(\\d+) tokens");

    private static final Pattern ROW_MARKER = Pattern.compile("ROW-\\d\\d");

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    private HTMLCleaner cleaner;

    @BeforeEach
    void setUp() throws Exception
    {
        this.cleaner = this.componentManager.getInstance(HTMLCleaner.class);
    }

    private MCPRenderedHtml parse(String html)
    {
        return MCPRenderedHtml.parse(this.cleaner, html);
    }

    private String strip(String html)
    {
        return parse(html).serialize();
    }

    private MCPRenderedHtml parseFull(String html)
    {
        return MCPRenderedHtml.parse(this.cleaner, html, true);
    }

    private String stripFull(String html)
    {
        return parseFull(html).serialize();
    }

    @Test
    void scriptStyleAndCommentsAreRemoved()
    {
        String out = strip("<p>keep</p><script>alert('payload')</script>"
            + "<style>.a{color:red}</style><!-- secret note -->");

        assertTrue(out.contains("<p>keep</p>"), out);
        assertFalse(out.contains("script"), out);
        assertFalse(out.contains("payload"), out);
        assertFalse(out.contains("style"), out);
        assertFalse(out.contains("color:red"), out);
        assertFalse(out.contains("secret note"), out);
        assertFalse(out.contains("<!--"), out);
    }

    @Test
    void presentationAttributesAreStrippedFromParagraphAndSpan()
    {
        String out = strip("<p style=\"color:red\" class=\"someclass\" id=\"p1\">text "
            + "<span style=\"font-weight:bold\">inside</span></p>");

        assertTrue(out.contains("<span>inside</span>"), out);
        assertFalse(out.contains("style="), out);
        assertFalse(out.contains("class="), out);
        assertFalse(out.contains("id="), out);
    }

    @Test
    void idKeptOnHeadingButNotOnDiv()
    {
        String out = strip("<h2 id=\"HSection\">Section</h2><div id=\"container\">x</div>");

        assertTrue(out.contains("<h2 id=\"HSection\">"), out);
        assertFalse(out.contains("container"), out);
    }

    @Test
    void messageBoxClassesSurvive()
    {
        String out = strip("<div class=\"box warningmessage\">Careful</div>");

        assertTrue(out.contains("class=\"box warningmessage\""), out);
        assertTrue(out.contains("Careful"), out);
    }

    @Test
    void renderingErrorMessageSurvivesButStackTraceDescriptionIsRemoved()
    {
        String out = strip("<div class=\"xwikirenderingerror\">Failed to execute the [velocity] macro</div>"
            + "<div class=\"xwikirenderingerrordescription hidden\">"
            + "<pre>org.xwiki.rendering.macro.MacroExecutionException\n"
            + "\tat a.b.C(C.java:1)\n\tat d.e.F(F.java:2)</pre></div>");

        // The one-line error message block stays - it is a useful signal for the agent.
        assertTrue(out.contains("class=\"xwikirenderingerror\""), out);
        assertTrue(out.contains("Failed to execute"), out);
        // The description block and its full stack trace are removed structurally.
        assertFalse(out.contains("xwikirenderingerrordescription"), out);
        assertFalse(out.contains("at a.b.C"), out);
        assertFalse(out.contains("MacroExecutionException"), out);
    }

    @Test
    void liveDataPlaceholderClassSurvivesStripped()
    {
        // Live Data renders client-side: the server-rendered page holds only an empty placeholder div,
        // and the surviving liveData token is what tells the agent the content is browser-populated.
        String out = strip("<div class=\"liveData loading\" data-config=\"x\">placeholder</div>");

        assertTrue(out.contains("class=\"liveData\""), out);
        assertFalse(out.contains("loading"), out);
        assertFalse(out.contains("data-config"), out);
    }

    @Test
    void wikiGeneratedIdClassIsDropped()
    {
        String out = strip("<p class=\"wikigeneratedid\">t</p>");

        assertFalse(out.contains("class="), out);
        assertFalse(out.contains("wikigeneratedid"), out);
        assertTrue(out.contains("<p>t</p>"), out);
    }

    @Test
    void mixedClassKeepsOnlyAllowlistedToken()
    {
        String out = strip("<span class=\"box wikilink\">x</span>");

        assertTrue(out.contains("class=\"box\""), out);
        assertFalse(out.contains("wikilink"), out);
    }

    @Test
    void contentAttributesSurvive()
    {
        String out = strip("<p><a href=\"https://example.org/page\">go</a> "
            + "<img src=\"/img/x.png\" alt=\"diagram\"/></p>"
            + "<table><tr><th scope=\"col\">h</th><td colspan=\"2\" rowspan=\"3\">c</td></tr></table>");

        assertTrue(out.contains("href=\"https://example.org/page\""), out);
        assertTrue(out.contains("src=\"/img/x.png\""), out);
        assertTrue(out.contains("alt=\"diagram\""), out);
        assertTrue(out.contains("scope=\"col\""), out);
        assertTrue(out.contains("colspan=\"2\""), out);
        assertTrue(out.contains("rowspan=\"3\""), out);
    }

    @Test
    void nestedTableStructureStaysIntact()
    {
        String out = strip("<table class=\"wikitable\"><tr><td>"
            + "<table><tr><td>inner</td></tr></table></td></tr></table>");

        assertTrue(out.contains("inner"), out);
        assertEquals(2, StringUtils.countMatches(out, "<table"), out);
        assertEquals(2, StringUtils.countMatches(out, "</table>"), out);
        assertFalse(out.contains("wikitable"), out);
    }

    @Test
    void cleanMarkupRoundTripsExactly()
    {
        // Pins the envelope-strip boundary exactly: any substring misalignment when removing the
        // serialized <html> wrapper would corrupt the first or last characters of the body.
        assertEquals("<p>x</p>", strip("<p>x</p>").trim());
    }

    @Test
    void emptyInputYieldsEmptyOutput()
    {
        assertEquals("", strip("").trim());
    }

    @Test
    void outlineListsAnchorsWithLevelIndentationAcrossNestedDivs()
    {
        // The h2 headings sit at different DOM depths (one inside a group div): the outline must still
        // list them in document order, indented by heading LEVEL, not DOM depth.
        MCPRenderedHtml parsed = parse("<h1 id=\"HTop\">Top</h1>"
            + "<div><div><h2 id=\"HSub\">Sub</h2><p>nested body</p></div></div>"
            + "<h2 id=\"HNext\">Next</h2><p>tail</p>");

        String outline = parsed.outline();
        assertTrue(outline.contains("#HTop: Top"), outline);
        assertTrue(outline.contains("\n  #HSub: Sub"), outline);
        assertTrue(outline.contains("\n  #HNext: Next"), outline);
        assertTrue(parsed.hasHeadings(), outline);
        assertFalse(outline.contains("nested body"), outline);
    }

    @Test
    void introPseudoSectionListedOnlyWhenNonEmpty()
    {
        MCPRenderedHtml withIntro = parse("<p>before the first heading</p><h2 id=\"HA\">A</h2>");
        MCPRenderedHtml withoutIntro = parse("<h2 id=\"HA\">A</h2><p>body</p>");
        MCPRenderedHtml noHeadings = parse("<p>only text, no headings</p>");

        assertTrue(withIntro.outline().startsWith("#(intro)"), withIntro.outline());
        assertTrue(withIntro.hasSection("(intro)"));
        assertFalse(withoutIntro.outline().contains("(intro)"), withoutIntro.outline());
        // Without any heading there is no outline at all, hence no intro pseudo-section either.
        assertFalse(noHeadings.hasHeadings());
        assertEquals("", noHeadings.outline());
    }

    @Test
    void statsCountsAppearOnlyWhenNonZeroAndSingularForms()
    {
        MCPRenderedHtml parsed = parse("<h2 id=\"HRich\">Rich</h2>"
            + "<table><tr><td>c</td></tr></table><img src=\"/x.png\" alt=\"a\"/>"
            + "<h2 id=\"HPlain\">Plain</h2><p>text only</p>");

        String outline = parsed.outline();
        String richLine = outline.lines().filter(line -> line.contains("#HRich")).findFirst().orElse("");
        String plainLine = outline.lines().filter(line -> line.contains("#HPlain")).findFirst().orElse("");
        assertTrue(richLine.contains("1 table"), richLine);
        assertFalse(richLine.contains("1 tables"), richLine);
        assertTrue(richLine.contains("1 image"), richLine);
        assertTrue(richLine.contains("tokens"), richLine);
        assertFalse(plainLine.contains("table"), plainLine);
        assertFalse(plainLine.contains("image"), plainLine);
        assertTrue(plainLine.contains("tokens"), plainLine);
    }

    @Test
    void renderingErrorMarkerIsCountedInStats()
    {
        MCPRenderedHtml parsed = parse("<h2 id=\"HErr\">Err</h2>"
            + "<div class=\"xwikirenderingerror\">Failed to execute the [velocity] macro</div>");

        assertTrue(parsed.outline().contains("1 rendering error"), parsed.outline());
    }

    @Test
    void parentSectionStatsAggregateChildSections()
    {
        // The table lives in the h2 child section; the h1 parent's aggregate stats must include it.
        MCPRenderedHtml parsed = parse("<h1 id=\"HParent\">Parent</h1><p>own text</p>"
            + "<h2 id=\"HChild\">Child</h2><table><tr><td>c</td></tr></table>");

        String parentLine = parsed.outline().lines()
            .filter(line -> line.contains("#HParent")).findFirst().orElse("");
        assertTrue(parentLine.contains("1 table"), parentLine);
    }

    @Test
    void sectionSpansToNextSameOrHigherHeadingAcrossDivDepths()
    {
        // Locks in the Range.cloneContents document-order slice: sections end at the next same-or-higher
        // heading even when the two headings sit inside different group divs.
        MCPRenderedHtml parsed = parse("<div><h2 id=\"HA\">A</h2><p>a-body</p></div>"
            + "<div><h2 id=\"HB\">B</h2><p>b-body</p></div>");

        String section = parsed.sectionHtml("HA");
        assertTrue(section.contains(">A</h2>"), section);
        assertTrue(section.contains("a-body"), section);
        assertFalse(section.contains("b-body"), section);
        assertFalse(section.contains(">B</h2>"), section);
    }

    @Test
    void lowerLevelHeadingsStayInsideTheirParentSection()
    {
        MCPRenderedHtml parsed = parse("<h1 id=\"HTop\">Top</h1><p>top-body</p>"
            + "<h2 id=\"HSub\">Sub</h2><p>sub-body</p><h1 id=\"HNext\">Next</h1><p>next-body</p>");

        String section = parsed.sectionHtml("HTop");
        assertTrue(section.contains("top-body"), section);
        assertTrue(section.contains("sub-body"), section);
        assertFalse(section.contains("next-body"), section);
    }

    @Test
    void lastSectionExtendsToEndOfDocument()
    {
        MCPRenderedHtml parsed = parse("<h2 id=\"HA\">A</h2><p>a-body</p>"
            + "<h2 id=\"HLast\">Last</h2><p>last-body</p><p>trailing</p>");

        String section = parsed.sectionHtml("HLast");
        assertTrue(section.contains("last-body"), section);
        assertTrue(section.contains("trailing"), section);
        assertFalse(section.contains("a-body"), section);
    }

    @Test
    void introSectionFetchReturnsContentBeforeFirstHeading()
    {
        MCPRenderedHtml parsed = parse("<p>intro paragraph</p><h2 id=\"HA\">A</h2><p>a-body</p>");

        String section = parsed.sectionHtml("(intro)");
        assertTrue(section.contains("intro paragraph"), section);
        assertFalse(section.contains("a-body"), section);
        assertFalse(section.contains(">A</h2>"), section);
    }

    @Test
    void duplicateAnchorsResolveToFirstMatchInDocumentOrder()
    {
        MCPRenderedHtml parsed = parse("<h2 id=\"HDup\">First</h2><p>first-body</p>"
            + "<h2 id=\"HDup\">Second</h2><p>second-body</p>");

        String section = parsed.sectionHtml("HDup");
        assertTrue(section.contains("first-body"), section);
        assertFalse(section.contains("second-body"), section);
    }

    @Test
    void userDefinedNonHShapedIdIsAccepted()
    {
        MCPRenderedHtml parsed = parse("<h2 id=\"custom-anchor\">Custom</h2><p>body</p>");

        assertTrue(parsed.hasSection("custom-anchor"));
        assertTrue(parsed.outline().contains("#custom-anchor: Custom"), parsed.outline());
    }

    @Test
    void idLessHeadingsGetSyntheticDocumentOrderAnchors()
    {
        // Sheet-generated headings carry no id: they get deterministic (h<N>) anchors from their 1-based
        // document-order position among ALL headings, so they stay addressable.
        MCPRenderedHtml parsed = parse("<h2>NoId</h2><p>first-body</p>"
            + "<h3 id=\"HReal\">Real</h3><h2>Another</h2><p>another-body</p>");

        String outline = parsed.outline();
        assertTrue(outline.contains("#(h1): NoId"), outline);
        assertTrue(outline.contains("#HReal: Real"), outline);
        assertTrue(outline.contains("#(h3): Another"), outline);
        String section = parsed.sectionHtml("(h1)");
        assertTrue(section.contains("first-body"), section);
        assertFalse(section.contains("another-body"), section);
    }

    @Test
    void headingTitleWhitespaceIsCollapsed()
    {
        MCPRenderedHtml parsed = parse("<h2 id=\"HX\">Spaced\n  <span>out</span>  title</h2>");

        assertTrue(parsed.outline().contains("#HX: Spaced out title"), parsed.outline());
    }

    @Test
    void sectionOutlineReturnsOwnEntryPlusSubHeadingsWithAbsoluteIndentation()
    {
        MCPRenderedHtml parsed = parse("<h1 id=\"HTop\">Top</h1><h2 id=\"HSub\">Sub</h2>"
            + "<h3 id=\"HLeaf\">Leaf</h3><h1 id=\"HNext\">Next</h1>");

        String subOutline = parsed.sectionOutline("HTop");
        assertTrue(subOutline.contains("#HTop: Top"), subOutline);
        assertTrue(subOutline.contains("\n  #HSub: Sub"), subOutline);
        assertTrue(subOutline.contains("\n    #HLeaf: Leaf"), subOutline);
        assertFalse(subOutline.contains("#HNext"), subOutline);
        // A leaf section (no sub-headings) and the intro have no sub-outline at all.
        assertEquals("", parsed.sectionOutline("HLeaf"));
        assertEquals("", parsed.sectionOutline("(intro)"));
    }

    @Test
    void normalizeAnchorTrimsAndStripsOneLeadingHash()
    {
        assertEquals("HFoo", MCPRenderedHtml.normalizeAnchor("#HFoo"));
        assertEquals("HFoo", MCPRenderedHtml.normalizeAnchor("  HFoo  "));
        assertEquals("#HFoo", MCPRenderedHtml.normalizeAnchor("##HFoo"));
        assertEquals("(intro)", MCPRenderedHtml.normalizeAnchor("#(intro)"));
        assertEquals("", MCPRenderedHtml.normalizeAnchor("#"));
        assertEquals("", MCPRenderedHtml.normalizeAnchor(null));
    }

    @Test
    void approxCharsReflectsContentSize()
    {
        MCPRenderedHtml small = parse("<p>tiny</p>");
        MCPRenderedHtml large = parse("<p>" + "x".repeat(4000) + "</p>");

        assertTrue(small.approxChars() > 0);
        assertTrue(large.approxChars() > small.approxChars());
        assertTrue(large.approxChars() >= 4000);
    }

    @Test
    void secondTerminalCallThrowsIllegalState()
    {
        MCPRenderedHtml serializedFirst = parse("<h2 id=\"HA\">A</h2><p>body</p>");
        serializedFirst.serialize();
        assertThrows(IllegalStateException.class, serializedFirst::serialize);
        assertThrows(IllegalStateException.class, () -> serializedFirst.sectionHtml("HA"));

        MCPRenderedHtml sectionFirst = parse("<h2 id=\"HA\">A</h2><p>body</p>");
        sectionFirst.sectionHtml("HA");
        assertThrows(IllegalStateException.class, sectionFirst::serialize);
    }

    @Test
    void unknownAnchorOnSectionHtmlThrowsIllegalArgument()
    {
        MCPRenderedHtml parsed = parse("<p>no headings here</p>");

        assertThrows(IllegalArgumentException.class, () -> parsed.sectionHtml("HNope"));
    }

    private static int tokenCount(String entry)
    {
        Matcher matcher = TOKEN_COUNT.matcher(entry);
        assertTrue(matcher.find(), entry);
        return Integer.parseInt(matcher.group(1));
    }

    private static Set<String> rowMarkers(String content)
    {
        Set<String> markers = new HashSet<>();
        Matcher matcher = ROW_MARKER.matcher(content);
        while (matcher.find()) {
            markers.add(matcher.group());
        }
        return markers;
    }

    @Test
    void chunkPartitionIsDeterministicAcrossParses()
    {
        StringBuilder html = new StringBuilder("<h2 id=\"HBig\">Big</h2>");
        for (int i = 0; i < 10; i++) {
            html.append("<p>paragraph").append(i).append(' ').append("word ".repeat(700)).append("</p>");
        }

        List<String> firstParse = parse(html.toString()).chunkMapEntries("HBig", 1);
        List<String> secondParse = parse(html.toString()).chunkMapEntries("HBig", 1);

        assertFalse(firstParse.isEmpty());
        assertEquals(firstParse, secondParse);
    }

    @Test
    void greedyPackingPacksConsecutiveParagraphsWithinChunkTarget()
    {
        // Ten paragraphs, each estimated at 3009 chars (3000 chars of text plus the <p> markup):
        // greedy packing closes a run at five paragraphs (15045 chars), just under the 16000-char
        // chunk target, so the ten paragraphs partition into exactly two chunks of five.
        String para = "<p>" + "word ".repeat(600) + "</p>";
        MCPRenderedHtml parsed = parse(para.repeat(10));

        assertEquals(2, parsed.chunkCount(INTRO));
        for (String entry : parsed.chunkMapEntries(INTRO, 1)) {
            int tokens = tokenCount(entry);
            assertTrue(tokens <= 4000, entry);
            assertTrue(tokens > 3000, entry);
        }
        assertEquals(List.of(), parsed.chunkColumns(INTRO, 1),
            "Paragraph-run chunks carry no column headers");
    }

    @Test
    void oversizedTableDescendsIntoRowRunsWithoutLeakingAcrossChunkBoundaries()
    {
        StringBuilder html = new StringBuilder("<h2 id=\"HT\">T</h2><table>");
        for (int i = 1; i <= 40; i++) {
            html.append("<tr><td>").append(String.format("ROW-%02d ", i)).append("x".repeat(770))
                .append("</td></tr>");
        }
        html.append("</table>");

        MCPRenderedHtml parsed = parse(html.toString());
        int chunkCount = parsed.chunkCount("HT");
        // The heading forms its own small chunk, then the table (over the target as a whole) is
        // descended into row runs spanning several chunks.
        assertTrue(chunkCount >= 3, "chunks: " + chunkCount);
        assertEquals(List.of(), parsed.chunkColumns("HT", 2),
            "A table without a th header row yields no column headers");

        String secondChunk = parsed.chunkHtml("HT", 2);
        String lastChunk = parse(html.toString()).chunkHtml("HT", chunkCount);
        assertTrue(secondChunk.contains("ROW-01"), secondChunk.substring(0, 200));
        assertFalse(secondChunk.contains("<table"), "Descended chunks hold row runs, not the whole table");
        assertTrue(lastChunk.contains("ROW-40"), lastChunk.substring(0, 200));
        Set<String> secondMarkers = rowMarkers(secondChunk);
        Set<String> lastMarkers = rowMarkers(lastChunk);
        assertFalse(secondMarkers.isEmpty());
        assertFalse(lastMarkers.isEmpty());
        secondMarkers.retainAll(lastMarkers);
        assertTrue(secondMarkers.isEmpty(), "Rows must not leak across chunk boundaries: " + secondMarkers);
    }

    @Test
    void siblingAfterOversizedDescendedTableResumesPackingAtItsOwnLevel()
    {
        // The table alone exceeds the chunk target, so packing descends into its row runs; the
        // paragraph following the table must then open a fresh top-level chunk instead of being
        // appended to the last row run.
        StringBuilder html = new StringBuilder("<table>");
        for (int i = 1; i <= 40; i++) {
            html.append("<tr><td>").append(String.format("ROW-%02d ", i)).append("x".repeat(770))
                .append("</td></tr>");
        }
        html.append("</table><p>AFTER</p>");

        MCPRenderedHtml parsed = parse(html.toString());
        int chunkCount = parsed.chunkCount(INTRO);
        assertTrue(chunkCount >= 3, "chunks: " + chunkCount);
        String lastEntry = parsed.chunkMapEntries(INTRO, 1).get(chunkCount - 1);
        assertTrue(lastEntry.contains("\"AFTER\""), lastEntry);
        String lastChunk = parsed.chunkHtml(INTRO, chunkCount);
        assertTrue(lastChunk.contains("<p>AFTER</p>"), lastChunk);
        assertFalse(lastChunk.contains("<tr"), "AFTER must not share a chunk with row runs: " + lastChunk);
    }

    @Test
    void atomicOversizedNodeBecomesItsOwnChunk()
    {
        // A single childless <pre> far over the chunk target cannot be descended into: it becomes one
        // oversized chunk (the atomic floor), left for the fetch path to cap.
        MCPRenderedHtml parsed = parse("<pre>" + "y".repeat(30000) + "</pre>");

        assertEquals(1, parsed.chunkCount(INTRO));
        assertTrue(tokenCount(parsed.chunkMapEntries(INTRO, 1).get(0)) > 4000);
        assertTrue(parsed.chunkHtml(INTRO, 1).contains("yyyy"));
    }

    @Test
    void introParentChunksTheWholeBodyOfAHeadinglessDocument()
    {
        String html = "<p>P-ONE " + "a ".repeat(4500) + "</p><p>P-TWO " + "b ".repeat(4500)
            + "</p><p>P-THREE " + "c ".repeat(4500) + "</p>";
        MCPRenderedHtml parsed = parse(html);

        assertFalse(parsed.hasHeadings());
        assertEquals(3, parsed.chunkCount(INTRO));
        assertEquals(INTRO, parsed.chunkParent("(intro/2)"));
        assertEquals(2, parsed.chunkOrdinal("(intro/2)"));
        String chunk = parsed.chunkHtml(INTRO, 2);
        assertTrue(chunk.contains("P-TWO"), chunk.substring(0, 100));
        assertFalse(chunk.contains("P-ONE"), "Chunk 2 must not contain chunk 1 content");
        assertFalse(chunk.contains("P-THREE"), "Chunk 2 must not contain chunk 3 content");
    }

    @Test
    void chunkAnchorParsingSplitsAtLastSlashAndResolvesParents()
    {
        MCPRenderedHtml parsed = parse("<h2 id=\"a/b\">Slashed</h2><p>body</p><h2>NoId</h2><p>x</p>");

        // A heading id containing a slash still resolves as a plain section anchor (headings first).
        assertTrue(parsed.hasSection("a/b"));
        // The last-slash split keeps the full id as the parent.
        assertEquals("a/b", parsed.chunkParent("a/b/2"));
        assertEquals(2, parsed.chunkOrdinal("a/b/2"));
        // A synthetic parent resolves in its bare and parenthesized spellings alike.
        assertEquals("(h2)", parsed.chunkParent("(h2/1)"));
        assertEquals("(h2)", parsed.chunkParent("((h2)/3)"));
        // Map pages parse through the same grammar.
        assertEquals("a/b", parsed.chunkParent("(a/b/map2)"));
        assertEquals(2, parsed.chunkMapPage("(a/b/map2)"));
        assertEquals(0, parsed.chunkOrdinal("(a/b/map2)"));
    }

    @Test
    void malformedOrUnresolvableChunkAnchorsReturnNull()
    {
        MCPRenderedHtml parsed = parse("<h2 id=\"HA\">A</h2><p>body</p>");

        assertNull(parsed.chunkParent("(HA/0)"), "Ordinals are 1-based");
        assertNull(parsed.chunkParent("(HA/x1)"), "Non-digit ordinal");
        assertNull(parsed.chunkParent("(HA/)"), "Empty ordinal");
        assertNull(parsed.chunkParent("(HA/map0)"), "Map pages are 1-based");
        assertNull(parsed.chunkParent("(HNope/1)"), "Unknown parent");
        assertNull(parsed.chunkParent("HA"), "No slash");
        // The intro parent only covers the whole body when the document has no headings at all.
        assertNull(parsed.chunkParent("(intro/1)"));
    }

    @Test
    void chunkTitleIsFirstEightWordsQuotedWithEllipsisWhenCut()
    {
        String cut = parse("<p>one two three four five six seven eight nine ten</p>")
            .chunkMapEntries(INTRO, 1).get(0);
        String whole = parse("<p>just three words</p>").chunkMapEntries(INTRO, 1).get(0);

        assertTrue(cut.contains("\"one two three four five six seven eight...\""), cut);
        assertFalse(cut.contains("nine"), cut);
        assertTrue(whole.contains("\"just three words\""), whole);
        assertFalse(whole.contains("..."), whole);
    }

    @Test
    void blankTextChunkTitleFallsBackToFirstElementTag()
    {
        String entry = parse("<table><tr><td><img src=\"/x.png\" alt=\"\"/></td></tr></table>")
            .chunkMapEntries(INTRO, 1).get(0);

        assertTrue(entry.contains("[table]"), entry);
        assertTrue(entry.contains("1 table"), entry);
    }

    @Test
    void chunkMapPaginatesWholeEntriesAtTheOutputBudget()
    {
        // 200 paragraphs of ~15000 chars: one chunk each, and the ~29000 chars of entry lines split
        // into two map pages at the output budget.
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            html.append("<p>").append("wordwordword ".repeat(1154)).append("</p>");
        }
        MCPRenderedHtml parsed = parse(html.toString());

        assertEquals(200, parsed.chunkCount(INTRO));
        int pages = parsed.chunkMapPageCount(INTRO);
        assertTrue(pages >= 2, "pages: " + pages);
        List<String> pageOne = parsed.chunkMapEntries(INTRO, 1);
        assertTrue(pageOne.stream().mapToInt(entry -> entry.length() + 1).sum() <= MCPSourceText.MAX_OUTPUT_CHARS);
        // Later pages continue the global chunk numbering where the previous page stopped.
        assertEquals(pageOne.size() + 1, parsed.chunkMapPageStart(INTRO, 2));
        assertTrue(parsed.chunkMapEntries(INTRO, 2).get(0).startsWith("#((intro)/" + (pageOne.size() + 1) + ")"));
        int listed = 0;
        for (int page = 1; page <= pages; page++) {
            listed += parsed.chunkMapEntries(INTRO, page).size();
        }
        assertEquals(200, listed, "Every chunk must be listed on exactly one page");
    }

    @Test
    void chunkHtmlIsTerminal()
    {
        MCPRenderedHtml chunkFirst = parse("<p>alpha beta</p>");
        chunkFirst.chunkHtml(INTRO, 1);
        assertThrows(IllegalStateException.class, chunkFirst::serialize);

        MCPRenderedHtml serializeFirst = parse("<p>alpha beta</p>");
        serializeFirst.serialize();
        assertThrows(IllegalStateException.class, () -> serializeFirst.chunkHtml(INTRO, 1));
    }

    @Test
    void fullDetailKeepsAllAttributesOnOrdinaryElements()
    {
        String out = stripFull("<p style=\"color:red\" class=\"someclass\" id=\"p1\" data-marker=\"m1\">text "
            + "<span style=\"font-weight:bold\">inside</span></p><div id=\"container\">x</div>");

        assertTrue(out.contains("style=\"color:red\""), out);
        assertTrue(out.contains("class=\"someclass\""), out);
        assertTrue(out.contains("id=\"p1\""), out);
        assertTrue(out.contains("data-marker=\"m1\""), out);
        assertTrue(out.contains("style=\"font-weight:bold\""), out);
        assertTrue(out.contains("id=\"container\""), out);
    }

    @Test
    void fullDetailStillDropsScriptStyleCommentsAndErrorDescription()
    {
        String out = stripFull("<p class=\"keepme\">keep</p><script>alert('payload')</script>"
            + "<style>.a{color:red}</style><!-- secret note -->"
            + "<div class=\"xwikirenderingerror\">Failed to execute the [velocity] macro</div>"
            + "<div class=\"xwikirenderingerrordescription hidden\"><pre>at a.b.C(C.java:1)</pre></div>");

        assertTrue(out.contains("class=\"keepme\""), out);
        assertFalse(out.contains("<script"), out);
        assertFalse(out.contains("payload"), out);
        assertFalse(out.contains("<style"), out);
        assertFalse(out.contains("color:red"), out);
        assertFalse(out.contains("secret note"), out);
        assertTrue(out.contains("Failed to execute"), out);
        assertFalse(out.contains("xwikirenderingerrordescription"), out);
        assertFalse(out.contains("at a.b.C"), out);
    }

    @Test
    void fullDetailShortensAttributeValuesAtTheExactBoundary()
    {
        String over = "a".repeat(501);
        String exact = "b".repeat(500);
        String out = stripFull("<p data-over=\"" + over + "\" data-exact=\"" + exact + "\">x</p>");

        assertTrue(out.contains("data-over=\"" + "a".repeat(500) + "[...shortened]\""),
            "A 501-char value must be cut at 500 chars and marked");
        assertFalse(out.contains(over), "The over-threshold value must not survive whole");
        assertTrue(out.contains("data-exact=\"" + exact + "\""), "A 500-char value must survive whole");
        assertFalse(out.contains(exact + "[...shortened]"), "A 500-char value must not be marked");
    }

    @Test
    void fullDetailKeepsUnderThresholdHrefWhole()
    {
        String href = "https://example.org/" + "p".repeat(80);
        String out = stripFull("<p><a href=\"" + href + "\">go</a></p>");

        assertTrue(out.contains("href=\"" + href + "\""), out);
        assertFalse(out.contains("[...shortened]"), out);
    }

    @Test
    void strippedDetailNeverShortensItsKeptAttributes()
    {
        // The shortening inversion is deliberate and documented: stripped mode emits its allowlisted
        // attributes whole (a long link target must stay usable), only full mode shortens.
        String href = "https://example.org/" + "p".repeat(600);
        String out = strip("<p><a href=\"" + href + "\">go</a></p>");

        assertTrue(out.contains("href=\"" + href + "\""), out);
        assertFalse(out.contains("[...shortened]"), out);
    }

    @Test
    void fullDetailNeverShortensTheClassAttribute()
    {
        // The class attribute is exempt from shortening: its tokens drive the section walk's
        // rendering-error stats, and a cut must not hide a token from the walk.
        String classes = "xwikirenderingerror " + "tok ".repeat(150);
        String out = stripFull("<div class=\"" + classes.trim() + "\">boom</div>");

        assertTrue(out.contains("xwikirenderingerror"), out);
        assertFalse(out.contains("[...shortened]"), out);
    }

    @Test
    void fullDetailAttributeCutBacksOffADanglingHighSurrogate()
    {
        // Chars 0-498 are 'a', the emoji occupies UTF-16 indices 499-500 and the trailing 'b' pushes the
        // length to 502, over the threshold. A cut at 500 would split the surrogate pair, so the cut
        // backs off to 499 and the emoji is gone before serialization.
        String value = "a".repeat(499) + "😀" + "b";
        String out = stripFull("<p data-cut=\"" + value + "\">x</p>");

        assertTrue(out.contains("data-cut=\"" + "a".repeat(499) + "[...shortened]\""),
            "The cut must back off the dangling high surrogate");
        assertFalse(out.contains("😀"), out);
        assertFalse(out.contains("&#x1f600;"), out);
    }

    @Test
    void fullDetailEstimatesCountKeptAttributes()
    {
        // The 400-char style attribute is dropped in the stripped detail but kept in the full detail,
        // so the same input estimates larger and its outline reports more tokens.
        String html = "<h2 id=\"HA\">A</h2><p style=\"" + "c".repeat(400) + "\">body</p>";
        MCPRenderedHtml stripped = parse(html);
        MCPRenderedHtml full = parseFull(html);

        assertTrue(full.approxChars() >= stripped.approxChars() + 400,
            "full=" + full.approxChars() + " stripped=" + stripped.approxChars());
        assertTrue(tokenCount(full.outline()) > tokenCount(stripped.outline()),
            "full outline: " + full.outline() + " stripped outline: " + stripped.outline());
    }

    @Test
    void fullDetailSectionFetchCarriesTheAttributes()
    {
        MCPRenderedHtml parsed = parseFull("<h2 id=\"HA\">A</h2><p class=\"fancy\" style=\"color:red\">"
            + "a-body</p><h2 id=\"HB\">B</h2><p class=\"other\">b-body</p>");

        String section = parsed.sectionHtml("HA");
        assertTrue(section.contains("class=\"fancy\""), section);
        assertTrue(section.contains("style=\"color:red\""), section);
        assertTrue(section.contains("a-body"), section);
        assertFalse(section.contains("b-body"), section);
    }

    @Test
    void rowRunChunksOfATableCarryItsHeaderRowColumnTexts()
    {
        // A th header row followed by fat data rows: the table alone exceeds the chunk target, so the
        // partition descends into row runs; every chunk inside the table carries the header columns.
        StringBuilder html = new StringBuilder("<h2 id=\"HT\">T</h2><table>"
            + "<tr><th>Name</th><th>Value</th><th>Note</th></tr>");
        for (int i = 1; i <= 40; i++) {
            html.append("<tr><td>").append(String.format("ROW-%02d ", i)).append("x".repeat(770))
                .append("</td></tr>");
        }
        html.append("</table>");

        MCPRenderedHtml parsed = parse(html.toString());
        int chunkCount = parsed.chunkCount("HT");
        assertTrue(chunkCount >= 3, "chunks: " + chunkCount);
        List<String> expected = List.of("Name", "Value", "Note");
        // The heading chunk sits outside the table and carries no columns.
        assertEquals(List.of(), parsed.chunkColumns("HT", 1));
        // Every row-run chunk of the same table carries the identical column list.
        for (int i = 2; i <= chunkCount; i++) {
            assertEquals(expected, parsed.chunkColumns("HT", i), "chunk " + i);
        }
        // Deterministic: a fresh parse of the same content extracts the same columns.
        assertEquals(expected, parse(html.toString()).chunkColumns("HT", 2));
    }

    @Test
    void theadWrappedHeaderRowIsFoundAndThWhitespaceIsCollapsed()
    {
        StringBuilder html = new StringBuilder("<table><thead><tr><th>  First\n <span>Name</span></th>"
            + "<th>Second</th></tr></thead><tbody>");
        for (int i = 1; i <= 40; i++) {
            html.append("<tr><td>").append("x".repeat(770)).append("</td></tr>");
        }
        html.append("</tbody></table>");

        MCPRenderedHtml parsed = parse(html.toString());
        int chunkCount = parsed.chunkCount(INTRO);
        assertTrue(chunkCount >= 2, "chunks: " + chunkCount);
        assertEquals(List.of("First Name", "Second"), parsed.chunkColumns(INTRO, 1));
        assertEquals(List.of("First Name", "Second"), parsed.chunkColumns(INTRO, chunkCount));
    }

    @Test
    void fullDetailChunkPartitionAndFetchCarryAttributes()
    {
        String para = "<p data-run=\"r1\">" + "word ".repeat(600) + "</p>";
        MCPRenderedHtml parsed = parseFull(para.repeat(10));

        assertTrue(parsed.chunkCount(INTRO) >= 2, "chunks: " + parsed.chunkCount(INTRO));
        String chunk = parsed.chunkHtml(INTRO, 1);
        assertTrue(chunk.contains("data-run=\"r1\""), chunk.substring(0, 200));
    }
}
