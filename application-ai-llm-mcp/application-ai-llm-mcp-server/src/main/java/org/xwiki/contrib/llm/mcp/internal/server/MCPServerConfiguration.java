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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.internal.tool.MCPListWikisTool;
import org.xwiki.contrib.llm.mcp.internal.tool.MCPManTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.wiki.descriptor.WikiDescriptor;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.PropertyInterface;

/**
 * Provides MCP server configuration values by reading the admin XObject stored at
 * {@code AI.MCP.Code.MCPServerConfig} on the wiki being queried. Falls back to sensible defaults
 * if the config document or XObject is absent (e.g. before the mcp-ui module is installed).
 * <p>
 * Every read goes through one door, {@link #loadConfiguration(String)}, which trusts a configuration page only
 * when the user who last saved it has admin right on that wiki. An untrusted page turns the wiki's endpoint off
 * and nothing else is read from it, so its values never reach a reader. Every write goes through
 * {@link #prepareForWrite(String, XWikiContext)}, which replaces an untrusted page with the page as the extension
 * ships it before the acting administrator's value is written, and through {@link #save}, which stamps the
 * acting user as author and creator of the saved page: a write never re-trusts anything an administrator has
 * not seen, and the page it produces is trusted.
 *
 * @version $Id$
 * @since 0.9
 */
@Component(roles = MCPServerConfiguration.class)
@Singleton
public class MCPServerConfiguration
{
    /** Space filter mode value disabling the filter: every document is allowed. */
    public static final String SPACE_FILTER_MODE_NONE = "none";

    /** Space filter mode value: only the configured spaces and documents are allowed. */
    public static final String SPACE_FILTER_MODE_WHITELIST = "whitelist";

    /** Space filter mode value: every document except the configured spaces and documents is allowed. */
    public static final String SPACE_FILTER_MODE_BLACKLIST = "blacklist";

    /** Default MCP server name advertised to connecting agents. */
    static final String DEFAULT_SERVER_NAME = "XWiki";

    /** Default MCP server instructions advertised to connecting agents. */
    static final String DEFAULT_SERVER_DESCRIPTION = "XWiki MCP Server";

    static final List<String> CONFIG_SPACES = List.of("AI", "MCP", "Code");

    static final String CONFIG_DOC_NAME = "MCPServerConfig";

    static final String CONFIG_CLASS_NAME = "MCPServerConfigClass";

    static final String FIELD_SERVER_NAME = "serverName";

    static final String FIELD_SERVER_DESCRIPTION = "serverDescription";

    static final String FIELD_ENABLED = "enabled";

    static final String FIELD_ENABLED_TOOLS = "enabledTools";

    static final String FIELD_SPACE_FILTER_MODE = "spaceFilterMode";

    static final String FIELD_SPACE_FILTER_SPACES = "spaceFilterSpaces";

    static final String FIELD_SPACE_FILTER_DOCUMENTS = "spaceFilterDocuments";

    static final String FIELD_ALLOW_RENDERED_CONTENT = "allowRenderedContent";

    static final String FIELD_ALLOW_GUEST = "allowGuest";

    static final String FIELD_REACH_ENABLED_WIKIS = "reachEnabledWikis";

    static final String FIELD_REACH_INITIALIZED = "reachInitialized";

    /**
     * Tool ids that are always part of the effective enabled set, regardless of the stored or default
     * policy. The {@code man} catalog is mandatory: an agent must always be able to discover the tools.
     */
    static final Set<String> MANDATORY_TOOL_IDS = Set.of(MCPManTool.TOOL_ID);

    /**
     * Tool ids that are not admin-togglable but are present in the effective set exactly when the wiki has
     * cross-wiki reach enabled. The {@code list_wikis} tool exposes the cross-wiki vocabulary, so it makes
     * sense only when the endpoint can actually reach other wikis.
     */
    static final Set<String> REACH_GATED_TOOL_IDS = Set.of(MCPListWikisTool.TOOL_ID);

    @Inject
    private ComponentManager componentManager;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private MCPConfigPageAuthorship authorship;

    @Inject
    private MCPConfigPageTemplate template;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    /**
     * The configuration page of a wiki as the readers see it: its object when the page is trusted, and the
     * author the trust decision was made on.
     *
     * @param trusted whether the readers may use the page: it does not exist, or its author has admin right on
     *     the wiki
     * @param object the configuration XObject when the page is trusted and carries one, {@code null} otherwise
     * @param author the user who last saved the page, or {@code null} when the page does not exist or records
     *     no author
     */
    private record Configuration(boolean trusted, BaseObject object, DocumentReference author)
    {
    }

    // Server identity (name/description) is read per wiki from that wiki's own config document, so each
    // wiki advertises its own MCP server name and instructions.

    /**
     * @param wikiId the wiki whose configured server name to read
     * @return the MCP server name to advertise to agents connecting to the given wiki. Falls back to the
     *     wiki's pretty name when no name is configured, and to the default {@code "XWiki"} when the pretty
     *     name is unavailable.
     * @since 0.9
     */
    public String getServerName(String wikiId)
    {
        String configured = getStringProperty(wikiId, FIELD_SERVER_NAME, null);
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }
        try {
            WikiDescriptor descriptor = this.wikiDescriptorManager.getById(wikiId);
            if (descriptor != null && StringUtils.isNotBlank(descriptor.getPrettyName())) {
                return descriptor.getPrettyName();
            }
        } catch (Exception e) {
            this.logger.debug("Could not read the pretty name for wiki [{}]", wikiId, e);
        }
        return DEFAULT_SERVER_NAME;
    }

    /**
     * @param wikiId the wiki whose configured server description to read
     * @return the MCP server instructions to advertise to agents connecting to the given wiki, or the
     *     default if not configured
     * @since 0.9
     */
    public String getServerDescription(String wikiId)
    {
        return getStringProperty(wikiId, FIELD_SERVER_DESCRIPTION, DEFAULT_SERVER_DESCRIPTION);
    }

    /**
     * Tells whether the given id is the canonical id of an existing wiki, i.e. the exact (case-sensitive) id
     * its descriptor carries. The main wiki's database is resolved case-insensitively, so a case variant of
     * its id (e.g. {@code XWIKI}) reaches the same documents under a different document-cache key, where a
     * later change of the configuration document is not seen. Callers use this check to refuse such variants
     * before reading any configuration. Fails closed: a blank id, an unknown wiki and a failed descriptor
     * lookup all resolve to {@code false}.
     *
     * @param wikiId the wiki id to check
     * @return {@code true} only if a wiki descriptor exists for the id and carries exactly that id
     * @since 0.10.1
     */
    public boolean isCanonicalWikiId(String wikiId)
    {
        if (StringUtils.isBlank(wikiId)) {
            return false;
        }
        try {
            WikiDescriptor descriptor = this.wikiDescriptorManager.getById(wikiId);
            return descriptor != null && wikiId.equals(descriptor.getId());
        } catch (WikiManagerException e) {
            this.logger.warn("Could not verify the wiki id [{}]; treating it as not canonical: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Wiki descriptor lookup failure for wiki id [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * @param wikiId the wiki to check
     * @return whether the MCP endpoint is enabled for the given wiki. MCP is enabled by default on every
     *     wiki so an endpoint is available with no admin action: an unset flag and a missing config object
     *     both resolve to true. Only an explicit {@code enabled=0} turns the endpoint off. A configuration
     *     page that is not trusted (see {@link #isConfigurationTrusted(String)}) turns the endpoint off as
     *     well, until an administrator resets it. A failed read still fails closed (returns false), so a
     *     config-read error 404s the endpoint rather than exposing it.
     * @since 0.9
     */
    public boolean isEnabled(String wikiId)
    {
        try {
            // This read must stay uncached: the farm dashboard writes the flag and re-renders the wiki's
            // row in the same request, relying on this returning the just-saved value.
            Configuration configuration = loadConfiguration(wikiId);
            if (!configuration.trusted()) {
                return false;
            }
            BaseObject configObject = configuration.object();
            // Enabled by default: a missing object or unset flag resolves to true. Only an explicit 0 is off.
            if (configObject == null || isUnset(configObject, FIELD_ENABLED)) {
                return true;
            }
            return configObject.getIntValue(FIELD_ENABLED) == 1;
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP enabled flag for wiki [{}]; disabling the endpoint: [{}]",
                wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP enabled flag read failure for wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * Tells whether the configuration page of the given wiki is trusted, i.e. whether its values are applied:
     * a page that does not exist is trusted (the defaults apply), and an existing page is trusted only when the
     * user who last saved it has admin right on that wiki. The values of an untrusted page are never read; the
     * wiki's endpoint is off until an administrator resets the page (see {@link #resetConfiguration(String)}).
     *
     * @param wikiId the wiki whose configuration page to check
     * @return {@code true} when the page does not exist or was last saved by a user with admin right on the
     *     wiki; {@code false} otherwise, and when the page cannot be read
     * @since 0.10.1
     */
    public boolean isConfigurationTrusted(String wikiId)
    {
        try {
            return loadConfiguration(wikiId).trusted();
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP configuration page of wiki [{}]; treating it as not "
                + "trusted: [{}]", wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP configuration page read failure for wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * @param wikiId the wiki whose configuration page to inspect
     * @return the user who last saved the wiki's configuration page, or {@code null} when the page does not
     *     exist, records no author, or cannot be read
     * @since 0.10.1
     */
    public DocumentReference getConfigurationAuthor(String wikiId)
    {
        try {
            return loadConfiguration(wikiId).author();
        } catch (Exception e) {
            this.logger.debug("MCP configuration author read failure for wiki [{}]", wikiId, e);
            return null;
        }
    }

    /**
     * Removes every stored MCP setting of the given wiki, so the defaults apply, and records the acting user
     * as author and creator of the configuration page. This is the way an administrator takes ownership of a
     * configuration page that is not trusted: the untrusted page is replaced by the page the extension ships
     * rather than re-trusted, and the page can then be configured again through the admin form.
     *
     * @param wikiId the wiki whose configuration to reset
     * @return {@code true} if the page was reset, {@code false} if the write failed
     * @since 0.10.1
     */
    public boolean resetConfiguration(String wikiId)
    {
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = prepareForWrite(wikiId, context);
            doc.removeXObjects(classReference(wikiId));
            // The reset page carries a fresh, empty configuration object, so the admin form finds one on its next
            // visit and does not save the page a second time to create it.
            doc.getXObject(classReference(wikiId), true, context);
            save(doc, "Reset MCP configuration", context);
            return true;
        } catch (Exception e) {
            this.logger.warn("Failed to reset the MCP configuration of wiki [{}]: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to reset the MCP configuration of wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * Returns whether {@code get_document}'s rendered mode is allowed for the given wiki. Rendering executes
     * the page's macros with the page author's rights (equivalent to viewing the page), so this toggle lets an
     * admin restrict the agent to the raw wiki source. It defaults to on: an unset field, a missing config
     * object and a failed read all resolve to {@code true}. Rendering is a capability, not a confidentiality
     * boundary - the space filter is the boundary and already fails closed - so this fails open to the default.
     *
     * @param wikiId the wiki to check
     * @return whether rendered content is allowed for the given wiki
     * @since 0.9
     */
    public boolean isRenderedContentAllowed(String wikiId)
    {
        try {
            BaseObject configObject = loadConfiguration(wikiId).object();
            if (configObject == null || isUnset(configObject, FIELD_ALLOW_RENDERED_CONTENT)) {
                return true;
            }
            return configObject.getIntValue(FIELD_ALLOW_RENDERED_CONTENT) == 1;
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP allow-rendered-content flag for wiki [{}]; allowing "
                + "rendered content: [{}]", wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP allow-rendered-content flag read failure for wiki [{}]", wikiId, e);
            return true;
        }
    }

    /**
     * Returns whether unauthenticated (guest) callers may use the MCP endpoint of the given wiki. This is the
     * single gate for unauthenticated callers, with or without an OIDC Provider: with it off the endpoint
     * answers every guest with {@code 401} (carrying the {@code WWW-Authenticate} challenge required by the
     * MCP authorization specification when an OIDC Provider is installed), and with it on the request is
     * forwarded to the transport and served with the guest user's rights. A request that presents credentials
     * which fail to authenticate is refused with {@code 401} regardless of this flag.
     * <p>
     * Allowing guests does not widen what the endpoint returns. Every tool resolves its content through
     * XWiki's own rights (and, for the document tools, the space filter), so a guest sees exactly the pages a
     * guest sees in the browser - typically nothing at all on a private wiki - and authoring (write) tools are
     * neither listed nor callable for guest callers. It does, however, expose the tool list and the wiki's
     * search surface to anyone who can reach the URL, which is why it defaults to off: an unset field, a
     * missing config object and a failed read all resolve to {@code false}, so guests stay refused.
     *
     * @param wikiId the wiki to check
     * @return whether guest callers may use the MCP endpoint of the given wiki
     * @since 0.10.1
     */
    public boolean isGuestAccessAllowed(String wikiId)
    {
        try {
            BaseObject configObject = loadConfiguration(wikiId).object();
            if (configObject == null || isUnset(configObject, FIELD_ALLOW_GUEST)) {
                return false;
            }
            return configObject.getIntValue(FIELD_ALLOW_GUEST) == 1;
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP allow-guest flag for wiki [{}]; refusing guest callers: [{}]",
                wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP allow-guest flag read failure for wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * Sets the MCP enabled flag on the given wiki's configuration document, creating the config XObject
     * if necessary.
     *
     * @param wikiId the wiki whose flag to set
     * @param enabled whether MCP should be enabled for that wiki
     * @return {@code true} if the flag was written, {@code false} if the write failed
     * @since 0.9
     */
    public boolean setEnabled(String wikiId, boolean enabled)
    {
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = prepareForWrite(wikiId, context);
            BaseObject obj = doc.getXObject(classReference(wikiId), true, context);
            obj.set(FIELD_ENABLED, enabled ? 1 : 0, context);
            save(doc, "Updated MCP enabled flag", context);
            return true;
        } catch (Exception e) {
            this.logger.warn("Failed to set the MCP enabled flag for wiki [{}]: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to set the MCP enabled flag for wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * Sets the MCP guest access flag on the given wiki's configuration document, creating the config XObject
     * if necessary.
     *
     * @param wikiId the wiki whose flag to set
     * @param allowed whether guest callers may use the MCP endpoint of that wiki
     * @return {@code true} if the flag was written, {@code false} if the write failed
     * @since 0.10.1
     */
    public boolean setGuestAccessAllowed(String wikiId, boolean allowed)
    {
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = prepareForWrite(wikiId, context);
            BaseObject obj = doc.getXObject(classReference(wikiId), true, context);
            obj.set(FIELD_ALLOW_GUEST, allowed ? 1 : 0, context);
            save(doc, "Updated MCP guest access flag", context);
            return true;
        } catch (Exception e) {
            this.logger.warn("Failed to set the MCP allow-guest flag for wiki [{}]: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to set the MCP allow-guest flag for wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * @param wikiId the wiki to check
     * @return whether the given wiki's MCP endpoint may reach across other wikis. The grant is stored as a
     *     farm-level list on the MAIN wiki's config document and read only from there, so a subwiki admin
     *     cannot self-grant reach by editing their own wiki's config. Until a farm admin first saves the reach
     *     dashboard (which sets {@code reachInitialized}), only the MAIN wiki reaches across the farm by default
     *     and every other wiki fails closed; from that first save on, the list is authoritative and may include
     *     or exclude any wiki, the main wiki included. A main wiki configuration page that is not trusted and a
     *     failed read both fail closed (false) for every wiki.
     * @since 0.9
     */
    public boolean isCrossWikiReachAllowed(String wikiId)
    {
        String mainWiki = this.wikiDescriptorManager.getMainWikiId();
        try {
            // The reach grant is a FARM-level decision: it is read only from the MAIN wiki's config list. A subwiki
            // admin can edit their own wiki's config document but not the main wiki's, so reach cannot be self-granted.
            Configuration configuration = loadConfiguration(mainWiki);
            // A grant from an untrusted list is not honoured for any wiki, the main wiki included: the "main wiki
            // only" default below would otherwise keep serving reach from a page nobody with admin right saved.
            if (!configuration.trusted()) {
                return false;
            }
            BaseObject configObject = configuration.object();
            // Default posture until the reach dashboard is first saved: only the MAIN wiki reaches across the
            // farm. The reachInitialized flag is read via getIntValue (0 for both an absent and an unset field),
            // so the default cannot be defeated by a materialised-but-empty list. Once set, the list is
            // authoritative and may itself exclude the main wiki.
            if (configObject == null || configObject.getIntValue(FIELD_REACH_INITIALIZED) != 1) {
                return wikiId.equals(mainWiki);
            }
            @SuppressWarnings("unchecked")
            List<String> reachWikis = configObject.getListValue(FIELD_REACH_ENABLED_WIKIS);
            return reachWikis.contains(wikiId);
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP cross-wiki reach list for wiki [{}]; disabling reach: [{}]",
                wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP cross-wiki reach list read failure for wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * Adds or removes the given wiki id from the farm-level cross-wiki reach list stored on the MAIN wiki's
     * configuration document, creating the config XObject if necessary and saving only when the list actually
     * changes. Because the grant lives on the main wiki, this is effective only for a caller allowed to write
     * the main wiki's config; a subwiki admin cannot self-grant reach.
     *
     * @param wikiId the wiki whose reach grant to set
     * @param allowed whether cross-wiki reach should be allowed for that wiki
     * @return {@code true} if the list was updated (or already in the desired state), {@code false} if the
     *     write failed
     * @since 0.9
     */
    public boolean setCrossWikiReach(String wikiId, boolean allowed)
    {
        String mainWiki = this.wikiDescriptorManager.getMainWikiId();
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = prepareForWrite(mainWiki, context);
            BaseObject obj = doc.getXObject(classReference(mainWiki), true, context);
            @SuppressWarnings("unchecked")
            List<String> current = new ArrayList<>(obj.getListValue(FIELD_REACH_ENABLED_WIKIS));
            boolean changed;
            if (allowed) {
                changed = !current.contains(wikiId);
                if (changed) {
                    current.add(wikiId);
                }
            } else {
                changed = current.remove(wikiId);
            }
            if (changed) {
                obj.set(FIELD_REACH_ENABLED_WIKIS, current, context);
                save(doc, "Updated MCP cross-wiki reach", context);
            }
            return true;
        } catch (Exception e) {
            this.logger.warn("Failed to set the MCP cross-wiki reach for wiki [{}]: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to set the MCP cross-wiki reach for wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * Materializes the default cross-wiki reach state on the MAIN wiki's config document and marks the reach
     * list as authoritative, so {@link #isCrossWikiReachAllowed(String)} stops applying the "main wiki only"
     * default. Idempotent: it writes only the first time (when {@code reachInitialized} is not yet set), seeding
     * the list with the main wiki so the default posture is preserved as an explicit, admin-editable value. The
     * reach dashboard calls this once at the start of an apply so that, from the first save on, the admin's
     * explicit choices - including turning the main wiki's own reach off - fully govern reach.
     *
     * @return {@code true} if the list is initialized (whether just written or already so), {@code false} if the
     *     write failed
     * @since 0.9
     */
    public boolean initializeReachDefaults()
    {
        String mainWiki = this.wikiDescriptorManager.getMainWikiId();
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = prepareForWrite(mainWiki, context);
            BaseObject obj = doc.getXObject(classReference(mainWiki), true, context);
            if (obj.getIntValue(FIELD_REACH_INITIALIZED) == 1) {
                return true;
            }
            obj.set(FIELD_REACH_ENABLED_WIKIS, List.of(mainWiki), context);
            obj.set(FIELD_REACH_INITIALIZED, 1, context);
            save(doc, "Initialized MCP cross-wiki reach defaults", context);
            return true;
        } catch (Exception e) {
            this.logger.warn("Failed to initialize the MCP cross-wiki reach defaults: [{}]",
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to initialize the MCP cross-wiki reach defaults", e);
            return false;
        }
    }

    /**
     * Returns the effective set of tool ids the given wiki's MCP endpoint exposes. The mandatory tools
     * (currently the {@code man} catalog) are always present, even when the stored list omits them. Beyond
     * those, the effective set is the stored list when it is non-empty (an explicit admin override); an empty
     * or never-set list resolves to the default policy (read tools on, write tools off). Disabling every tool
     * is therefore not a representable state: it falls back to the defaults, so disable the whole endpoint via
     * the {@code enabled} flag instead. A failed read also falls back to the default policy so a read glitch
     * keeps read tools available with write tools off, never silently enabling writes; the mandatory tools
     * remain in the set in every case. Reach-gated tools (currently {@code list_wikis}) are outside the stored
     * or default tool policy entirely: they are added if and only if this wiki has cross-wiki reach enabled.
     *
     * @param wikiId the wiki whose enabled tool ids to resolve
     * @return the effective set of enabled tool ids
     * @since 0.9
     */
    public Set<String> getEnabledToolIds(String wikiId)
    {
        try {
            // This read must stay uncached so a just-saved tool set is reflected on the next request.
            BaseObject configObject = loadConfiguration(wikiId).object();
            // getListValue returns an empty List when the field is absent, never null.
            @SuppressWarnings("unchecked")
            List<String> stored = configObject == null ? List.of() : configObject.getListValue(FIELD_ENABLED_TOOLS);
            Set<String> base = stored.isEmpty() ? defaultEnabledToolIds() : new HashSet<>(stored);
            return applyReachGate(wikiId, withMandatory(base));
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP enabled tools for wiki [{}]; applying the default "
                + "policy: [{}]", wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP enabled tools read failure for wiki [{}]", wikiId, e);
            return applyReachGate(wikiId, withMandatory(defaultEnabledToolIds()));
        }
    }

    /**
     * @param toolId a tool id
     * @return whether the tool is reach-gated: not admin-togglable, present in the effective set only when
     *     cross-wiki reach is enabled for the wiki (e.g. the {@code list_wikis} tool)
     * @since 0.9
     */
    public boolean isReachGatedTool(String toolId)
    {
        return REACH_GATED_TOOL_IDS.contains(toolId);
    }

    /**
     * Applies the cross-wiki reach gate to an otherwise-resolved effective tool set: the reach-gated tools are
     * present exactly when the wiki has cross-wiki reach enabled, independently of the stored or default tool
     * policy.
     *
     * @param wikiId the wiki whose reach grant governs the reach-gated tools
     * @param effective the effective tool set before the reach gate
     * @return a new set with the reach-gated tools added iff the wiki has cross-wiki reach enabled
     */
    private Set<String> applyReachGate(String wikiId, Set<String> effective)
    {
        Set<String> result = new HashSet<>(effective);
        // Reach-gated tools (e.g. list_wikis) are never controlled by the stored/default tool policy: they are
        // present exactly when this wiki has cross-wiki reach enabled.
        result.removeAll(REACH_GATED_TOOL_IDS);
        if (isCrossWikiReachAllowed(wikiId)) {
            result.addAll(REACH_GATED_TOOL_IDS);
        }
        return result;
    }

    /**
     * @param toolId a tool id
     * @return whether the tool is always enabled regardless of configuration (e.g. the {@code man} catalog)
     * @since 0.9
     */
    public boolean isMandatoryTool(String toolId)
    {
        return MANDATORY_TOOL_IDS.contains(toolId);
    }

    /**
     * Computes the default enabled tool set: every registered tool that does not perform writes (see
     * {@link MCPTool#isWrite()}). On a tool lookup failure it returns an empty set, failing to "no tools",
     * which is safe. The mandatory tools are layered on by {@link #withMandatory(Set)} at the call sites.
     *
     * @return the default set of enabled tool ids
     */
    private Set<String> defaultEnabledToolIds()
    {
        try {
            Map<String, MCPTool> tools = this.componentManager.getInstanceMap(MCPTool.class);
            Set<String> result = new HashSet<>();
            for (Map.Entry<String, MCPTool> entry : tools.entrySet()) {
                if (!entry.getValue().isWrite()) {
                    result.add(entry.getKey());
                }
            }
            return result;
        } catch (ComponentLookupException e) {
            this.logger.warn("Could not look up MCP tools to compute the default tool policy; enabling no "
                + "tools: [{}]", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP tool lookup failure while computing the default tool policy", e);
            return new HashSet<>();
        }
    }

    /**
     * Returns a new set containing the given tool ids plus the mandatory tool ids, so the {@code man} catalog
     * (and any other mandatory tool) is always part of the effective enabled set.
     *
     * @param toolIds the base set of tool ids
     * @return a new set that also contains the mandatory tool ids
     */
    private Set<String> withMandatory(Set<String> toolIds)
    {
        Set<String> result = new HashSet<>(toolIds);
        result.addAll(MANDATORY_TOOL_IDS);
        return result;
    }

    /**
     * Stores the explicit set of enabled tool ids on the given wiki's configuration document, creating the
     * config XObject if necessary. A {@code null} list is stored as the empty list, which the readers resolve to
     * the default policy (see {@link #getEnabledToolIds(String)}).
     *
     * @param wikiId the wiki whose tool set to write
     * @param toolIds the tool ids to enable, or {@code null} to store the empty list
     * @return {@code true} if the set was written, {@code false} if the write failed (including when the
     *     deployed configuration class lacks the tool-list field, in which case nothing is saved)
     * @since 0.9
     */
    public boolean setEnabledToolIds(String wikiId, List<String> toolIds)
    {
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = prepareForWrite(wikiId, context);
            BaseObject obj = doc.getXObject(classReference(wikiId), true, context);
            obj.set(FIELD_ENABLED_TOOLS, toolIds == null ? List.of() : toolIds, context);
            if (obj.getField(FIELD_ENABLED_TOOLS) == null) {
                this.logger.warn("Could not store the MCP tool list for wiki [{}]: the [{}] field is absent "
                    + "from the deployed config class", wikiId, FIELD_ENABLED_TOOLS);
                return false;
            }
            save(doc, "Updated MCP tool configuration", context);
            return true;
        } catch (Exception e) {
            this.logger.warn("Failed to set the MCP enabled tools for wiki [{}]: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to set the MCP enabled tools for wiki [{}]", wikiId, e);
            return false;
        }
    }

    /**
     * Returns the space filter mode configured for the given wiki: one of {@code none}, {@code whitelist}
     * or {@code blacklist}. This filter narrows the set of documents the wiki's MCP tools may reach on top
     * of the regular rights checks; it never grants access. A blank or absent value (the read succeeded but
     * the field is empty) resolves to {@code none}, imposing no restriction. A genuine read failure is
     * propagated as a {@link RuntimeException} so the caller can fail closed: a configuration glitch must not
     * silently defeat a blacklist.
     *
     * @param wikiId the wiki whose space filter mode to read
     * @return the configured mode, or {@code none} when unset or blank
     * @throws RuntimeException if the configuration document cannot be read
     * @since 0.9
     */
    public String getSpaceFilterMode(String wikiId)
    {
        BaseObject configObject = loadSpaceFilterObject(wikiId);
        if (configObject == null) {
            return SPACE_FILTER_MODE_NONE;
        }
        String mode = configObject.getStringValue(FIELD_SPACE_FILTER_MODE);
        return StringUtils.isBlank(mode) ? SPACE_FILTER_MODE_NONE : mode.trim();
    }

    /**
     * Returns the local space references configured for the given wiki's space filter. Each reference covers
     * that space and everything nested under it. An absent field (the read succeeded) yields an empty list; a
     * genuine read failure is propagated so the caller can fail closed.
     *
     * @param wikiId the wiki whose filtered spaces to read
     * @return the configured local space references, never {@code null}
     * @throws RuntimeException if the configuration document cannot be read
     * @since 0.9
     */
    public List<String> getSpaceFilterSpaces(String wikiId)
    {
        return getSpaceFilterList(wikiId, FIELD_SPACE_FILTER_SPACES);
    }

    /**
     * Returns the local document references configured for the given wiki's space filter, filtered
     * individually (not as subtrees). An absent field (the read succeeded) yields an empty list; a genuine
     * read failure is propagated so the caller can fail closed.
     *
     * @param wikiId the wiki whose filtered documents to read
     * @return the configured local document references, never {@code null}
     * @throws RuntimeException if the configuration document cannot be read
     * @since 0.9
     */
    public List<String> getSpaceFilterDocuments(String wikiId)
    {
        return getSpaceFilterList(wikiId, FIELD_SPACE_FILTER_DOCUMENTS);
    }

    private List<String> getSpaceFilterList(String wikiId, String fieldName)
    {
        BaseObject configObject = loadSpaceFilterObject(wikiId);
        if (configObject == null) {
            return List.of();
        }
        // getListValue returns an empty List when the field is absent, never null.
        @SuppressWarnings("unchecked")
        List<String> stored = configObject.getListValue(fieldName);
        return new ArrayList<>(stored);
    }

    /**
     * Loads the configuration XObject for a space-filter read, translating a read failure into an unchecked
     * exception so the three space-filter getters can propagate it and the space filter can fail closed.
     *
     * @param wikiId the wiki whose configuration object to load
     * @return the configuration XObject, or {@code null} when the document has no such object
     * @throws RuntimeException if the configuration document cannot be read
     */
    private BaseObject loadSpaceFilterObject(String wikiId)
    {
        try {
            return loadConfiguration(wikiId).object();
        } catch (XWikiException e) {
            throw new IllegalStateException(
                "Could not read the MCP space filter configuration for wiki [" + wikiId + "]", e);
        }
    }

    /**
     * Loads the configuration page of a wiki. A page that does not exist is trusted and has no object (the
     * defaults apply). An existing page is trusted only when the user who last saved it (its effective metadata
     * author) has admin right on that wiki; otherwise the readers treat the wiki as unconfigured except that
     * its endpoint is off.
     *
     * @param wikiId the wiki whose configuration page to load
     * @return the page as the readers see it
     * @throws XWikiException if the configuration document cannot be read
     */
    private Configuration loadConfiguration(String wikiId) throws XWikiException
    {
        XWikiContext context = this.contextProvider.get();
        XWikiDocument configDoc = context.getWiki().getDocument(configReference(wikiId), context);
        if (configDoc.isNew()) {
            return new Configuration(true, null, null);
        }
        DocumentReference author = this.authorship.authorOf(configDoc);
        boolean trusted = this.authorship.isTrusted(author, wikiId);
        return new Configuration(trusted, trusted ? configDoc.getXObject(classReference(wikiId)) : null, author);
    }

    /**
     * Loads the document a write modifies and saves. For a page that does not exist or is trusted this is a
     * private copy of the stored page: the cached page is never modified in place. An untrusted page is not
     * copied at all: the write starts from the page as the extension ships it (see
     * {@link MCPConfigPageTemplate#shippedPage(String, XWikiContext)}), or from an empty hidden page when the
     * extension package cannot be read, and the acting administrator takes ownership of it. The untrusted page is
     * deleted first, with its translations (to the recycle bin when the recycle bin is enabled), so that the save
     * of the fresh document creates the page and the platform's own deletion listeners take care of its
     * attachments, links and index entries. Saving the fresh document over the stored page instead would make the
     * platform delete the stored page itself and then fire a document-updated event whose original document
     * still lists the removed attachments, which its attachment event generator fails to read. Nothing of the
     * untrusted page is kept, not even its title or content, because the platform runs UI extensions and the
     * administration section's code under the page's metadata author and skin-extension Velocity under its
     * content author: after the save both are the administrator, so anything left on the page would run with
     * their rights.
     *
     * @param wikiId the wiki whose configuration page to prepare
     * @param context the current context
     * @return the document to modify and save
     * @throws XWikiException if the configuration document cannot be read, or an untrusted page cannot be deleted
     */
    private XWikiDocument prepareForWrite(String wikiId, XWikiContext context) throws XWikiException
    {
        XWikiDocument stored = context.getWiki().getDocument(configReference(wikiId), context);
        if (stored.isNew() || this.authorship.isTrusted(this.authorship.authorOf(stored), wikiId)) {
            return stored.clone();
        }
        context.getWiki().deleteAllDocuments(stored, context);
        XWikiDocument fresh = this.template.shippedPage(wikiId, context);
        if (fresh == null) {
            this.logger.warn("The MCP configuration page of wiki [{}] could not be restored from the extension "
                + "package; it is replaced by an empty page", wikiId);
            fresh = new XWikiDocument(configReference(wikiId));
            fresh.setHidden(true);
        }
        this.authorship.takeOwnership(fresh, context);
        return fresh;
    }

    /**
     * Saves a configuration page prepared by {@link #prepareForWrite(String, XWikiContext)}, stamping the acting
     * user as its author and creator first so that the saved page is trusted. A save that creates or replaces the
     * page is verified afterwards (see {@link #verifyReplacement(XWikiDocument, XWikiContext)}).
     *
     * @param doc the document to save
     * @param comment the version comment
     * @param context the current context, carrying the acting user
     * @throws XWikiException if the save fails, or the saved page is found to carry content that was not saved
     */
    private void save(XWikiDocument doc, String comment, XWikiContext context) throws XWikiException
    {
        this.authorship.stamp(doc, context);
        boolean created = doc.isNew();
        context.getWiki().saveDocument(doc, comment, true, context);
        if (created) {
            verifyReplacement(doc, context);
        }
    }

    /**
     * Checks that the page just created or replaced carries nothing but what was saved. A save by the untrusted
     * author landing between the deletion of their page and the replacement save re-creates the page, and the
     * platform's save of a new document over an existing one removes only the objects and attachments of the
     * original it read earlier: rows of that save can stay attached to the page name, now under the
     * administrator's authorship. The page is therefore read
     * back from the store (the save invalidated the cache) and compared with the saved instance: more objects of
     * any class than the saved instance carries (a second configuration object would carry the other author's
     * values under the administrator's trust), an attachment or a translation means the page was changed
     * concurrently. The page is then deleted (to the recycle bin), which leaves the wiki on the defaults, the
     * documented state for a missing page, with nothing of the other author live under the administrator, and
     * the write is reported as failed.
     *
     * @param saved the instance that was just saved
     * @param context the current context
     * @throws XWikiException if the page cannot be read back or deleted, or carries content that was not saved
     */
    private void verifyReplacement(XWikiDocument saved, XWikiContext context) throws XWikiException
    {
        XWikiDocument reloaded = context.getWiki().getDocument(saved.getDocumentReference(), context);
        Set<DocumentReference> excessClasses = excessObjectClasses(saved, reloaded);
        int attachments = reloaded.getAttachmentList().size();
        int translations = reloaded.getTranslationLocales(context).size();
        if (excessClasses.isEmpty() && attachments == 0 && translations == 0) {
            return;
        }
        String wikiId = saved.getDocumentReference().getWikiReference().getName();
        this.logger.warn("The MCP configuration page of wiki [{}] was changed concurrently with its replacement and "
            + "carries content that was not saved (more objects of classes {} than saved, [{}] attachment(s), [{}] "
            + "translation(s)); the page is deleted and the wiki uses the default MCP configuration", wikiId,
            excessClasses, attachments, translations);
        context.getWiki().deleteAllDocuments(reloaded, context);
        throw new XWikiException(XWikiException.MODULE_XWIKI_DOC, XWikiException.ERROR_XWIKI_UNKNOWN,
            "The MCP configuration page of wiki [" + wikiId + "] was changed concurrently with its replacement");
    }

    /**
     * @param saved the instance that was saved
     * @param reloaded the page as read back from the store
     * @return the classes the reloaded page carries more objects of than the saved instance, including the
     *     classes the saved instance carries no object of
     */
    private static Set<DocumentReference> excessObjectClasses(XWikiDocument saved, XWikiDocument reloaded)
    {
        Map<DocumentReference, Integer> savedCounts = objectCounts(saved);
        Set<DocumentReference> excess = new HashSet<>();
        for (Map.Entry<DocumentReference, Integer> entry : objectCounts(reloaded).entrySet()) {
            if (entry.getValue() > savedCounts.getOrDefault(entry.getKey(), 0)) {
                excess.add(entry.getKey());
            }
        }
        return excess;
    }

    /**
     * @param doc a document
     * @return the classes the document carries at least one object of (a removed object leaves a {@code null}
     *     slot behind, which does not count)
     */
    static Set<DocumentReference> objectClasses(XWikiDocument doc)
    {
        return new HashSet<>(objectCounts(doc).keySet());
    }

    /**
     * @param doc a document
     * @return the number of objects the document carries per class, for the classes it carries at least one
     *     object of (a removed object leaves a {@code null} slot behind, which does not count)
     */
    private static Map<DocumentReference, Integer> objectCounts(XWikiDocument doc)
    {
        Map<DocumentReference, Integer> counts = new HashMap<>();
        for (Map.Entry<DocumentReference, List<BaseObject>> entry : doc.getXObjects().entrySet()) {
            int count = (int) entry.getValue().stream().filter(object -> object != null).count();
            if (count > 0) {
                counts.put(entry.getKey(), count);
            }
        }
        return counts;
    }

    /**
     * Tells whether a field of the configuration object carries no value. A property that the store added with
     * no value when the object was saved (the Hibernate store materializes every class property missing from a
     * saved object) is as unset as a missing one: both must resolve to the reader's default.
     *
     * @param object the configuration object
     * @param fieldName the field to check
     * @return {@code true} when the field is absent or present without a value
     */
    private static boolean isUnset(BaseObject object, String fieldName)
    {
        PropertyInterface property = object.getField(fieldName);
        return property == null
            || (property instanceof BaseProperty<?> baseProperty && baseProperty.getValue() == null);
    }

    private static DocumentReference configReference(String wikiId)
    {
        return new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
    }

    private static DocumentReference classReference(String wikiId)
    {
        return new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
    }

    private String getStringProperty(String wikiId, String fieldName, String defaultValue)
    {
        try {
            BaseObject configObject = loadConfiguration(wikiId).object();
            String value = configObject == null ? null : configObject.getStringValue(fieldName);
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        } catch (Exception e) {
            this.logger.warn("Failed to read MCP server config field [{}], using default value: [{}]",
                fieldName, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to read MCP server config field [{}]", fieldName, e);
        }
        return defaultValue;
    }
}
