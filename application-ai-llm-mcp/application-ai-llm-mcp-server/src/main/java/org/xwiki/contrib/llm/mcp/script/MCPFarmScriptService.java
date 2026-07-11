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
package org.xwiki.contrib.llm.mcp.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.internal.server.MCPServerConfiguration;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.stability.Unstable;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

/**
 * Provides the farm-level MCP administration Script APIs used by the main wiki's MCP admin dashboard:
 * reading each wiki's MCP enabled state and toggling it with a per-wiki admin rights check.
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named("mcp")
@Singleton
@Unstable
public class MCPFarmScriptService implements ScriptService
{
    private static final String DEFAULT_CATEGORY = "General";

    @Inject
    private MCPServerConfiguration mcpConfig;

    @Inject
    private ContextualAuthorizationManager authorization;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private ComponentManager componentManager;

    @Inject
    private Logger logger;

    /**
     * @param wikiId the wiki to check
     * @return whether the MCP endpoint is enabled for the given wiki
     * @since 0.9
     */
    public boolean isEnabled(String wikiId)
    {
        return this.mcpConfig.isEnabled(wikiId);
    }

    /**
     * @param wikiId the wiki to check
     * @return whether the current user has admin rights on the given wiki
     * @since 0.9
     */
    public boolean canAdmin(String wikiId)
    {
        return this.authorization.hasAccess(Right.ADMIN, new WikiReference(wikiId));
    }

    /**
     * @return whether the current user has admin rights on the MAIN wiki, i.e. is a farm administrator. The
     *     cross-wiki reach grant is a farm-level decision, so only a farm administrator may change it.
     * @since 0.9
     */
    public boolean canFarmAdmin()
    {
        return this.authorization.hasAccess(Right.ADMIN,
            new WikiReference(this.wikiDescriptorManager.getMainWikiId()));
    }

    /**
     * Sets the MCP enabled flag on the given wiki, gated by the current user's admin rights on that wiki.
     *
     * @param wikiId the wiki whose flag to set
     * @param enabled whether MCP should be enabled for that wiki
     * @return {@code true} if the flag was written, {@code false} when the user lacks admin rights on the
     *     wiki or the write failed
     * @since 0.9
     */
    public boolean setEnabled(String wikiId, boolean enabled)
    {
        if (!canAdmin(wikiId)) {
            this.logger.debug("Refused MCP enabled-flag change for wiki [{}]: missing admin rights", wikiId);
            return false;
        }
        return this.mcpConfig.setEnabled(wikiId, enabled);
    }

    /**
     * Returns the per-wiki state of every registered MCP tool, sorted by category then id for a stable tree
     * order, with each tool's {@code enabled} flag reflecting the wiki's effective tool policy. Read-only;
     * no rights gate, consistent with {@link #isEnabled(String)}.
     *
     * @param wikiId the wiki whose tool states to read
     * @return the tool states, or an empty list if the tools could not be looked up
     * @since 0.9
     */
    public List<MCPToolState> getToolStates(String wikiId)
    {
        try {
            Map<String, MCPTool> tools = this.componentManager.getInstanceMap(MCPTool.class);
            Set<String> enabled = this.mcpConfig.getEnabledToolIds(wikiId);
            List<MCPToolState> states = new ArrayList<>();
            for (Map.Entry<String, MCPTool> entry : tools.entrySet()) {
                String id = entry.getKey();
                // Reach-gated tools (e.g. list_wikis) are not admin-togglable, so they never render as a
                // checkbox in the admin tree; their presence is governed by the cross-wiki reach grant.
                if (this.mcpConfig.isReachGatedTool(id)) {
                    continue;
                }
                MCPTool tool = entry.getValue();
                String category = StringUtils.defaultIfBlank(tool.getCategory(), DEFAULT_CATEGORY);
                String summary = tool.getSummary() == null ? "" : tool.getSummary();
                states.add(new MCPToolState(id, category, summary, this.mcpConfig.isMandatoryTool(id),
                    tool.isWrite(), enabled.contains(id)));
            }
            states.sort(Comparator.comparing(MCPToolState::getCategory).thenComparing(MCPToolState::getId));
            return states;
        } catch (ComponentLookupException e) {
            this.logger.warn("Could not look up MCP tools for wiki [{}]: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP tool lookup failure for wiki [{}]", wikiId, e);
            return List.of();
        }
    }

    /**
     * Stores the explicit set of enabled MCP tool ids for the given wiki, gated by the current user's admin
     * rights on that wiki. A {@code null} array stores an explicit empty set.
     *
     * @param wikiId the wiki whose tool set to write
     * @param toolIds the tool ids to enable, or {@code null} to store an empty set
     * @return {@code true} if the set was written, {@code false} when the user lacks admin rights on the
     *     wiki or the write failed
     * @since 0.9
     */
    public boolean setEnabledTools(String wikiId, String[] toolIds)
    {
        if (!canAdmin(wikiId)) {
            this.logger.debug("Refused MCP tool-set change for wiki [{}]: missing admin rights", wikiId);
            return false;
        }
        return this.mcpConfig.setEnabledToolIds(wikiId,
            toolIds == null ? List.of() : Arrays.asList(toolIds));
    }

    /**
     * Applies a desired MCP enabled state across a set of managed wikis. Each wiki in {@code managedWikiIds}
     * is set to enabled if and only if it also appears in {@code enabledWikiIds}. A wiki is only written when
     * its current state actually differs from the desired one, and only when the current user has admin rights
     * on that wiki; otherwise it is skipped.
     *
     * @param managedWikiIds the wikis whose state should be reconciled (may be {@code null} or empty)
     * @param enabledWikiIds the subset of those wikis that should end up enabled (may be {@code null} or empty)
     * @return the outcome counts of the apply
     * @since 0.9
     */
    public BulkResult applyEnabled(String[] managedWikiIds, String[] enabledWikiIds)
    {
        Set<String> enabledSet =
            new HashSet<>(enabledWikiIds == null ? List.of() : Arrays.asList(enabledWikiIds));
        int changed = 0;
        int skipped = 0;
        if (managedWikiIds != null) {
            for (String wiki : managedWikiIds) {
                boolean desired = enabledSet.contains(wiki);
                if (!canAdmin(wiki)) {
                    skipped++;
                    continue;
                }
                if (this.mcpConfig.isEnabled(wiki) == desired) {
                    continue;
                }
                if (this.mcpConfig.setEnabled(wiki, desired)) {
                    changed++;
                } else {
                    skipped++;
                }
            }
        }
        return new BulkResult(changed, skipped);
    }

    /**
     * @param wikiId the wiki to check
     * @return whether the given wiki's MCP endpoint may reach across other wikis
     * @since 0.9
     */
    public boolean isCrossWikiReach(String wikiId)
    {
        return this.mcpConfig.isCrossWikiReachAllowed(wikiId);
    }

    /**
     * Sets the MCP cross-wiki reach grant for the given wiki, gated by the current user's admin rights on the
     * MAIN wiki. Cross-wiki reach is a farm-level decision, so a subwiki admin cannot self-grant it.
     *
     * @param wikiId the wiki whose reach grant to set
     * @param allowed whether cross-wiki reach should be allowed for that wiki
     * @return {@code true} if the grant was written, {@code false} when the user is not a farm administrator
     *     or the write failed
     * @since 0.9
     */
    public boolean setCrossWikiReach(String wikiId, boolean allowed)
    {
        if (!canFarmAdmin()) {
            this.logger.debug("Refused MCP cross-wiki reach change: missing farm admin rights");
            return false;
        }
        return this.mcpConfig.setCrossWikiReach(wikiId, allowed);
    }

    /**
     * Applies a desired MCP cross-wiki reach state across a set of managed wikis, gated once by the current
     * user's admin rights on the MAIN wiki: cross-wiki reach is a farm-level decision, so a caller who is not a
     * farm administrator changes nothing. Each wiki in {@code managedWikiIds} is granted reach if and only if
     * it also appears in {@code reachWikiIds}, and is only written when its current state actually differs from
     * the desired one.
     *
     * @param managedWikiIds the wikis whose state should be reconciled (may be {@code null} or empty)
     * @param reachWikiIds the subset of those wikis that should end up with reach (may be {@code null} or empty)
     * @return the outcome counts of the apply; when the reach defaults cannot be initialized nothing is written
     *     and every managed wiki is counted as skipped
     * @since 0.9
     */
    public BulkResult applyReach(String[] managedWikiIds, String[] reachWikiIds)
    {
        if (!canFarmAdmin()) {
            this.logger.debug("Refused MCP cross-wiki reach apply: missing farm admin rights");
            return new BulkResult(0, managedWikiIds == null ? 0 : managedWikiIds.length);
        }
        // First save promotes the reach list from the "main wiki only" default to an authoritative list, so the
        // reconciliation below can then turn the main wiki's own reach off if the admin unchecked it. Until that
        // promotion succeeds the list is not consulted, so writing it would report success without effect.
        if (!this.mcpConfig.initializeReachDefaults()) {
            this.logger.debug("Refused MCP cross-wiki reach apply: the reach defaults could not be initialized");
            return new BulkResult(0, managedWikiIds == null ? 0 : managedWikiIds.length);
        }
        Set<String> reachSet =
            new HashSet<>(reachWikiIds == null ? List.of() : Arrays.asList(reachWikiIds));
        int changed = 0;
        int skipped = 0;
        if (managedWikiIds != null) {
            for (String wiki : managedWikiIds) {
                boolean desired = reachSet.contains(wiki);
                if (this.mcpConfig.isCrossWikiReachAllowed(wiki) == desired) {
                    continue;
                }
                if (this.mcpConfig.setCrossWikiReach(wiki, desired)) {
                    changed++;
                } else {
                    skipped++;
                }
            }
        }
        return new BulkResult(changed, skipped);
    }
}
