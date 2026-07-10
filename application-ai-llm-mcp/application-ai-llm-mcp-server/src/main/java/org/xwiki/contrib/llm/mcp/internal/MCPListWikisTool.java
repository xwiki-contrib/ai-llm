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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiContext;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that lists the wikis reachable from the current MCP endpoint: the current wiki plus every other wiki in
 * the farm the current user can access. This tool is only registered when the endpoint has cross-wiki reach enabled,
 * so its presence already implies reach is on; the agent-facing text therefore states the reachable set plainly
 * rather than conditionally.
 *
 * <p>This is a default (read-only) tool bundled with the MCP server module. It gives an agent the vocabulary for the
 * {@code query_documents} {@code wiki} parameter and for cross-wiki document references: a listed wiki id can be used
 * as the {@code wiki} argument (or as the wiki prefix of a reference) to reach that wiki from this endpoint.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPListWikisTool.TOOL_ID)
@Singleton
public class MCPListWikisTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "list_wikis";

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String ROW_INDENT = "  ";

    private static final String ROW_SEPARATOR = " — ";

    private static final String THIS_ENDPOINT_SUFFIX = " (this endpoint)";

    /**
     * Header for the reach-enabled listing, naming what the rows are and how to search them together.
     */
    private static final String REACHABLE_HEADER =
        "Reachable wikis - use any id below with the 'wiki' parameter of query_documents or get_tree (or as "
            + "the wiki prefix of a document reference); wiki=\"all\" searches them together:";

    /**
     * First line of the reach-disabled listing.
     */
    private static final String REACH_DISABLED_HEADER =
        "Cross-wiki reach is disabled for this endpoint; only this wiki is available.";

    /**
     * Closing line of the reach-disabled listing, pointing an admin at where to enable reach.
     */
    private static final String REACH_DISABLED_FOOTER =
        "An administrator can enable cross-wiki reach in the main wiki's \"MCP across all wikis\" dashboard.";

    private static final String DESCRIPTION =
        "List the wikis this endpoint can search and act on: the current wiki plus every other wiki in the farm "
            + "you can access. Use a listed wiki id with the 'wiki' parameter of query_documents or get_tree, "
            + "or as the wiki prefix of a document reference.";

    /**
     * The declared parameters: none. The empty parameter set still yields a valid empty-object input schema.
     */
    private static final MCPToolSupport PARAMS = MCPToolSupport.builder().build();

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private ContextualAuthorizationManager authorization;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.inputSchema())
            .description(DESCRIPTION)
            .build();
    }

    @Override
    public String getCategory()
    {
        return "Search & Navigation";
    }

    @Override
    public String getSummary()
    {
        return "List the wikis reachable from this MCP endpoint.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                A listed wiki id is used two ways: as the 'wiki' argument of query_documents or
                get_tree (query_documents also accepts wiki="all" to search several at once), and
                as the prefix of a reference passed to get_document, edit_document or write_document
                to read or write in that wiki. The current wiki is always listed; other wikis appear
                only where you have access.

            EXAMPLES
                List reachable wikis:  (call with no arguments)

            SEE ALSO
                man query_documents  Search documents, optionally across reachable wikis.
                man                  (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        String currentWiki = this.contextProvider.get().getWikiId();
        if (!this.wikiReach.isReachEnabled()) {
            return MCPToolSupport.result(renderDisabled(currentWiki));
        }
        return MCPToolSupport.result(renderReachable(currentWiki));
    }

    private String renderDisabled(String currentWiki)
    {
        return REACH_DISABLED_HEADER + DOUBLE_NEW_LINE
            + row(currentWiki, true) + DOUBLE_NEW_LINE
            + REACH_DISABLED_FOOTER;
    }

    private String renderReachable(String currentWiki)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(REACHABLE_HEADER).append(DOUBLE_NEW_LINE);
        sb.append(row(currentWiki, true));
        for (String id : otherReachableWikis(currentWiki)) {
            sb.append(NEW_LINE).append(row(id, false));
        }
        return sb.toString();
    }

    /**
     * Builds the sorted list of other wikis this endpoint reaches: every wiki other than the current one that is
     * viewable by the current user. Reach is a source-side power grant, so a target wiki is reachable regardless of
     * whether it has its own MCP endpoint enabled. On a wiki-list failure it fails closed to an empty list, so only
     * the current wiki (added by the caller) is shown.
     *
     * @param currentWiki the current wiki id, excluded from the result
     * @return the reachable other wiki ids, sorted by id
     */
    private List<String> otherReachableWikis(String currentWiki)
    {
        List<String> ids;
        try {
            ids = new ArrayList<>(this.wikiDescriptorManager.getAllIds());
        } catch (WikiManagerException e) {
            this.logger.warn("Could not list wikis for list_wikis; showing only this wiki: [{}]",
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Wiki list failure in list_wikis", e);
            return List.of();
        }
        return ids.stream()
            .filter(id -> !id.equals(currentWiki))
            .filter(this::isViewable)
            .sorted()
            .toList();
    }

    /**
     * @param wikiId the wiki to check
     * @return whether the current user has wiki-level VIEW on the given wiki. This evaluates the wiki's own global
     *     rights ({@code XWiki.XWikiPreferences}) rather than any single document's rights, so it is a reliable
     *     "may this user access this wiki" check, independent of a specific home document's name or permissions.
     *     Per-document rights are still enforced on every actual read/write, so listing a wiki the user cannot
     *     ultimately use is harmless, whereas hiding a reachable wiki would not be.
     */
    private boolean isViewable(String wikiId)
    {
        return this.authorization.hasAccess(Right.VIEW, new WikiReference(wikiId));
    }

    /**
     * Formats one wiki row: an indented {@code id — prettyName}, with a trailing marker on the current wiki.
     *
     * @param wikiId the wiki id
     * @param current whether this is the current wiki (this endpoint)
     * @return the formatted row
     */
    private String row(String wikiId, boolean current)
    {
        return ROW_INDENT + wikiId + ROW_SEPARATOR + prettyName(wikiId) + (current ? THIS_ENDPOINT_SUFFIX : "");
    }

    /**
     * Resolves a wiki's pretty name, falling back to the id when the descriptor is unavailable, has no pretty name,
     * or cannot be read.
     *
     * @param wikiId the wiki id
     * @return the pretty name, or the id as a fallback
     */
    private String prettyName(String wikiId)
    {
        try {
            var descriptor = this.wikiDescriptorManager.getById(wikiId);
            if (descriptor != null && StringUtils.isNotBlank(descriptor.getPrettyName())) {
                return descriptor.getPrettyName();
            }
        } catch (WikiManagerException e) {
            this.logger.debug("Could not read the pretty name for wiki [{}]", wikiId, e);
        }
        return wikiId;
    }
}
