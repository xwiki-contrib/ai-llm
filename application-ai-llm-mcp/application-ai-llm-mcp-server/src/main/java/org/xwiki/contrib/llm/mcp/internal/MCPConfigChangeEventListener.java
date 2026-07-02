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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

/**
 * Listens for saves to any wiki's MCP server configuration document ({@code AI.MCP.Code.MCPServerConfig})
 * and {@link XWikiMCPServerManager#invalidate(String) invalidates} that wiki's MCP server, so its name,
 * description and instructions are re-read on the next connection without restarting XWiki.
 *
 * <p>The MAIN wiki's config document carries the farm-level cross-wiki reach grant, which affects the
 * reach-gated tool set of every wiki's endpoint, not only the main wiki's. A save to the main wiki's config
 * therefore invalidates all cached servers; a save to any other wiki's config invalidates only that wiki.</p>
 *
 * <p>This is a global {@link AbstractEventListener} (not a {@code LocalEventListener}) by design: each
 * cluster node keeps its own per-wiki server cache, so the invalidation must fire on every node. A
 * local listener would leave other nodes serving stale configuration.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPConfigChangeEventListener.NAME)
@Singleton
public class MCPConfigChangeEventListener extends AbstractEventListener
{
    /** The component hint and listener name, used as a stable unique identifier. */
    public static final String NAME =
        "org.xwiki.contrib.llm.mcp.internal.MCPConfigChangeEventListener";

    @Inject
    private XWikiMCPServerManager mcpServerManager;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private Logger logger;

    /**
     * Registers interest in both document-created and document-updated events.
     * (Created covers the first install of the mcp-ui module.)
     */
    public MCPConfigChangeEventListener()
    {
        super(NAME, new DocumentCreatedEvent(), new DocumentUpdatedEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (!(source instanceof DocumentModelBridge)) {
            return;
        }
        DocumentReference ref = ((DocumentModelBridge) source).getDocumentReference();
        String wikiId = ref.getWikiReference().getName();
        DocumentReference configRef = new DocumentReference(wikiId,
            MCPServerConfiguration.CONFIG_SPACES, MCPServerConfiguration.CONFIG_DOC_NAME);
        if (!configRef.equals(ref)) {
            return;
        }
        if (wikiId.equals(this.wikiDescriptorManager.getMainWikiId())) {
            this.logger.debug("Main MCP configuration changed; invalidating all wiki servers, farm-level reach "
                + "may have changed");
            this.mcpServerManager.invalidateAll();
        } else {
            this.logger.debug("MCP server configuration changed for wiki [{}], invalidating its server", wikiId);
            this.mcpServerManager.invalidate(wikiId);
        }
    }
}
