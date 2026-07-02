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

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptor;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiContext;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPListWikisTool}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPListWikisToolTest
{
    private static final String CURRENT = "current";

    private static final String VIEWABLE = "viewablewiki";

    private static final String OTHER_VIEWABLE = "otherviewablewiki";

    private static final String HIDDEN = "hiddenwiki";

    private static final String THIS_ENDPOINT = "(this endpoint)";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPListWikisTool tool;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private MCPWikiReach wikiReach;

    @MockComponent
    private ContextualAuthorizationManager authorization;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @BeforeEach
    void setUp()
    {
        XWikiContext xcontext = mock(XWikiContext.class);
        lenient().when(this.contextProvider.get()).thenReturn(xcontext);
        lenient().when(xcontext.getWikiId()).thenReturn(CURRENT);
    }

    private static WikiReference wikiRef(String wikiId)
    {
        return new WikiReference(wikiId);
    }

    private void stubDescriptor(String wikiId, String prettyName) throws Exception
    {
        WikiDescriptor descriptor = mock(WikiDescriptor.class);
        when(descriptor.getPrettyName()).thenReturn(prettyName);
        when(this.wikiDescriptorManager.getById(wikiId)).thenReturn(descriptor);
    }

    private String callList()
    {
        McpSchema.CallToolResult result = this.tool.execute(
            McpSchema.CallToolRequest.builder(MCPListWikisTool.TOOL_ID).arguments(Map.of()).build());
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    void reachDisabledListsOnlyCurrentWikiAndGuidanceWithoutEnumeratingWikis() throws Exception
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);
        stubDescriptor(CURRENT, "Current Wiki");

        String output = callList();

        assertTrue(output.contains("disabled for this endpoint"), output);
        assertTrue(output.contains("dashboard"), output);
        assertTrue(output.contains("  " + CURRENT + " — Current Wiki " + THIS_ENDPOINT), output);
        // No other wiki is named, and the descriptor list is never enumerated when reach is disabled.
        assertFalse(output.contains(VIEWABLE), output);
        verify(this.wikiDescriptorManager, never()).getAllIds();
    }

    @Test
    void reachEnabledListsCurrentPlusEveryViewableWikiRegardlessOfMcpEnabled() throws Exception
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);
        when(this.wikiDescriptorManager.getAllIds())
            .thenReturn(List.of(CURRENT, VIEWABLE, OTHER_VIEWABLE, HIDDEN));
        // Reach is a source-side power grant: a target wiki is reachable when the user can view it, whether or not
        // it has its own MCP endpoint enabled. Only the not-viewable wiki is excluded.
        when(this.authorization.hasAccess(eq(Right.VIEW), eq(wikiRef(VIEWABLE)))).thenReturn(true);
        when(this.authorization.hasAccess(eq(Right.VIEW), eq(wikiRef(OTHER_VIEWABLE)))).thenReturn(true);
        when(this.authorization.hasAccess(eq(Right.VIEW), eq(wikiRef(HIDDEN)))).thenReturn(false);
        // Current wiki has no descriptor -> falls back to its id; the others have pretty names.
        when(this.wikiDescriptorManager.getById(CURRENT)).thenReturn(null);
        stubDescriptor(VIEWABLE, "Viewable Wiki");
        stubDescriptor(OTHER_VIEWABLE, "Other Viewable Wiki");

        String output = callList();

        assertTrue(output.contains("wiki=\"all\""), output);
        assertTrue(output.contains("  " + CURRENT + " — " + CURRENT + " " + THIS_ENDPOINT), output);
        assertTrue(output.contains("  " + VIEWABLE + " — Viewable Wiki"), output);
        // A viewable wiki that does not have its own MCP endpoint enabled is now included.
        assertTrue(output.contains("  " + OTHER_VIEWABLE + " — Other Viewable Wiki"), output);
        // The current wiki row leads; the reachable other wikis follow it.
        assertTrue(output.indexOf(CURRENT) < output.indexOf(VIEWABLE), output);
        // Only the current wiki carries the endpoint marker.
        assertEquals(output.indexOf(THIS_ENDPOINT), output.lastIndexOf(THIS_ENDPOINT), output);
        // A wiki the user cannot view is excluded.
        assertFalse(output.contains(HIDDEN), output);
    }

    @Test
    void wikiListFailureFallsBackToCurrentWikiOnly() throws Exception
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);
        when(this.wikiDescriptorManager.getAllIds()).thenThrow(new WikiManagerException("boom"));
        stubDescriptor(CURRENT, "Current Wiki");

        String output = callList();

        // The failure degrades to just the current wiki rather than erroring out.
        assertTrue(output.contains("  " + CURRENT + " — Current Wiki " + THIS_ENDPOINT), output);
        assertFalse(output.contains(VIEWABLE), output);
        assertTrue(this.logCapture.getMessage(0).contains("Could not list wikis for list_wikis"),
            this.logCapture.getMessage(0));
    }

    @Test
    void toolDefinitionAdvertisesNoParameters()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertEquals(MCPListWikisTool.TOOL_ID, definition.name());
        Map<String, Object> schema = definition.inputSchema();
        assertEquals("object", schema.get("type"));
        assertTrue(((Map<?, ?>) schema.get("properties")).isEmpty(), schema.toString());
        assertTrue(((List<?>) schema.get("required")).isEmpty(), schema.toString());
    }

    @Test
    void toolMetadataIsSet()
    {
        assertEquals("Search & Navigation", this.tool.getCategory());
        assertFalse(this.tool.isWrite());
        assertTrue(this.tool.isEnabled());
        assertTrue(this.tool.getManPage().contains("SEE ALSO"), this.tool.getManPage());
        assertTrue(this.tool.getSummary().contains("reachable"), this.tool.getSummary());
    }
}
