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
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.diff.DiffException;
import org.xwiki.diff.DiffManager;
import org.xwiki.diff.DiffResult;
import org.xwiki.diff.display.Splitter;
import org.xwiki.diff.display.UnifiedDiffBlock;
import org.xwiki.diff.display.UnifiedDiffDisplayer;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.criteria.impl.RangeFactory;
import com.xpn.xwiki.criteria.impl.RevisionCriteria;
import com.xpn.xwiki.doc.DocumentRevisionProvider;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.rcs.XWikiRCSNodeInfo;

/**
 * History-side plumbing of the {@code get_history} tool: the revision-list reads (criteria, paging and
 * per-revision row rendering), historical revision loads and the unified content diff. A separate
 * component so the tool composes responses while this class owns the oldcore versioning and
 * xwiki-commons diff machinery.
 *
 * <p>Every criteria this class builds includes minor versions: this extension's updates are recorded
 * as minor versions unless {@code major=true} (creations are major) and must be visible in the history
 * it reports.</p>
 *
 * @version $Id$
 * @since 0.10
 */
@Component(roles = MCPHistorySupport.class)
@Singleton
public class MCPHistorySupport
{
    /**
     * Separates the metadata fields of a revision row.
     */
    private static final String FIELD_SEPARATOR = "  ";

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private DocumentRevisionProvider revisionProvider;

    @Inject
    private DiffManager diffManager;

    @Inject
    @Named("line")
    private Splitter<String, String> lineSplitter;

    @Inject
    private UnifiedDiffDisplayer unifiedDiffDisplayer;

    /**
     * Counts the document's revisions, minor versions included.
     *
     * @param doc the loaded document (or translation row) whose history is read
     * @return the total revision count
     * @throws XWikiException when the versioning store fails
     */
    public long countRevisions(XWikiDocument doc) throws XWikiException
    {
        return doc.getRevisionsCount(allRevisionsCriteria(), this.contextProvider.get());
    }

    /**
     * Reads one newest-first page of the document's revision history as formatted rows. The paging
     * mirrors the platform's own history viewer: the range addresses the {@code limit} revisions
     * ending {@code offset} revisions before the newest one, and the store's ascending answer is
     * reversed. The caller ensures {@code offset < total}.
     *
     * @param doc the loaded document (or translation row) whose history is read
     * @param total the total revision count, as returned by {@link #countRevisions(XWikiDocument)}
     * @param offset the number of NEWEST revisions to skip
     * @param limit the maximum number of rows to return
     * @return the formatted rows, newest first
     * @throws XWikiException when the versioning store fails
     */
    public List<String> revisionRows(XWikiDocument doc, long total, int offset, int limit)
        throws XWikiException
    {
        XWikiContext xcontext = this.contextProvider.get();
        RevisionCriteria criteria = allRevisionsCriteria();
        criteria.setRange(RangeFactory.createRange((int) (total - offset), -limit));
        List<String> versions = doc.getRevisions(criteria, xcontext);
        Collections.reverse(versions);
        List<String> rows = new ArrayList<>(versions.size());
        for (String version : versions) {
            rows.add(formatRow(version, doc.getRevisionInfo(version, xcontext)));
        }
        return rows;
    }

    /**
     * Loads one historical revision of a document. The reference may carry a locale: translations have
     * their own histories, and the provider loads the addressed row's revision.
     *
     * @param reference the (possibly locale-carrying) document reference
     * @param version the revision to load, already validated as a {@code major.minor} version string
     * @return the revision, or {@code null} when the document has no such revision
     * @throws XWikiException when loading the revision fails
     */
    public XWikiDocument loadRevision(DocumentReference reference, String version) throws XWikiException
    {
        return this.revisionProvider.getRevision(reference, version);
    }

    /**
     * Computes the unified diff of two content strings, rendered as standard {@code @@ -a,b +c,d @@}
     * hunks with {@code +}/{@code -}/{@code  } lines, one string per hunk. An empty list means the
     * contents are identical. The inputs are diffed whole, so the hunk line numbers are true line
     * numbers of each revision's full content.
     *
     * @param previous the older revision's content
     * @param next the newer revision's content
     * @return the rendered hunks, possibly empty
     * @throws DiffException when the diff computation fails
     */
    public List<String> unifiedDiffBlocks(String previous, String next) throws DiffException
    {
        DiffResult<String> diffResult =
            this.diffManager.diff(this.lineSplitter.split(previous), this.lineSplitter.split(next), null);
        List<UnifiedDiffBlock<String, Character>> blocks = this.unifiedDiffDisplayer.display(diffResult);
        List<String> rendered = new ArrayList<>(blocks.size());
        for (UnifiedDiffBlock<String, Character> block : blocks) {
            rendered.add(block.toString());
        }
        return rendered;
    }

    /**
     * Builds the criteria matching every revision: minor versions MUST be included (the default
     * excludes them), because this extension's updates are minor versions unless {@code major=true}
     * (creations are major).
     *
     * @return the criteria
     */
    private static RevisionCriteria allRevisionsCriteria()
    {
        RevisionCriteria criteria = new RevisionCriteria();
        criteria.setIncludeMinorVersions(true);
        return criteria;
    }

    /**
     * Formats one revision row: version, date, author, the minor marker and the version comment.
     * Author names and comments are wiki-authored values and are neutralized AND length-clamped
     * ({@link MCPTextGuards#fragment(String)}) before entering the line grammar, so a crafted value can
     * neither forge rows nor dominate one. A missing archive node (or a node without a date) degrades
     * to the fields that are available.
     *
     * @param version the revision's version string
     * @param node the revision's archive node, or {@code null} when it could not be resolved
     * @return the formatted row
     */
    private static String formatRow(String version, XWikiRCSNodeInfo node)
    {
        StringBuilder row = new StringBuilder(version);
        if (node == null) {
            return row.toString();
        }
        if (node.isMinorEdit()) {
            row.append(" (minor)");
        }
        if (node.getDate() != null) {
            row.append(FIELD_SEPARATOR).append(MCPAttachmentSupport.formatDate(node.getDate()));
        }
        row.append(FIELD_SEPARATOR).append("by ").append(MCPTextGuards.fragment(node.getAuthor()));
        String comment = MCPTextGuards.fragment(node.getComment());
        if (StringUtils.isNotBlank(comment)) {
            row.append(FIELD_SEPARATOR).append("- ").append(comment);
        }
        return row.toString();
    }
}
