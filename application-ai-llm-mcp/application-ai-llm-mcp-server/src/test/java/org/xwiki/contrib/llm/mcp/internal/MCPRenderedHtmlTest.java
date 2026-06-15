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

    private String strip(String html)
    {
        return MCPRenderedHtml.strip(this.cleaner, html);
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
}
