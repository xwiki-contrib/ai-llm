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
import org.xwiki.bridge.event.AbstractDocumentEvent;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

/**
 * Listens for saves to the MCP server configuration document ({@code AI.MCP.Code.MCPServerConfig})
 * and triggers a {@link XWikiMCPServerManager#rebuildServer() rebuild} so the new name / description
 * take effect immediately without restarting XWiki.
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPConfigChangeEventListener.NAME)
@Singleton
public class MCPConfigChangeEventListener extends AbstractLocalEventListener
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
    public void processLocalEvent(Event event, Object source, Object data)
    {
        DocumentReference ref = event instanceof AbstractDocumentEvent
            ? ((AbstractDocumentEvent) event).getDocumentReference()
            : null;
        if (ref == null && source instanceof DocumentModelBridge) {
            ref = ((DocumentModelBridge) source).getDocumentReference();
        }

        if (ref != null && getConfigDocumentReference().equals(ref)) {
            this.logger.debug("MCP server configuration changed, triggering server rebuild");
            this.mcpServerManager.rebuildServer();
        }
    }

    // Not cached as a field: getMainWikiId() may not be available at component initialisation time.
    private DocumentReference getConfigDocumentReference()
    {
        return new DocumentReference(this.wikiDescriptorManager.getMainWikiId(),
            MCPServerConfiguration.CONFIG_SPACES, MCPServerConfiguration.CONFIG_DOC_NAME);
    }
}
