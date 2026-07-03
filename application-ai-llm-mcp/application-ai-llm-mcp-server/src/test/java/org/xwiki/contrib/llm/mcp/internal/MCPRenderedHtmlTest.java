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
}
