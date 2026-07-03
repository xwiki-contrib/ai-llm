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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared source-text helpers used by the document read and edit tools, so that line-ending
 * normalization and line numbering stay identical across both (an edit's {@code old_string} is
 * formed from the read tool's normalized output and must match byte-for-byte). Also owns the
 * regex-based source-text scans of the read tool: the source heading outline and the stack-frame
 * collapsing of rendered plain text.
 *
 * @version $Id$
 * @since 0.9
 */
final class MCPSourceText
{
    /**
     * Rough characters-per-token heuristic used wherever an approximate token count is surfaced to the
     * agent (response headers, outline size estimates). Shared so all sizes use the same scale.
     */
    static final int CHARS_PER_TOKEN = 4;

    private static final String LF = "\n";

    private static final String XWIKI_SYNTAX_PREFIX = "xwiki/";

    private static final String MARKDOWN_SYNTAX_PREFIX = "markdown";

    /**
     * Heading pattern for XWiki syntaxes: leading {@code =} run sets the level, optional trailing
     * {@code =} run is stripped.
     */
    private static final Pattern XWIKI_HEADING = Pattern.compile("^(={1,6})\\s+(.+?)\\s*=*\\s*$");

    /**
     * Heading pattern for Markdown syntaxes: leading {@code #} run sets the level, optional trailing
     * {@code #} run is stripped.
     */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /**
     * Matches XWiki group/style markers such as {@code (% class="x" %)} and {@code (%%)}, so they can be
     * stripped from an outline title. Non-greedy and bounded by {@code (%}/{@code %)}, so no catastrophic
     * backtracking.
     */
    private static final Pattern XWIKI_STYLE_MARKER = Pattern.compile("\\(%.*?%\\)");

    /**
     * Matches an XWiki link {@code [[ ... ]]} so its label (or target, when unlabelled) can replace the raw
     * markup in an outline title. Non-greedy and bounded by {@code [[}/{@code ]]}.
     */
    private static final Pattern XWIKI_LINK = Pattern.compile("\\[\\[(.*?)\\]\\]");

    /**
     * Matches a run of two or more whitespace characters, collapsed to a single space after markup stripping.
     */
    private static final Pattern MULTIPLE_SPACES = Pattern.compile("\\s{2,}");

    private static final String LINK_LABEL_SEPARATOR = ">>";

    private static final String LINK_PARAM_SEPARATOR = "||";

    /**
     * Minimum number of consecutive JVM stack-frame lines a run must reach before it is collapsed to a
     * single marker. Below it, the lines pass through verbatim, so an incidental one- or two-line
     * {@code at ...} mention in prose is never touched.
     */
    private static final int STACK_FRAME_COLLAPSE_THRESHOLD = 3;

    /**
     * Marker substituted for a collapsed run of stack frames, parameterised by the run length.
     */
    private static final String STACK_FRAMES_OMITTED_MARKER = "[... %d stack frames omitted ...]";

    /**
     * Matches a JVM stack-frame line ({@code at fully.qualified.Method(Source.java:NN)}) or a frame
     * continuation line ({@code ... N more} / {@code ... N common frames omitted}), anchored on the
     * whole line so ordinary prose containing the word "at" is not matched.
     */
    private static final Pattern STACK_FRAME_LINE =
        Pattern.compile("^\\s*at\\s+\\S.*\\(.*\\)\\s*$|^\\s*\\.\\.\\.\\s+\\d+\\s+(more|common frames omitted)\\s*$");

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

    /**
     * Tells whether the given source syntax has a heading pattern, i.e. whether a source outline can be
     * scanned for it at all.
     *
     * @param syntaxId the document syntax identifier
     * @return whether a heading pattern exists for the syntax
     */
    static boolean hasHeadingPattern(String syntaxId)
    {
        return headingPatternFor(syntaxId) != null;
    }

    /**
     * Collects the formatted outline entries for a source document. Returns an empty list when the syntax
     * has no heading pattern or when no line matches, so callers can branch on emptiness without sniffing
     * a formatted message.
     *
     * @param lines the document lines
     * @param totalLines the total number of lines
     * @param syntaxId the document syntax identifier
     * @return the formatted heading entries, or an empty list if none
     */
    static List<String> collectHeadingLines(String[] lines, int totalLines, String syntaxId)
    {
        List<String> headings = new ArrayList<>();
        Pattern pattern = headingPatternFor(syntaxId);
        if (pattern == null) {
            return headings;
        }
        for (int i = 1; i <= totalLines; i++) {
            appendHeading(headings, pattern, lines[i - 1], i);
        }
        return headings;
    }

    /**
     * Collapses runs of consecutive JVM stack-frame lines in rendered plain output for token economy. A
     * maximal run of {@value #STACK_FRAME_COLLAPSE_THRESHOLD} or more frame lines (a frame being
     * {@code at type.method(Source:NN)} or a {@code ... N more} continuation, matched anchored on the
     * line so prose is untouched) is replaced by a single {@code [... N stack frames omitted ...]} marker;
     * shorter runs and every non-frame line pass through unchanged, so the exception header, any
     * {@code Caused by:} chain and the human message are preserved.
     *
     * @param text the LF-normalized rendered plain content
     * @return the content with stack-frame runs collapsed
     */
    static String collapseStackTraces(String text)
    {
        String[] lines = text.split(LF, -1);
        List<String> out = new ArrayList<>();
        List<String> run = new ArrayList<>();
        for (String line : lines) {
            if (STACK_FRAME_LINE.matcher(line).matches()) {
                run.add(line);
            } else {
                flushFrameRun(out, run);
                out.add(line);
            }
        }
        flushFrameRun(out, run);
        return String.join(LF, out);
    }

    /**
     * Flushes the accumulated frame-line run into {@code out}: a run of
     * {@value #STACK_FRAME_COLLAPSE_THRESHOLD} or more lines is replaced by a single marker, a shorter run
     * is re-emitted verbatim, and an empty run is a no-op. The run buffer is cleared either way.
     *
     * @param out the output line accumulator
     * @param run the buffered frame lines (mutated: cleared on return)
     */
    private static void flushFrameRun(List<String> out, List<String> run)
    {
        if (run.isEmpty()) {
            return;
        }
        if (run.size() >= STACK_FRAME_COLLAPSE_THRESHOLD) {
            out.add(String.format(STACK_FRAMES_OMITTED_MARKER, run.size()));
        } else {
            out.addAll(run);
        }
        run.clear();
    }

    private static void appendHeading(List<String> headings, Pattern pattern, String line, int lineNumber)
    {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.matches()) {
            return;
        }
        int level = matcher.group(1).length();
        String titleText = cleanHeadingTitle(matcher.group(2).trim());
        headings.add(" ".repeat(2 * (level - 1)) + "L" + lineNumber + ": " + titleText);
    }

    /**
     * Cleans noisy inline markup from an outline heading title using pure string operations (no parsing or
     * rendering). Strips XWiki group/style markers and collapses XWiki links to their label (or target, when
     * unlabelled). Falls back to the trimmed raw title if cleaning leaves the title empty, so an entry is never
     * blank.
     *
     * @param raw the extracted, already-trimmed heading title text
     * @return the cleaned title
     */
    private static String cleanHeadingTitle(String raw)
    {
        String stripped = XWIKI_STYLE_MARKER.matcher(raw).replaceAll("");

        Matcher linkMatcher = XWIKI_LINK.matcher(stripped);
        StringBuilder sb = new StringBuilder();
        while (linkMatcher.find()) {
            String inner = linkMatcher.group(1);
            String label = inner.contains(LINK_LABEL_SEPARATOR)
                ? inner.substring(0, inner.indexOf(LINK_LABEL_SEPARATOR)) : inner;
            if (label.contains(LINK_PARAM_SEPARATOR)) {
                label = label.substring(0, label.indexOf(LINK_PARAM_SEPARATOR));
            }
            linkMatcher.appendReplacement(sb, Matcher.quoteReplacement(label.trim()));
        }
        linkMatcher.appendTail(sb);

        String cleaned = MULTIPLE_SPACES.matcher(sb.toString()).replaceAll(" ").trim();
        return cleaned.isEmpty() ? raw.trim() : cleaned;
    }

    private static Pattern headingPatternFor(String syntaxId)
    {
        if (syntaxId.startsWith(XWIKI_SYNTAX_PREFIX)) {
            return XWIKI_HEADING;
        }
        if (syntaxId.startsWith(MARKDOWN_SYNTAX_PREFIX)) {
            return MARKDOWN_HEADING;
        }
        return null;
    }
}
