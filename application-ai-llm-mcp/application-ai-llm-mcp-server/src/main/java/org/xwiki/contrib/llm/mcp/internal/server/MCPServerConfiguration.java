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
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.internal.tool.MCPListWikisTool;
import org.xwiki.contrib.llm.mcp.internal.tool.MCPManTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.wiki.descriptor.WikiDescriptor;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Provides MCP server configuration values by reading the admin XObject stored at
 * {@code AI.MCP.Code.MCPServerConfig} on the wiki being queried. Falls back to sensible defaults
 * if the config document or XObject is absent (e.g. before the mcp-ui module is installed).
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
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private ComponentManager componentManager;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

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
     * @param wikiId the wiki to check
     * @return whether the MCP endpoint is enabled for the given wiki. MCP is enabled by default on every
     *     wiki so an endpoint is available with no admin action: an unset flag and a missing config object
     *     both resolve to true. Only an explicit {@code enabled=0} turns the endpoint off. A failed read
     *     still fails closed (returns false), so a config-read error 404s the endpoint rather than exposing it.
     * @since 0.9
     */
    public boolean isEnabled(String wikiId)
    {
        DocumentReference configRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            XWikiContext context = this.contextProvider.get();
            // This read must stay uncached: the farm dashboard writes the flag and re-renders the wiki's
            // row in the same request, relying on this returning the just-saved value.
            XWikiDocument configDoc = context.getWiki().getDocument(configRef, context);
            BaseObject configObject = configDoc.getXObject(classRef);
            // Enabled by default: a missing object or unset flag resolves to true. Only an explicit 0 is off.
            if (configObject == null || configObject.getField(FIELD_ENABLED) == null) {
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
        DocumentReference configRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument configDoc = context.getWiki().getDocument(configRef, context);
            BaseObject configObject = configDoc.getXObject(classRef);
            if (configObject == null || configObject.getField(FIELD_ALLOW_RENDERED_CONTENT) == null) {
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
        DocumentReference configRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            this.documentAccessBridge.setProperty(configRef, classRef, FIELD_ENABLED, enabled ? 1 : 0);
            return true;
        } catch (Exception e) {
            this.logger.warn("Failed to set the MCP enabled flag for wiki [{}]: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to set the MCP enabled flag for wiki [{}]", wikiId, e);
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
     *     or exclude any wiki, the main wiki included. A failed read fails closed (false) for every wiki.
     * @since 0.9
     */
    public boolean isCrossWikiReachAllowed(String wikiId)
    {
        String mainWiki = this.wikiDescriptorManager.getMainWikiId();
        DocumentReference configRef = new DocumentReference(mainWiki, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(mainWiki, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            XWikiContext context = this.contextProvider.get();
            // The reach grant is a FARM-level decision: it is read only from the MAIN wiki's config list. A subwiki
            // admin can edit their own wiki's config document but not the main wiki's, so reach cannot be self-granted.
            XWikiDocument configDoc = context.getWiki().getDocument(configRef, context);
            BaseObject configObject = configDoc.getXObject(classRef);
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
        DocumentReference configRef = new DocumentReference(mainWiki, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(mainWiki, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = context.getWiki().getDocument(configRef, context);
            BaseObject obj = doc.getXObject(classRef, true, context);
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
                context.getWiki().saveDocument(doc, "Updated MCP cross-wiki reach", true, context);
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
        DocumentReference configRef = new DocumentReference(mainWiki, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(mainWiki, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = context.getWiki().getDocument(configRef, context);
            BaseObject obj = doc.getXObject(classRef, true, context);
            if (obj.getIntValue(FIELD_REACH_INITIALIZED) == 1) {
                return true;
            }
            obj.set(FIELD_REACH_ENABLED_WIKIS, List.of(mainWiki), context);
            obj.set(FIELD_REACH_INITIALIZED, 1, context);
            context.getWiki().saveDocument(doc, "Initialized MCP cross-wiki reach defaults", true, context);
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
        DocumentReference configRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            XWikiContext context = this.contextProvider.get();
            // This read must stay uncached so a just-saved tool set is reflected on the next request.
            XWikiDocument configDoc = context.getWiki().getDocument(configRef, context);
            BaseObject configObject = configDoc.getXObject(classRef);
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
     * Returns the raw stored tool id list for the given wiki, or {@code null} when the wiki has never had a
     * tool set configured (so callers can distinguish "using defaults" from an explicit, possibly empty set).
     *
     * @param wikiId the wiki whose stored tool ids to read
     * @return the stored tool id list, or {@code null} if never configured
     * @since 0.9
     */
    public List<String> getConfiguredToolIds(String wikiId)
    {
        DocumentReference configRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument configDoc = context.getWiki().getDocument(configRef, context);
            BaseObject configObject = configDoc.getXObject(classRef);
            if (configObject == null || configObject.getField(FIELD_ENABLED_TOOLS) == null) {
                return null;
            }
            // getListValue returns a raw List; the stored values are tool id strings.
            @SuppressWarnings("unchecked")
            List<String> storedIds = configObject.getListValue(FIELD_ENABLED_TOOLS);
            return new ArrayList<>(storedIds);
        } catch (Exception e) {
            this.logger.warn("Could not read the configured MCP tool ids for wiki [{}]: [{}]", wikiId,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP configured tool ids read failure for wiki [{}]", wikiId, e);
            return null;
        }
    }

    /**
     * Stores the explicit set of enabled tool ids on the given wiki's configuration document, creating the
     * config XObject if necessary. A {@code null} list is stored as the empty list (an explicit "no tools").
     *
     * @param wikiId the wiki whose tool set to write
     * @param toolIds the tool ids to enable, or {@code null} to store an empty set
     * @return {@code true} if the set was written, {@code false} if the write failed (including when the
     *     deployed configuration class lacks the tool-list field, in which case nothing is saved)
     * @since 0.9
     */
    public boolean setEnabledToolIds(String wikiId, List<String> toolIds)
    {
        DocumentReference configRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
        try {
            XWikiContext context = this.contextProvider.get();
            XWikiDocument doc = context.getWiki().getDocument(configRef, context);
            BaseObject obj = doc.getXObject(classRef, true, context);
            obj.set(FIELD_ENABLED_TOOLS, toolIds == null ? List.of() : toolIds, context);
            if (obj.getField(FIELD_ENABLED_TOOLS) == null) {
                this.logger.warn("Could not store the MCP tool list for wiki [{}]: the [{}] field is absent "
                    + "from the deployed config class", wikiId, FIELD_ENABLED_TOOLS);
                return false;
            }
            context.getWiki().saveDocument(doc, "Updated MCP tool configuration", true, context);
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
            return loadConfigObject(wikiId);
        } catch (XWikiException e) {
            throw new IllegalStateException(
                "Could not read the MCP space filter configuration for wiki [" + wikiId + "]", e);
        }
    }

    /**
     * Loads the MCP configuration XObject for the given wiki, reading the config document uncached so a
     * just-saved value is reflected on the next request.
     *
     * @param wikiId the wiki whose configuration object to load
     * @return the configuration XObject, or {@code null} when the document has no such object
     * @throws XWikiException if the configuration document cannot be read
     */
    private BaseObject loadConfigObject(String wikiId) throws XWikiException
    {
        DocumentReference configRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
        DocumentReference classRef = new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
        XWikiContext context = this.contextProvider.get();
        XWikiDocument configDoc = context.getWiki().getDocument(configRef, context);
        return configDoc.getXObject(classRef);
    }

    private String getStringProperty(String wikiId, String fieldName, String defaultValue)
    {
        try {
            DocumentReference configRef =
                new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_DOC_NAME);
            DocumentReference classRef =
                new DocumentReference(wikiId, CONFIG_SPACES, CONFIG_CLASS_NAME);
            Object value = this.documentAccessBridge.getProperty(configRef, classRef, fieldName);
            if (value instanceof String) {
                String trimmedValue = ((String) value).trim();
                if (!trimmedValue.isEmpty()) {
                    return trimmedValue;
                }
            }
        } catch (Exception e) {
            this.logger.warn("Failed to read MCP server config field [{}], using default value: [{}]",
                fieldName, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to read MCP server config field [{}]", fieldName, e);
        }
        return defaultValue;
    }
}
