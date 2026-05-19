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
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

/**
 * Provides MCP server configuration values by reading the admin XObject stored at
 * {@code AI.MCP.Code.MCPServerConfig} on the main wiki. Falls back to sensible defaults
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

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private Logger logger;

    /**
     * @return the MCP server name to advertise to connecting agents, or the default {@code "XWiki"} if not configured
     */
    public String getServerName()
    {
        return getStringProperty(FIELD_SERVER_NAME, DEFAULT_SERVER_NAME);
    }

    /**
     * @return the MCP server instructions to advertise to connecting agents, or the default if not configured
     */
    public String getServerDescription()
    {
        return getStringProperty(FIELD_SERVER_DESCRIPTION, DEFAULT_SERVER_DESCRIPTION);
    }

    private String getStringProperty(String fieldName, String defaultValue)
    {
        try {
            String mainWiki = this.wikiDescriptorManager.getMainWikiId();
            DocumentReference configRef =
                new DocumentReference(mainWiki, CONFIG_SPACES, CONFIG_DOC_NAME);
            DocumentReference classRef =
                new DocumentReference(mainWiki, CONFIG_SPACES, CONFIG_CLASS_NAME);
            Object value = this.documentAccessBridge.getProperty(configRef, classRef, fieldName);
            if (value instanceof String) {
                String trimmedValue = ((String) value).trim();
                if (!trimmedValue.isEmpty()) {
                    return trimmedValue;
                }
            }
        } catch (Exception e) {
            this.logger.warn("Failed to read MCP server config field [{}], using default value", fieldName, e);
        }
        return defaultValue;
    }
}
