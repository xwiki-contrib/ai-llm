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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPServerConfiguration}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPServerConfigurationTest
{
    private static final String MAIN_WIKI = "xwiki";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPServerConfiguration mcpServerConfiguration;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @Test
    void getServerNameReturnsConfiguredValue() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        DocumentReference configRef =
            new DocumentReference(MAIN_WIKI, MCPServerConfiguration.CONFIG_SPACES,
                MCPServerConfiguration.CONFIG_DOC_NAME);
        DocumentReference classRef =
            new DocumentReference(MAIN_WIKI, MCPServerConfiguration.CONFIG_SPACES,
                MCPServerConfiguration.CONFIG_CLASS_NAME);
        when(this.documentAccessBridge.getProperty(configRef, classRef,
            MCPServerConfiguration.FIELD_SERVER_NAME)).thenReturn("My Wiki");

        assertEquals("My Wiki", this.mcpServerConfiguration.getServerName());
    }

    @Test
    void getServerNameReturnsDefaultWhenPropertyIsEmpty() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn("");

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName());
    }

    @Test
    void getServerNameReturnsDefaultWhenPropertyIsBlank() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn("   ");

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName());
    }

    @Test
    void getServerNameReturnsDefaultWhenPropertyIsNull() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName());
    }

    @Test
    void getServerNameReturnsDefaultOnException() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenThrow(
            new RuntimeException("Wiki manager down"));

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName());
        assertEquals("Failed to read MCP server config field [serverName], using default value",
            this.logCapture.getMessage(0));
    }

    @Test
    void getServerDescriptionReturnsConfiguredValue() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class),
            eq(MCPServerConfiguration.FIELD_SERVER_DESCRIPTION)))
            .thenReturn("My custom description");

        assertEquals("My custom description",
            this.mcpServerConfiguration.getServerDescription());
    }

    @Test
    void getServerDescriptionReturnsDefaultWhenNotConfigured() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_DESCRIPTION,
            this.mcpServerConfiguration.getServerDescription());
    }

    @Test
    void getServerDescriptionReturnsDefaultWhenBlank() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn("   ");

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_DESCRIPTION,
            this.mcpServerConfiguration.getServerDescription());
    }
}
