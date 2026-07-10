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
package org.xwiki.contrib.llm.mcp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the static source-text helpers of {@link MCPSourceText}.
 *
 * @version $Id$
 */
class MCPSourceTextTest
{
    private static final String XWIKI_SYNTAX = "xwiki/2.1";

    @Test
    void normalizeLineEndingsMapsCrlfAndLoneCrToLf()
    {
        assertEquals("one\ntwo\nthree", MCPSourceText.normalizeLineEndings("one\r\ntwo\rthree"));
    }

    @Test
    void normalizeLineEndingsMapsNullToEmptyString()
    {
        assertEquals("", MCPSourceText.normalizeLineEndings(null));
    }

    @Test
    void numberedLinesFormatsTheRequestedRangeCatStyle()
    {
        String[] lines = {"first", "second", "third", "fourth"};

        assertEquals("     2\tsecond\n     3\tthird", MCPSourceText.numberedLines(lines, 2, 3));
    }

    @Test
    void numberedLinesFormatsASingleLineWithoutTrailingBreak()
    {
        String[] lines = {"only"};

        assertEquals("     1\tonly", MCPSourceText.numberedLines(lines, 1, 1));
    }

    @Test
    void hasHeadingPatternKnowsXWikiAndMarkdownSyntaxesOnly()
    {
        assertTrue(MCPSourceText.hasHeadingPattern(XWIKI_SYNTAX));
        assertTrue(MCPSourceText.hasHeadingPattern("markdown/1.2"));
        assertFalse(MCPSourceText.hasHeadingPattern("plain/1.0"));
    }

    @Test
    void collectHeadingLinesFindsXWikiHeadingsWithLineNumbersAndIndentation()
    {
        String[] lines = {
            "= Title =",
            "some text",
            "== Section ==",
            "more text",
        };

        List<String> headings = MCPSourceText.collectHeadingLines(lines, lines.length, XWIKI_SYNTAX);

        assertEquals(List.of("L1: Title", "  L3: Section"), headings);
    }

    @Test
    void collectHeadingLinesCleansStyleMarkersAndCollapsesLinkLabels()
    {
        String[] lines = {
            "== (% class=\"fancy\" %)Styled(%%) heading ==",
            "== See [[the label>>Space.Page]] here ==",
        };

        List<String> headings = MCPSourceText.collectHeadingLines(lines, lines.length, XWIKI_SYNTAX);

        assertEquals(List.of("  L1: Styled heading", "  L2: See the label here"), headings);
    }

    @Test
    void collectHeadingLinesReturnsEmptyListForSyntaxWithoutHeadingPattern()
    {
        String[] lines = {"= Not scanned ="};

        assertEquals(List.of(), MCPSourceText.collectHeadingLines(lines, lines.length, "plain/1.0"));
    }

    @Test
    void collapseStackTracesReplacesFrameRunsWithAMarker()
    {
        String text = String.join("\n",
            "java.lang.IllegalStateException: boom",
            "    at com.example.Foo.bar(Foo.java:10)",
            "    at com.example.Baz.qux(Baz.java:20)",
            "    ... 12 more",
            "Caused by: java.io.IOException: disk");

        String collapsed = MCPSourceText.collapseStackTraces(text);

        assertEquals(String.join("\n",
            "java.lang.IllegalStateException: boom",
            "[... 3 stack frames omitted ...]",
            "Caused by: java.io.IOException: disk"), collapsed);
    }

    @Test
    void collapseStackTracesLeavesShortRunsAndProseAlone()
    {
        String text = String.join("\n",
            "We met at the station.",
            "    at com.example.Foo.bar(Foo.java:10)",
            "    at com.example.Baz.qux(Baz.java:20)",
            "The end.");

        assertEquals(text, MCPSourceText.collapseStackTraces(text));
    }
}
