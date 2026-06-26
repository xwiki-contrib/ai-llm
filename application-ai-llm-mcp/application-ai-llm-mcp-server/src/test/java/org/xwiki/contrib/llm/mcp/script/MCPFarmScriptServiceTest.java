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

import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.mcp.internal.MCPServerConfiguration;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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

    @InjectMockComponents
    private MCPFarmScriptService service;

    @MockComponent
    private MCPServerConfiguration mcpConfig;

    @MockComponent
    private ContextualAuthorizationManager authorization;

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
}
