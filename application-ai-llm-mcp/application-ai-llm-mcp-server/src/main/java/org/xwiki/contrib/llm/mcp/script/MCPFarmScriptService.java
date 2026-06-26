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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.internal.MCPServerConfiguration;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.stability.Unstable;

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
    @Inject
    private MCPServerConfiguration mcpConfig;

    @Inject
    private ContextualAuthorizationManager authorization;

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
}
