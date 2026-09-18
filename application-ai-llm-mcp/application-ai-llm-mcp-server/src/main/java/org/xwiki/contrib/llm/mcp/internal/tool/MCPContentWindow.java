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

import org.xwiki.contrib.llm.mcp.MCPSourceText;

/**
 * The shared line-window grammar of the document-content reads: {@code get_document} range reads and
 * headless-large-document heads, and {@code get_history} version-mode snapshots. One place holds the
 * budgeted end-line computation and the {@code Showing lines a-b of n.} footer with its same-line
 * continuation hint, so the two tools cannot drift apart on the window mechanics, the 1-based offset
 * convention or the continuation wording.
 *
 * <p>The attachment text window ({@link MCPAttachmentSupport}) is deliberately NOT this grammar: it
 * streams unnumbered content with 0-based skip offsets and mid-line cuts, and its continuation number
 * follows its own convention.</p>
 *
 * @version $Id$
 * @since 0.10
 */
final class MCPContentWindow
{
    /**
     * Range separator of the shown-lines texts.
     */
    static final String DASH = "-";

    /**
     * Infix of the x-of-y range texts.
     */
    static final String OF_INFIX = " of ";

    /**
     * The offset-assignment fragment closing the continuation hints, directly followed by the offset
     * value. Matches the tools' {@code offset} parameter name.
     */
    static final String OFFSET_EQUALS = "offset=";

    /**
     * Opens the truncation note of a content window cut at the output budget, completed by the
     * continuation offset. The leading space continues the shown-lines footer sentence on the same
     * line.
     */
    static final String CONTINUATION_PREFIX = " Output truncated at the ~" + MCPSourceText.MAX_OUTPUT_TOKENS
        + "-token cap; continue with " + OFFSET_EQUALS;

    private static final String PERIOD = ".";

    private MCPContentWindow()
    {
    }

    /**
     * Returns the largest line index in {@code [start..requestedEnd]} whose cumulative character count
     * (including a newline per line) does not exceed {@code maxChars}. Always returns at least
     * {@code start}, so a single oversized line is emitted whole rather than truncated mid-line,
     * preserving edit-ability.
     *
     * @param lines the content lines
     * @param start the 1-based first line to emit
     * @param requestedEnd the 1-based last line the caller asked for
     * @param maxChars the cumulative character budget for the emitted window
     * @return the 1-based capped end line index
     */
    static int cappedEnd(String[] lines, int start, int requestedEnd, int maxChars)
    {
        long total = 0;
        for (int i = start; i <= requestedEnd; i++) {
            total += (long) lines[i - 1].length() + 1;
            if (total > maxChars && i > start) {
                return i - 1;
            }
        }
        return requestedEnd;
    }

    /**
     * Composes the shown-lines sentence of a window's footer.
     *
     * @param start the 1-based first emitted line
     * @param end the 1-based last emitted line
     * @param totalLines the content's total line count
     * @return the {@code Showing lines a-b of n.} sentence
     */
    static String showingLines(int start, int end, int totalLines)
    {
        return "Showing lines " + start + DASH + end + OF_INFIX + totalLines + PERIOD;
    }

    /**
     * Composes a window's complete footer: the shown-lines sentence, continued on the same line by the
     * truncation-and-continuation hint (with the 1-based offset of the next unread line) when the
     * output budget cut the window below the requested end.
     *
     * @param start the 1-based first emitted line
     * @param actualEnd the 1-based last emitted line, as capped by {@link #cappedEnd}
     * @param requestedEnd the 1-based last line the caller asked for
     * @param totalLines the content's total line count
     * @return the composed footer
     */
    static String footer(int start, int actualEnd, int requestedEnd, int totalLines)
    {
        String footer = showingLines(start, actualEnd, totalLines);
        if (actualEnd < requestedEnd) {
            footer += CONTINUATION_PREFIX + (actualEnd + 1) + PERIOD;
        }
        return footer;
    }
}
