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
package org.xwiki.contrib.llm.mcp.internal.server;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.internal.access.MCPSpaceFilter;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Listens for saves and deletions of any wiki's MCP server configuration document
 * ({@code AI.MCP.Code.MCPServerConfig})
 * and {@link XWikiMCPServerManager#invalidate(String) invalidates} that wiki's MCP server, so its name,
 * description and instructions are re-read on the next connection without restarting XWiki. With the same
 * granularity it {@link MCPSpaceFilter#invalidate(String) invalidates} the cached parsed space-filter
 * configuration, so the saved whitelist/blacklist takes effect on the next document check.
 *
 * <p>The MAIN wiki's config document carries the farm-level cross-wiki reach grant, which affects the
 * reach-gated tool set of every wiki's endpoint, not only the main wiki's. A save to the main wiki's config
 * therefore invalidates all cached servers; a save to any other wiki's config invalidates only that wiki.</p>
 *
 * <p>It also leaves an audit trail of the saves that change what the endpoint applies: a save that leaves the
 * page untrusted (see {@link MCPServerConfiguration#isConfigurationTrusted(String)}) and the deletion of the
 * page by a user without admin right on the wiki are logged at WARN level with the acting user, since both
 * silently change the wiki's endpoint (off, respectively back to the defaults); an administrator deleting or
 * replacing the page is a legitimate action and is not logged. A save that leaves objects of a class the
 * extension does not put on the page is logged as well: the extension owns the page and ships exactly one
 * configuration object and one administration-section object on it. These lines are written only on the node
 * where the save or deletion happened, not on the nodes replaying the event.</p>
 *
 * <p>Events of a translation of the page are ignored: translations carry no configuration, and the save or
 * deletion of the default-locale page that goes with them does the invalidation.</p>
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
    /**
     * The component hint and listener name, used as a stable unique identifier. The value is historical (the
     * class's original fully qualified name) and deliberately decoupled from the class's current package: it is a
     * registration identifier, not a class reference.
     */
    public static final String NAME =
        "org.xwiki.contrib.llm.mcp.internal.MCPConfigChangeEventListener";

    @Inject
    private XWikiMCPServerManager mcpServerManager;

    @Inject
    private MCPSpaceFilter spaceFilter;

    @Inject
    private MCPServerConfiguration mcpConfig;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private MCPConfigPageAuthorship authorship;

    @Inject
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @Inject
    private Logger logger;

    /**
     * Registers interest in document-created, document-updated and document-deleted events.
     * (Created covers the first install of the mcp-ui module; deleted covers an admin removing the
     * configuration document, which must drop the cached server and space-filter state so the defaults
     * take effect instead of the last saved configuration.)
     */
    public MCPConfigChangeEventListener()
    {
        super(NAME, new DocumentCreatedEvent(), new DocumentUpdatedEvent(), new DocumentDeletedEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (!(source instanceof DocumentModelBridge) || isTranslation(source)) {
            return;
        }
        DocumentReference ref = ((DocumentModelBridge) source).getDocumentReference();
        String wikiId = ref.getWikiReference().getName();
        DocumentReference configRef = new DocumentReference(wikiId,
            MCPServerConfiguration.CONFIG_SPACES, MCPServerConfiguration.CONFIG_DOC_NAME);
        if (!configRef.equals(ref)) {
            return;
        }
        // The audit trail is written once, on the node where the save or deletion happened: the event is replayed
        // on every other cluster node, which has no acting user for it. The invalidation below stays global.
        if (!this.remoteObservationManagerContext.isRemoteState()) {
            if (event instanceof DocumentDeletedEvent) {
                warnIfDeletedByNonAdmin(source, wikiId);
            } else {
                warnIfUntrusted(wikiId);
                warnIfForeignObjects(source, wikiId);
            }
        }
        if (wikiId.equals(this.wikiDescriptorManager.getMainWikiId())) {
            this.logger.debug("Main MCP configuration changed; invalidating all wiki servers, farm-level reach "
                + "may have changed");
            this.mcpServerManager.invalidateAll();
            this.spaceFilter.invalidateAll();
        } else {
            this.logger.debug("MCP server configuration changed for wiki [{}], invalidating its server", wikiId);
            this.mcpServerManager.invalidate(wikiId);
            this.spaceFilter.invalidate(wikiId);
        }
    }

    /**
     * Logs a save that leaves the configuration page untrusted: the endpoint of the wiki is off until an
     * administrator resets the configuration, which must not pass unnoticed in the server log.
     *
     * @param wikiId the wiki whose configuration page was saved
     */
    private void warnIfUntrusted(String wikiId)
    {
        if (this.mcpConfig.isConfigurationTrusted(wikiId)) {
            return;
        }
        DocumentReference author = this.mcpConfig.getConfigurationAuthor(wikiId);
        if (author == null) {
            this.logger.warn("The MCP configuration page of wiki [{}] was saved without a recorded author; the "
                + "endpoint of the wiki is disabled until an administrator resets the configuration", wikiId);
        } else {
            this.logger.warn("The MCP configuration page of wiki [{}] was saved by [{}], who has no admin right on "
                + "the wiki; the endpoint of the wiki is disabled until an administrator resets the configuration",
                wikiId, author);
        }
    }

    /**
     * Logs a save that leaves objects of a class the extension does not put on the configuration page. This is
     * an audit line, not a trust decision: the page is trusted or not by its author alone.
     *
     * @param source the saved document
     * @param wikiId the wiki whose configuration page was saved
     */
    private void warnIfForeignObjects(Object source, String wikiId)
    {
        if (!(source instanceof XWikiDocument document)) {
            return;
        }
        Set<DocumentReference> foreignClasses = MCPServerConfiguration.objectClasses(document);
        foreignClasses.remove(new DocumentReference(wikiId, MCPServerConfiguration.CONFIG_SPACES,
            MCPServerConfiguration.CONFIG_CLASS_NAME));
        foreignClasses.remove(new DocumentReference(wikiId, List.of("XWiki"), "ConfigurableClass"));
        if (!foreignClasses.isEmpty()) {
            this.logger.warn("The MCP configuration page of wiki [{}] carries objects of classes {} that the "
                + "extension does not put on it", wikiId, foreignClasses);
        }
    }

    /**
     * Tells whether the event concerns a translation of a page rather than its default-locale document. The
     * source of a deletion event is a blank document built from the locale-less reference of the deleted one, so
     * its locale is always the root locale; the deleted document is its original document, which carries the
     * actual locale.
     *
     * @param source the event source
     * @return whether the source, or the original document it stands for, is a translation
     */
    private static boolean isTranslation(Object source)
    {
        if (!(source instanceof XWikiDocument document)) {
            return false;
        }
        XWikiDocument original = document.getOriginalDocument();
        Locale locale = Locale.ROOT.equals(document.getLocale()) && original != null
            ? original.getLocale() : document.getLocale();
        return !Locale.ROOT.equals(locale);
    }

    /**
     * Logs the deletion of the configuration page by a user without admin right on the wiki, after which the
     * wiki's endpoint runs on the defaults. The platform stamps the deleting user as the author of the (blank)
     * source document of a deletion event, so the deleter is read like the author of a saved page and judged by
     * the same rule; a deletion by an administrator is not logged.
     *
     * @param source the source document of the deletion event
     * @param wikiId the wiki whose configuration page was deleted
     */
    private void warnIfDeletedByNonAdmin(Object source, String wikiId)
    {
        DocumentReference deleter = source instanceof XWikiDocument document ? this.authorship.authorOf(document)
            : null;
        if (this.authorship.isTrusted(deleter, wikiId)) {
            return;
        }
        if (deleter == null) {
            this.logger.warn("The MCP configuration page of wiki [{}] was deleted by [an unknown user]; the wiki "
                + "now uses the default MCP configuration", wikiId);
        } else {
            this.logger.warn("The MCP configuration page of wiki [{}] was deleted by [{}], who has no admin right on "
                + "the wiki; the wiki now uses the default MCP configuration", wikiId, deleter);
        }
    }
}
