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
package org.xwiki.contrib.llm.mcp.script;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.internal.MCPServerConfiguration;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPFarmScriptService}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPFarmScriptServiceTest
{
    private static final String WIKI = "subwiki";

    private static final String MAIN_WIKI = "xwiki";

    @InjectMockComponents
    private MCPFarmScriptService service;

    @MockComponent
    private MCPServerConfiguration mcpConfig;

    @MockComponent
    private ContextualAuthorizationManager authorization;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private ComponentManager componentManager;

    private void farmAdmin(boolean allowed)
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(MAIN_WIKI))).thenReturn(allowed);
    }

    @Test
    void isEnabledDelegatesToConfig()
    {
        when(this.mcpConfig.isEnabled(WIKI)).thenReturn(true);
        assertTrue(this.service.isEnabled(WIKI));

        when(this.mcpConfig.isEnabled(WIKI)).thenReturn(false);
        assertFalse(this.service.isEnabled(WIKI));
    }

    @Test
    void canAdminReturnsTrueWhenAuthorized()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);

        assertTrue(this.service.canAdmin(WIKI));
        verify(this.authorization).hasAccess(Right.ADMIN, new WikiReference(WIKI));
    }

    @Test
    void canAdminReturnsFalseWhenNotAuthorized()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(false);

        assertFalse(this.service.canAdmin(WIKI));
        verify(this.authorization).hasAccess(Right.ADMIN, new WikiReference(WIKI));
    }

    @Test
    void setEnabledRefusesAndDoesNotWriteWhenNotAdmin()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(false);

        assertFalse(this.service.setEnabled(WIKI, true));
        verify(this.mcpConfig, never()).setEnabled(WIKI, true);
    }

    @Test
    void setEnabledDelegatesAndPropagatesTrueWhenAdmin()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.mcpConfig.setEnabled(WIKI, true)).thenReturn(true);

        assertTrue(this.service.setEnabled(WIKI, true));
        verify(this.mcpConfig).setEnabled(WIKI, true);
    }

    @Test
    void setEnabledDelegatesAndPropagatesFalseWhenAdmin()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.mcpConfig.setEnabled(WIKI, false)).thenReturn(false);

        assertFalse(this.service.setEnabled(WIKI, false));
        verify(this.mcpConfig).setEnabled(WIKI, false);
    }

    @Test
    void applyEnabledWritesWhenDesiredStateDiffersAndAdmin()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.mcpConfig.isEnabled(WIKI)).thenReturn(false);
        when(this.mcpConfig.setEnabled(WIKI, true)).thenReturn(true);

        BulkResult result = this.service.applyEnabled(new String[] {WIKI}, new String[] {WIKI});

        verify(this.mcpConfig).setEnabled(WIKI, true);
        assertEquals(1, result.getChanged());
        assertEquals(0, result.getSkipped());
    }

    @Test
    void applyEnabledDisablesWhenManagedButNotInEnabledSet()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.mcpConfig.isEnabled(WIKI)).thenReturn(true);
        when(this.mcpConfig.setEnabled(WIKI, false)).thenReturn(true);

        BulkResult result = this.service.applyEnabled(new String[] {WIKI}, new String[] {});

        verify(this.mcpConfig).setEnabled(WIKI, false);
        assertEquals(1, result.getChanged());
        assertEquals(0, result.getSkipped());
    }

    @Test
    void applyEnabledSkipsWriteWhenAlreadyInDesiredState()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.mcpConfig.isEnabled(WIKI)).thenReturn(true);

        BulkResult result = this.service.applyEnabled(new String[] {WIKI}, new String[] {WIKI});

        verify(this.mcpConfig, never()).setEnabled(anyString(), anyBoolean());
        assertEquals(0, result.getChanged());
        assertEquals(0, result.getSkipped());
    }

    @Test
    void applyEnabledSkipsWhenNotAdmin()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(false);

        BulkResult result = this.service.applyEnabled(new String[] {WIKI}, new String[] {WIKI});

        verify(this.mcpConfig, never()).setEnabled(anyString(), anyBoolean());
        verify(this.mcpConfig, never()).isEnabled(anyString());
        assertEquals(0, result.getChanged());
        assertEquals(1, result.getSkipped());
    }

    @Test
    void applyEnabledSkipsWhenWriteFails()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.mcpConfig.isEnabled(WIKI)).thenReturn(false);
        when(this.mcpConfig.setEnabled(WIKI, true)).thenReturn(false);

        BulkResult result = this.service.applyEnabled(new String[] {WIKI}, new String[] {WIKI});

        verify(this.mcpConfig).setEnabled(WIKI, true);
        assertEquals(0, result.getChanged());
        assertEquals(1, result.getSkipped());
    }

    @Test
    void applyEnabledWithNullArgumentsReturnsZeroCounts()
    {
        BulkResult nullManaged = this.service.applyEnabled(null, new String[] {WIKI});
        assertEquals(0, nullManaged.getChanged());
        assertEquals(0, nullManaged.getSkipped());

        BulkResult emptyManaged = this.service.applyEnabled(new String[] {}, null);
        assertEquals(0, emptyManaged.getChanged());
        assertEquals(0, emptyManaged.getSkipped());

        verify(this.mcpConfig, never()).setEnabled(anyString(), anyBoolean());
    }

    @Test
    void canFarmAdminReflectsMainWikiAdminRights()
    {
        farmAdmin(true);
        assertTrue(this.service.canFarmAdmin());

        farmAdmin(false);
        assertFalse(this.service.canFarmAdmin());
    }

    @Test
    void setCrossWikiReachRefusesAndDoesNotWriteWhenNotFarmAdmin()
    {
        farmAdmin(false);

        assertFalse(this.service.setCrossWikiReach(WIKI, true));
        verify(this.mcpConfig, never()).setCrossWikiReach(WIKI, true);
    }

    @Test
    void setCrossWikiReachDelegatesAndPropagatesTrueWhenFarmAdmin()
    {
        farmAdmin(true);
        when(this.mcpConfig.setCrossWikiReach(WIKI, true)).thenReturn(true);

        assertTrue(this.service.setCrossWikiReach(WIKI, true));
        verify(this.mcpConfig).setCrossWikiReach(WIKI, true);
    }

    @Test
    void applyReachWritesWhenDesiredStateDiffersAndFarmAdmin()
    {
        farmAdmin(true);
        when(this.mcpConfig.isCrossWikiReachAllowed(WIKI)).thenReturn(false);
        when(this.mcpConfig.setCrossWikiReach(WIKI, true)).thenReturn(true);

        BulkResult result = this.service.applyReach(new String[] {WIKI}, new String[] {WIKI});

        // The first thing an apply does (once past the farm gate) is promote the reach list to authoritative.
        verify(this.mcpConfig).initializeReachDefaults();
        verify(this.mcpConfig).setCrossWikiReach(WIKI, true);
        assertEquals(1, result.getChanged());
        assertEquals(0, result.getSkipped());
    }

    @Test
    void applyReachSkipsWriteWhenAlreadyInDesiredState()
    {
        farmAdmin(true);
        when(this.mcpConfig.isCrossWikiReachAllowed(WIKI)).thenReturn(true);

        BulkResult result = this.service.applyReach(new String[] {WIKI}, new String[] {WIKI});

        verify(this.mcpConfig, never()).setCrossWikiReach(anyString(), anyBoolean());
        assertEquals(0, result.getChanged());
        assertEquals(0, result.getSkipped());
    }

    @Test
    void applyReachRefusesEveryWikiWhenNotFarmAdmin()
    {
        farmAdmin(false);

        BulkResult result = this.service.applyReach(new String[] {WIKI, "another"}, new String[] {WIKI});

        // The farm gate governs all managed wikis at once: nothing is initialized, read or written, and every
        // managed wiki is counted as skipped.
        verify(this.mcpConfig, never()).initializeReachDefaults();
        verify(this.mcpConfig, never()).setCrossWikiReach(anyString(), anyBoolean());
        verify(this.mcpConfig, never()).isCrossWikiReachAllowed(anyString());
        assertEquals(0, result.getChanged());
        assertEquals(2, result.getSkipped());
    }

    private MCPTool tool(String category, String summary, boolean write)
    {
        MCPTool tool = mock(MCPTool.class);
        when(tool.getCategory()).thenReturn(category);
        when(tool.getSummary()).thenReturn(summary);
        when(tool.isWrite()).thenReturn(write);
        return tool;
    }

    @Test
    void getToolStatesReflectsEnabledSetAndSortsByCategoryThenId() throws Exception
    {
        Map<String, MCPTool> tools = Map.of(
            "query_documents", tool("Search & Navigation", "Search the wiki", false),
            "write_document", tool("Authoring", null, true),
            "man", tool("Help", "Tool catalog", false),
            // list_wikis is reach-gated: it is skipped before any of its metadata is read, so a bare mock
            // suffices (stubbing its category/summary would be unnecessary).
            "list_wikis", mock(MCPTool.class));
        when(this.componentManager.<MCPTool>getInstanceMap(MCPTool.class)).thenReturn(tools);
        when(this.mcpConfig.getEnabledToolIds(WIKI)).thenReturn(Set.of("query_documents", "man", "list_wikis"));
        when(this.mcpConfig.isMandatoryTool("man")).thenReturn(true);
        when(this.mcpConfig.isReachGatedTool("list_wikis")).thenReturn(true);

        List<MCPToolState> states = this.service.getToolStates(WIKI);

        // The reach-gated list_wikis never becomes a togglable tree entry, so it is absent from the states.
        assertFalse(states.stream().anyMatch(state -> "list_wikis".equals(state.getId())));
        // Sorted by category then id: Authoring/write_document, Help/man, Search & Navigation/query_documents.
        assertEquals(List.of("write_document", "man", "query_documents"),
            states.stream().map(MCPToolState::getId).toList());

        MCPToolState write = states.get(0);
        assertEquals("Authoring", write.getCategory());
        // A null summary surfaces as the empty string.
        assertEquals("", write.getSummary());
        assertTrue(write.isWrite());
        assertFalse(write.isMandatory());
        assertFalse(write.isEnabled());

        MCPToolState man = states.get(1);
        assertEquals("Tool catalog", man.getSummary());
        assertTrue(man.isMandatory());
        assertFalse(man.isWrite());
        assertTrue(man.isEnabled());

        assertFalse(states.get(2).isWrite());
        assertTrue(states.get(2).isEnabled());
    }

    @Test
    void setEnabledToolsRefusesAndDoesNotWriteWhenNotAdmin()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(false);

        assertFalse(this.service.setEnabledTools(WIKI, new String[] {"man"}));
        verify(this.mcpConfig, never()).setEnabledToolIds(anyString(), any());
    }

    @Test
    void setEnabledToolsDelegatesWhenAdmin()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.mcpConfig.setEnabledToolIds(WIKI, List.of("man", "query_documents"))).thenReturn(true);

        assertTrue(this.service.setEnabledTools(WIKI, new String[] {"man", "query_documents"}));
        verify(this.mcpConfig).setEnabledToolIds(WIKI, List.of("man", "query_documents"));
    }

    @Test
    void setEnabledToolsStoresEmptyListForNullWhenAdmin()
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.mcpConfig.setEnabledToolIds(WIKI, List.of())).thenReturn(true);

        assertTrue(this.service.setEnabledTools(WIKI, null));
        verify(this.mcpConfig).setEnabledToolIds(WIKI, List.of());
    }
}
