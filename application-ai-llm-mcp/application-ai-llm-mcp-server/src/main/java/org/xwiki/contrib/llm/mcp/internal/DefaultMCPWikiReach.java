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

import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiContext;

/**
 * Default {@link MCPWikiReach}: reads the cross-wiki reach flag of the current wiki's endpoint from
 * {@link MCPServerConfiguration} to decide how far a document tool or the document search may reach. Reach is a
 * pure source-side power grant: a reach-enabled endpoint may reach every wiki's content in the farm, regardless of
 * whether the target wiki has its own MCP endpoint enabled. The reach flag fails closed, so an unreadable
 * configuration keeps the endpoint confined to its own wiki.
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Singleton
public class DefaultMCPWikiReach implements MCPWikiReach
{
    /** The {@code wiki} parameter value requesting the whole farm. */
    private static final String WIKI_ALL = "all";

    /** Shared prefix of the wiki-validation error messages. */
    private static final String WIKI_PREFIX = "Wiki \"";

    /** Shared tail of the wiki-validation error messages, pointing an agent at the reachable wiki list. */
    private static final String SEE_LIST_WIKIS = "Use list_wikis to see reachable wikis.";

    @Inject
    private MCPServerConfiguration mcpConfig;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Override
    public boolean isReachEnabled()
    {
        String current = currentWikiId();
        return current != null && this.mcpConfig.isCrossWikiReachAllowed(current);
    }

    @Override
    public boolean canReachWiki(String targetWiki)
    {
        String current = currentWikiId();
        if (current != null && current.equals(targetWiki)) {
            return true;
        }
        return isReachEnabled();
    }

    @Override
    public List<String> resolveSearchWikis(String wikiParam) throws MCPAccessDeniedException
    {
        String current = currentWikiId();
        if (StringUtils.isBlank(wikiParam) || wikiParam.equals(current)) {
            return current == null ? List.of() : List.of(current);
        }
        if (!isReachEnabled()) {
            throw new MCPAccessDeniedException("Cross-wiki search is not enabled for this endpoint. Omit the "
                + "'wiki' parameter to search this wiki (\"" + current + "\").");
        }
        if (WIKI_ALL.equalsIgnoreCase(wikiParam)) {
            return null;
        }
        if (!wikiExists(wikiParam)) {
            throw new MCPAccessDeniedException(WIKI_PREFIX + wikiParam + "\" does not exist. " + SEE_LIST_WIKIS);
        }
        return List.of(wikiParam);
    }

    /**
     * @param wikiParam the requested wiki id
     * @return whether the given wiki id is a real wiki in this farm
     * @throws MCPAccessDeniedException if the wiki descriptor lookup fails, so the caller cannot tell the agent
     *     a wiki that could not be verified is reachable
     */
    private boolean wikiExists(String wikiParam) throws MCPAccessDeniedException
    {
        try {
            return this.wikiDescriptorManager.getById(wikiParam) != null;
        } catch (WikiManagerException e) {
            this.logger.warn("Could not verify wiki [{}] for a cross-wiki search: [{}]", wikiParam,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Wiki descriptor lookup failure for cross-wiki search on [{}]", wikiParam, e);
            throw new MCPAccessDeniedException(WIKI_PREFIX + wikiParam + "\" is not available for cross-wiki "
                + "search. " + SEE_LIST_WIKIS);
        }
    }

    /**
     * @return the current (context) wiki id, or {@code null} when there is no context
     */
    private String currentWikiId()
    {
        XWikiContext context = this.contextProvider.get();
        return context == null ? null : context.getWikiId();
    }
}
