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

/**
 * Shared source-text helpers used by the document read and edit tools, so that line-ending
 * normalization and line numbering stay identical across both (an edit's {@code old_string} is
 * formed from the read tool's normalized output and must match byte-for-byte).
 *
 * @version $Id$
 * @since 0.9
 */
final class MCPSourceText
{
    private static final String LF = "\n";

    private MCPSourceText()
    {
    }

    /**
     * Normalizes line endings to LF, mapping {@code \r\n} and lone {@code \r} to {@code \n}. A
     * {@code null} input yields an empty string.
     *
     * @param content the raw content, possibly {@code null}
     * @return the LF-normalized content, never {@code null}
     */
    static String normalizeLineEndings(String content)
    {
        if (content == null) {
            return "";
        }
        return content.replace("\r\n", LF).replace("\r", LF);
    }

    /**
     * Renders the 1-based line range {@code [start, end]} as {@code cat -n} style numbered lines
     * (six-wide right-aligned number, a tab, then the line), joined by LF.
     *
     * @param lines the document lines
     * @param start the 1-based first line, inclusive
     * @param end the 1-based last line, inclusive
     * @return the numbered block
     */
    static String numberedLines(String[] lines, int start, int end)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) {
                sb.append(LF);
            }
            sb.append(String.format("%6d\t%s", i, lines[i - 1]));
        }
        return sb.toString();
    }
}
