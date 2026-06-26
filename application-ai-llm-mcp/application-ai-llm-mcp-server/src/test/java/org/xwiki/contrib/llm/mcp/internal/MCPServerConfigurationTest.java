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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    void setUp()
    {
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.context.getWiki()).thenReturn(this.xwiki);
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
    void isEnabledIsFalseOnMainWikiWhenObjectHasNoEnabledValue() throws Exception
    {
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(0);

        assertFalse(this.mcpServerConfiguration.isEnabled(MAIN_WIKI));
    }

    @Test
    void isEnabledIsFalseOnSubWikiWhenObjectHasNoEnabledValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(0);

        assertFalse(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
    }

    @Test
    void isEnabledIsFalseWhenNoXObject() throws Exception
    {
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(null);

        assertFalse(this.mcpServerConfiguration.isEnabled(MAIN_WIKI));
    }

    @Test
    void isEnabledIsTrueOnlyWhenExplicitlyEnabled() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(1);

        assertTrue(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
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

        assertTrue(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, null));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED_TOOLS, List.of(), this.context);
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
}
