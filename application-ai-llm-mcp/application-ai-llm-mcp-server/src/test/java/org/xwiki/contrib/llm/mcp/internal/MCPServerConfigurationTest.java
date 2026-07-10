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
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import org.xwiki.wiki.descriptor.WikiDescriptor;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.PropertyInterface;

import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static final String SUB_WIKI = "subwiki";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPServerConfiguration mcpServerConfiguration;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private ComponentManager componentManager;

    @Mock
    private XWikiContext context;

    @Mock
    private XWiki xwiki;

    @Mock
    private XWikiDocument configDoc;

    @Mock
    private BaseObject configObject;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.context.getWiki()).thenReturn(this.xwiki);
        // getEnabledToolIds applies a cross-wiki reach gate that reads the MAIN wiki's config. Default it to a
        // readable document with no reach grant, so the tool-policy tests see reach off (list_wikis absent)
        // without extra stubbing. Tests exercising reach on override the reach list explicitly.
        lenient().when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        lenient().when(this.xwiki.getDocument(
            eq(new DocumentReference(MAIN_WIKI, MCPServerConfiguration.CONFIG_SPACES,
                MCPServerConfiguration.CONFIG_DOC_NAME)), eq(this.context)))
            .thenReturn(this.configDoc);
    }

    private void mockConfigDocument(String wikiId) throws Exception
    {
        when(this.xwiki.getDocument(
            eq(new DocumentReference(wikiId, MCPServerConfiguration.CONFIG_SPACES,
                MCPServerConfiguration.CONFIG_DOC_NAME)), eq(this.context)))
            .thenReturn(this.configDoc);
    }

    private DocumentReference classRef(String wikiId)
    {
        return new DocumentReference(wikiId, MCPServerConfiguration.CONFIG_SPACES,
            MCPServerConfiguration.CONFIG_CLASS_NAME);
    }

    @Test
    void getServerNameReturnsConfiguredValue() throws Exception
    {
        DocumentReference configRef =
            new DocumentReference(SUB_WIKI, MCPServerConfiguration.CONFIG_SPACES,
                MCPServerConfiguration.CONFIG_DOC_NAME);
        DocumentReference classRef =
            new DocumentReference(SUB_WIKI, MCPServerConfiguration.CONFIG_SPACES,
                MCPServerConfiguration.CONFIG_CLASS_NAME);
        when(this.documentAccessBridge.getProperty(configRef, classRef,
            MCPServerConfiguration.FIELD_SERVER_NAME)).thenReturn("My Wiki");

        assertEquals("My Wiki", this.mcpServerConfiguration.getServerName(SUB_WIKI));
        // The configured value wins: the pretty-name fallback is never consulted.
        verify(this.wikiDescriptorManager, never()).getById(SUB_WIKI);
    }

    @Test
    void getServerNameFallsBackToPrettyNameWhenNotConfigured() throws Exception
    {
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn("   ");
        WikiDescriptor descriptor = mock(WikiDescriptor.class);
        when(descriptor.getPrettyName()).thenReturn("Pretty Sub Wiki");
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenReturn(descriptor);

        assertEquals("Pretty Sub Wiki", this.mcpServerConfiguration.getServerName(SUB_WIKI));
    }

    @Test
    void getServerNameFallsBackToDefaultWhenPrettyNameBlank() throws Exception
    {
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn(null);
        WikiDescriptor descriptor = mock(WikiDescriptor.class);
        when(descriptor.getPrettyName()).thenReturn("   ");
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenReturn(descriptor);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName(SUB_WIKI));
    }

    @Test
    void getServerNameFallsBackToDefaultWhenNoDescriptor() throws Exception
    {
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn(null);
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName(SUB_WIKI));
    }

    @Test
    void getServerNameReturnsDefaultOnException() throws Exception
    {
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenThrow(new RuntimeException("Bridge down"));
        when(this.wikiDescriptorManager.getById(MAIN_WIKI)).thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName(MAIN_WIKI));
        assertEquals("Failed to read MCP server config field [serverName], using default value: "
            + "[RuntimeException: Bridge down]", this.logCapture.getMessage(0));
    }

    @Test
    void getServerDescriptionReturnsConfiguredValue() throws Exception
    {
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class),
            eq(MCPServerConfiguration.FIELD_SERVER_DESCRIPTION)))
            .thenReturn("My custom description");

        assertEquals("My custom description",
            this.mcpServerConfiguration.getServerDescription(SUB_WIKI));
    }

    @Test
    void getServerDescriptionReturnsDefaultWhenNotConfigured() throws Exception
    {
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_DESCRIPTION,
            this.mcpServerConfiguration.getServerDescription(SUB_WIKI));
    }

    @Test
    void getServerDescriptionReturnsDefaultWhenBlank() throws Exception
    {
        when(this.documentAccessBridge.getProperty(
            any(DocumentReference.class), any(DocumentReference.class), any(String.class)))
            .thenReturn("   ");

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_DESCRIPTION,
            this.mcpServerConfiguration.getServerDescription(SUB_WIKI));
    }

    @Test
    void isEnabledIsTrueByDefaultOnMainWikiWhenFieldUnset() throws Exception
    {
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(null);

        assertTrue(this.mcpServerConfiguration.isEnabled(MAIN_WIKI));
    }

    @Test
    void isEnabledIsTrueByDefaultOnSubWikiWhenFieldUnset() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(null);

        assertTrue(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
    }

    @Test
    void isEnabledIsTrueByDefaultWhenNoXObject() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);

        assertTrue(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
    }

    @Test
    void isEnabledIsTrueWhenExplicitlyEnabled() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED))
            .thenReturn(mock(PropertyInterface.class));
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(1);

        assertTrue(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
    }

    @Test
    void isEnabledIsFalseWhenExplicitlyDisabled() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED))
            .thenReturn(mock(PropertyInterface.class));
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(0);

        assertFalse(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
    }

    @Test
    void isEnabledFailsClosedOnMainWikiWhenDocumentReadThrows() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        assertFalse(this.mcpServerConfiguration.isEnabled(MAIN_WIKI));
        assertEquals("Could not read the MCP enabled flag for wiki [xwiki]; disabling the endpoint: "
            + "[XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(0));
    }

    @Test
    void isEnabledFailsClosedOnSubWikiWhenDocumentReadThrows() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        assertFalse(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
        assertEquals("Could not read the MCP enabled flag for wiki [subwiki]; disabling the endpoint: "
            + "[XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(0));
    }

    @Test
    void isRenderedContentAllowedIsTrueByDefaultWhenFieldUnset() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_RENDERED_CONTENT)).thenReturn(null);

        assertTrue(this.mcpServerConfiguration.isRenderedContentAllowed(SUB_WIKI));
    }

    @Test
    void isRenderedContentAllowedIsTrueByDefaultWhenNoXObject() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);

        assertTrue(this.mcpServerConfiguration.isRenderedContentAllowed(SUB_WIKI));
    }

    @Test
    void isRenderedContentAllowedIsTrueWhenExplicitOne() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_RENDERED_CONTENT))
            .thenReturn(mock(PropertyInterface.class));
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ALLOW_RENDERED_CONTENT)).thenReturn(1);

        assertTrue(this.mcpServerConfiguration.isRenderedContentAllowed(SUB_WIKI));
    }

    @Test
    void isRenderedContentAllowedIsFalseWhenExplicitZero() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_RENDERED_CONTENT))
            .thenReturn(mock(PropertyInterface.class));
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ALLOW_RENDERED_CONTENT)).thenReturn(0);

        assertFalse(this.mcpServerConfiguration.isRenderedContentAllowed(SUB_WIKI));
    }

    @Test
    void isRenderedContentAllowedFailsOpenWhenReadThrows() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        // Rendering is a capability, not a confidentiality boundary: a read glitch fails open to the default.
        assertTrue(this.mcpServerConfiguration.isRenderedContentAllowed(SUB_WIKI));
        assertEquals("Could not read the MCP allow-rendered-content flag for wiki [subwiki]; allowing "
            + "rendered content: [XWikiException: Error number 0 in 0: Store down]",
            this.logCapture.getMessage(0));
    }

    @Test
    void isCrossWikiReachAllowedIsTrueWhenInitializedMainListContainsWiki() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(1);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(List.of("otherwiki", SUB_WIKI));

        // The grant is read from the MAIN wiki's list, not the queried wiki's own config document.
        assertTrue(this.mcpServerConfiguration.isCrossWikiReachAllowed(SUB_WIKI));
    }

    @Test
    void isCrossWikiReachAllowedIsFalseWhenInitializedMainListLacksWiki() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(1);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(List.of("otherwiki"));

        assertFalse(this.mcpServerConfiguration.isCrossWikiReachAllowed(SUB_WIKI));
    }

    @Test
    void isCrossWikiReachAllowedCanTurnMainWikiOffOnceInitialized() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(1);
        // Once initialized, the list is authoritative and may exclude the main wiki itself.
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(List.of(SUB_WIKI));

        assertFalse(this.mcpServerConfiguration.isCrossWikiReachAllowed(MAIN_WIKI));
    }

    @Test
    void isCrossWikiReachAllowedIsFalseForSubWikiByDefaultWhenNotInitialized() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(0);

        assertFalse(this.mcpServerConfiguration.isCrossWikiReachAllowed(SUB_WIKI));
    }

    @Test
    void isCrossWikiReachAllowedIsFalseForSubWikiWhenNoXObject() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(null);

        assertFalse(this.mcpServerConfiguration.isCrossWikiReachAllowed(SUB_WIKI));
    }

    @Test
    void isCrossWikiReachAllowedIsTrueForMainWikiByDefaultWhenNotInitialized() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        // A materialised-but-empty reach list must NOT defeat the default: getIntValue is 0 for an unset flag.
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(0);
        lenient().when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(List.of());

        assertTrue(this.mcpServerConfiguration.isCrossWikiReachAllowed(MAIN_WIKI));
    }

    @Test
    void isCrossWikiReachAllowedIsTrueForMainWikiByDefaultWhenNoXObject() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(null);

        assertTrue(this.mcpServerConfiguration.isCrossWikiReachAllowed(MAIN_WIKI));
    }

    @Test
    void isCrossWikiReachAllowedFailsClosedWhenDocumentReadThrows() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        assertFalse(this.mcpServerConfiguration.isCrossWikiReachAllowed(SUB_WIKI));
        assertEquals("Could not read the MCP cross-wiki reach list for wiki [subwiki]; disabling reach: "
            + "[XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(0));
    }

    @Test
    void setCrossWikiReachAddsWikiToMainListAndSaves() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(new ArrayList<>());

        assertTrue(this.mcpServerConfiguration.setCrossWikiReach(SUB_WIKI, true));

        // The write targets the MAIN wiki's config document, adding the granted wiki id to its list.
        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS, List.of(SUB_WIKI),
            this.context);
        verify(this.xwiki).saveDocument(eq(this.configDoc), eq("Updated MCP cross-wiki reach"),
            eq(true), eq(this.context));
    }

    @Test
    void setCrossWikiReachRemovesWikiFromMainListAndSaves() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(new ArrayList<>(List.of(SUB_WIKI, "otherwiki")));

        assertTrue(this.mcpServerConfiguration.setCrossWikiReach(SUB_WIKI, false));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS, List.of("otherwiki"),
            this.context);
        verify(this.xwiki).saveDocument(eq(this.configDoc), eq("Updated MCP cross-wiki reach"),
            eq(true), eq(this.context));
    }

    @Test
    void setCrossWikiReachSkipsSaveWhenListUnchanged() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        // The wiki is already granted, so re-granting it is a no-op and nothing is written.
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(new ArrayList<>(List.of(SUB_WIKI)));

        assertTrue(this.mcpServerConfiguration.setCrossWikiReach(SUB_WIKI, true));

        verify(this.configObject, never()).set(anyString(), any(), any());
        verify(this.xwiki, never()).saveDocument(any(), anyString(), anyBoolean(), any());
    }

    @Test
    void initializeReachDefaultsSeedsMainWikiAndFlagAndSaves() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(0);

        assertTrue(this.mcpServerConfiguration.initializeReachDefaults());

        // The default (main wiki reaches, others do not) is materialized into the list as an explicit value.
        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS, List.of(MAIN_WIKI),
            this.context);
        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_INITIALIZED, 1, this.context);
        verify(this.xwiki).saveDocument(eq(this.configDoc), eq("Initialized MCP cross-wiki reach defaults"),
            eq(true), eq(this.context));
    }

    @Test
    void initializeReachDefaultsIsNoOpWhenAlreadyInitialized() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(1);

        assertTrue(this.mcpServerConfiguration.initializeReachDefaults());

        verify(this.configObject, never()).set(anyString(), any(), any());
        verify(this.xwiki, never()).saveDocument(any(), anyString(), anyBoolean(), any());
    }

    @Test
    void setCrossWikiReachReturnsFalseAndLogsWhenWriteThrows() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Save down"));

        assertFalse(this.mcpServerConfiguration.setCrossWikiReach(SUB_WIKI, true));
        assertEquals("Failed to set the MCP cross-wiki reach for wiki [subwiki]: "
            + "[XWikiException: Error number 0 in 0: Save down]", this.logCapture.getMessage(0));
    }

    @Test
    void setEnabledWritesOneForEnable() throws Exception
    {
        DocumentReference configRef = new DocumentReference(SUB_WIKI, MCPServerConfiguration.CONFIG_SPACES,
            MCPServerConfiguration.CONFIG_DOC_NAME);

        assertTrue(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));
        verify(this.documentAccessBridge).setProperty(configRef, classRef(SUB_WIKI),
            MCPServerConfiguration.FIELD_ENABLED, 1);
    }

    @Test
    void setEnabledWritesZeroForDisable() throws Exception
    {
        DocumentReference configRef = new DocumentReference(MAIN_WIKI, MCPServerConfiguration.CONFIG_SPACES,
            MCPServerConfiguration.CONFIG_DOC_NAME);

        assertTrue(this.mcpServerConfiguration.setEnabled(MAIN_WIKI, false));
        verify(this.documentAccessBridge).setProperty(configRef, classRef(MAIN_WIKI),
            MCPServerConfiguration.FIELD_ENABLED, 0);
    }

    @Test
    void setEnabledReturnsFalseAndLogsWhenWriteThrows() throws Exception
    {
        doThrow(new XWikiException(0, 0, "Save down")).when(this.documentAccessBridge)
            .setProperty(any(DocumentReference.class), any(DocumentReference.class),
                eq(MCPServerConfiguration.FIELD_ENABLED), any());

        assertFalse(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));
        assertEquals("Failed to set the MCP enabled flag for wiki [subwiki]: "
            + "[XWikiException: Error number 0 in 0: Save down]", this.logCapture.getMessage(0));
    }

    private MCPTool tool(boolean write)
    {
        MCPTool tool = mock(MCPTool.class);
        when(tool.isWrite()).thenReturn(write);
        return tool;
    }

    private void mockToolMap() throws Exception
    {
        Map<String, MCPTool> tools = Map.of(
            "query_documents", tool(false),
            "write_document", tool(true),
            "man", tool(false));
        when(this.componentManager.<MCPTool>getInstanceMap(MCPTool.class)).thenReturn(tools);
    }

    @Test
    void getEnabledToolIdsReturnsDefaultPolicyWhenFieldUnset() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        // getListValue returns an empty List when the field has never been set.
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(List.of());
        mockToolMap();

        // The write tool is off by default; the read tools stay on and the mandatory man tool is included.
        assertEquals(Set.of("query_documents", "man"),
            this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI));
    }

    @Test
    void getEnabledToolIdsEmptyListFallsBackToDefault() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        // An explicit empty stored list is not "all tools off": it resolves to the default policy.
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(List.of());
        mockToolMap();

        assertEquals(Set.of("query_documents", "man"),
            this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI));
    }

    @Test
    void getEnabledToolIdsReturnsDefaultPolicyWhenNoConfigObject() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);
        mockToolMap();

        assertEquals(Set.of("query_documents", "man"),
            this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI));
    }

    @Test
    void getEnabledToolIdsReturnsStoredSetWhenFieldSet() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(List.of("write_document"));

        // The explicit set overrides the default policy; the component manager is never consulted. The
        // mandatory man tool is still unioned in even though the stored set does not list it.
        assertEquals(Set.of("write_document", "man"),
            this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI));
        verify(this.componentManager, never()).getInstanceMap(MCPTool.class);
    }

    @Test
    void getEnabledToolIdsKeepsMandatoryToolOnReadFailure() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));
        mockToolMap();

        assertTrue(this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI).contains("man"));
        assertEquals("Could not read the MCP enabled tools for wiki [subwiki]; applying the default "
            + "policy: [XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(0));
        // The reach gate also reads the (also failing) main-wiki config, so it fails closed with its own warn.
        assertEquals("Could not read the MCP cross-wiki reach list for wiki [subwiki]; disabling reach: "
            + "[XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(1));
    }

    @Test
    void getEnabledToolIdsAppliesDefaultPolicyOnReadFailure() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));
        mockToolMap();

        assertEquals(Set.of("query_documents", "man"),
            this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI));
        assertEquals("Could not read the MCP enabled tools for wiki [subwiki]; applying the default "
            + "policy: [XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(0));
        // The reach gate also reads the (also failing) main-wiki config, so it fails closed with its own warn.
        assertEquals("Could not read the MCP cross-wiki reach list for wiki [subwiki]; disabling reach: "
            + "[XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(1));
    }

    @Test
    void getEnabledToolIdsIncludesListWikisWhenReachEnabled() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_ENABLED_TOOLS)).thenReturn(List.of());
        // The main-wiki reach list (initialized) grants SUB_WIKI cross-wiki reach.
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(1);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(List.of(SUB_WIKI));
        mockToolMap();

        // list_wikis is reach-gated: it appears in the effective set because reach is enabled, even though it
        // is neither in the stored/default tool policy nor mandatory.
        assertEquals(Set.of("query_documents", "man", "list_wikis"),
            this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI));
    }

    @Test
    void getEnabledToolIdsExcludesListWikisWhenReachDisabled() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_ENABLED_TOOLS)).thenReturn(List.of());
        // Reach off: the main-wiki config (from setUp) has no reach object, so list_wikis stays absent.
        mockToolMap();

        Set<String> ids = this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI);
        assertFalse(ids.contains("list_wikis"));
        assertEquals(Set.of("query_documents", "man"), ids);
    }

    @Test
    void isReachGatedToolIsTrueOnlyForReachGatedTools()
    {
        assertTrue(this.mcpServerConfiguration.isReachGatedTool("list_wikis"));
        assertFalse(this.mcpServerConfiguration.isReachGatedTool("query_documents"));
        assertFalse(this.mcpServerConfiguration.isReachGatedTool("man"));
    }

    @Test
    void getConfiguredToolIdsReturnsNullWhenUnset() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED_TOOLS)).thenReturn(null);

        assertNull(this.mcpServerConfiguration.getConfiguredToolIds(SUB_WIKI));
    }

    @Test
    void getConfiguredToolIdsReturnsStoredListWhenSet() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(mock(PropertyInterface.class));
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(List.of("man", "query_documents"));

        assertEquals(List.of("man", "query_documents"),
            this.mcpServerConfiguration.getConfiguredToolIds(SUB_WIKI));
    }

    @Test
    void setEnabledToolIdsWritesListAndSaves() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(mock(PropertyInterface.class));

        List<String> toolIds = List.of("man", "query_documents");
        assertTrue(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, toolIds));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED_TOOLS, toolIds, this.context);
        verify(this.xwiki).saveDocument(eq(this.configDoc), eq("Updated MCP tool configuration"),
            eq(true), eq(this.context));
    }

    @Test
    void setEnabledToolIdsWritesEmptyListForNull() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(mock(PropertyInterface.class));

        assertTrue(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, null));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED_TOOLS, List.of(), this.context);
    }

    @Test
    void setEnabledToolIdsReturnsFalseAndWarnsWhenFieldAbsentFromClass() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        // No getField stub: the deployed config class lacks the enabledTools field, so set() silently no-ops.

        assertFalse(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, List.of("man")));

        verify(this.xwiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(),
            any(XWikiContext.class));
        assertEquals("Could not store the MCP tool list for wiki [subwiki]: the [enabledTools] field is absent "
            + "from the deployed config class", this.logCapture.getMessage(0));
    }

    @Test
    void setEnabledToolIdsReturnsFalseAndLogsWhenWriteThrows() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Save down"));

        assertFalse(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, List.of("man")));
        assertEquals("Failed to set the MCP enabled tools for wiki [subwiki]: "
            + "[XWikiException: Error number 0 in 0: Save down]", this.logCapture.getMessage(0));
    }

    @Test
    void getSpaceFilterModeReturnsNoneWhenNoXObject() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);

        assertEquals(MCPServerConfiguration.SPACE_FILTER_MODE_NONE,
            this.mcpServerConfiguration.getSpaceFilterMode(SUB_WIKI));
    }

    @Test
    void getSpaceFilterModeReturnsNoneWhenFieldBlank() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SPACE_FILTER_MODE)).thenReturn("  ");

        assertEquals(MCPServerConfiguration.SPACE_FILTER_MODE_NONE,
            this.mcpServerConfiguration.getSpaceFilterMode(SUB_WIKI));
    }

    @Test
    void getSpaceFilterModeReadsStoredValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SPACE_FILTER_MODE))
            .thenReturn("whitelist");

        assertEquals(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST,
            this.mcpServerConfiguration.getSpaceFilterMode(SUB_WIKI));
    }

    @Test
    void getSpaceFilterModePropagatesOnReadFailure() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        // A genuine read failure propagates so the caller (the space filter) can fail closed.
        assertThrows(IllegalStateException.class,
            () -> this.mcpServerConfiguration.getSpaceFilterMode(SUB_WIKI));
    }

    @Test
    void getSpaceFilterSpacesReadsListValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_SPACE_FILTER_SPACES))
            .thenReturn(List.of("Help.Guides", "Sandbox"));

        assertEquals(List.of("Help.Guides", "Sandbox"),
            this.mcpServerConfiguration.getSpaceFilterSpaces(SUB_WIKI));
    }

    @Test
    void getSpaceFilterSpacesEmptyWhenNoXObject() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);

        assertTrue(this.mcpServerConfiguration.getSpaceFilterSpaces(SUB_WIKI).isEmpty());
    }

    @Test
    void getSpaceFilterDocumentsReadsListValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_SPACE_FILTER_DOCUMENTS))
            .thenReturn(List.of("Help.FAQ"));

        assertEquals(List.of("Help.FAQ"),
            this.mcpServerConfiguration.getSpaceFilterDocuments(SUB_WIKI));
    }

    @Test
    void getSpaceFilterDocumentsPropagatesOnReadFailure() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        // A genuine read failure propagates so the caller (the space filter) can fail closed.
        assertThrows(IllegalStateException.class,
            () -> this.mcpServerConfiguration.getSpaceFilterDocuments(SUB_WIKI));
    }

    @Test
    void getSpaceFilterSpacesPropagatesOnReadFailure() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        assertThrows(IllegalStateException.class,
            () -> this.mcpServerConfiguration.getSpaceFilterSpaces(SUB_WIKI));
    }
}
