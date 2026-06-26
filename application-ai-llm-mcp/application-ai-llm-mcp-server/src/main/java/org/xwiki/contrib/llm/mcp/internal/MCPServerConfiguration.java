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
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.wiki.descriptor.WikiDescriptor;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.XWikiContext;
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

    /**
     * Tool ids that are always part of the effective enabled set, regardless of the stored or default
     * policy. The {@code man} catalog is mandatory: an agent must always be able to discover the tools.
     */
    static final Set<String> MANDATORY_TOOL_IDS = Set.of(MCPManTool.TOOL_ID);

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
     * @return whether the MCP endpoint is enabled for the given wiki. MCP is disabled by default on every
     *     wiki: the endpoint is enabled only by an explicit {@code enabled=1} on the config object. An unset
     *     flag, a missing config object, and a failed read all resolve to false, so the endpoint fails closed.
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
            return configObject != null && configObject.getIntValue(FIELD_ENABLED) == 1;
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP enabled flag for wiki [{}]; disabling the endpoint: [{}]",
                wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP enabled flag read failure for wiki [{}]", wikiId, e);
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
     * Returns the effective set of tool ids the given wiki's MCP endpoint exposes. The mandatory tools
     * (currently the {@code man} catalog) are always present, even when the stored list omits them. Beyond
     * those, the effective set is the stored list when it is non-empty (an explicit admin override); an empty
     * or never-set list resolves to the default policy (read tools on, write tools off). Disabling every tool
     * is therefore not a representable state: it falls back to the defaults, so disable the whole endpoint via
     * the {@code enabled} flag instead. A failed read also falls back to the default policy so a read glitch
     * keeps read tools available with write tools off, never silently enabling writes; the mandatory tools
     * remain in the set in every case.
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
            return withMandatory(base);
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP enabled tools for wiki [{}]; applying the default "
                + "policy: [{}]", wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP enabled tools read failure for wiki [{}]", wikiId, e);
            return withMandatory(defaultEnabledToolIds());
        }
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
     * @return {@code true} if the set was written, {@code false} if the write failed
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
                this.logger.debug("Could not store the MCP tool list for wiki [{}]: the [{}] field is absent "
                    + "from the deployed config class", wikiId, FIELD_ENABLED_TOOLS);
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
