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
package org.xwiki.contrib.llm.mcp.internal.access;

import java.util.List;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.internal.server.MCPServerConfiguration;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptor;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultMCPWikiReach}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPWikiReachTest
{
    private static final String CURRENT = "xwiki";

    private static final String OTHER = "other";

    private static final String ALL = "all";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private DefaultMCPWikiReach wikiReach;

    @MockComponent
    private MCPServerConfiguration mcpConfig;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @Mock
    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.context.getWikiId()).thenReturn(CURRENT);
    }

    private void reachEnabled(boolean enabled)
    {
        lenient().when(this.mcpConfig.isCrossWikiReachAllowed(CURRENT)).thenReturn(enabled);
    }

    @Test
    void blankParamResolvesToCurrentWiki() throws Exception
    {
        assertEquals(List.of(CURRENT), this.wikiReach.resolveSearchWikis(null));
        assertEquals(List.of(CURRENT), this.wikiReach.resolveSearchWikis(""));
    }

    @Test
    void currentWikiParamResolvesToCurrentWiki() throws Exception
    {
        assertEquals(List.of(CURRENT), this.wikiReach.resolveSearchWikis(CURRENT));
    }

    @Test
    void reachDisabledWithOtherWikiThrows()
    {
        reachEnabled(false);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.wikiReach.resolveSearchWikis(OTHER));
        assertTrue(exception.getMessage().contains("Cross-wiki search is not enabled"), exception.getMessage());
    }

    @Test
    void reachDisabledWithAllThrows()
    {
        reachEnabled(false);

        assertThrows(MCPAccessDeniedException.class, () -> this.wikiReach.resolveSearchWikis(ALL));
    }

    @Test
    void reachEnabledWithAllResolvesToNullMeaningWholeFarm() throws Exception
    {
        reachEnabled(true);

        assertNull(this.wikiReach.resolveSearchWikis(ALL));
        // The special value is matched case-insensitively.
        assertNull(this.wikiReach.resolveSearchWikis("ALL"));
    }

    @Test
    void reachEnabledWithSpecificExistingWikiResolvesToThatWiki() throws Exception
    {
        reachEnabled(true);
        when(this.wikiDescriptorManager.getById(OTHER)).thenReturn(mock(WikiDescriptor.class));

        assertEquals(List.of(OTHER), this.wikiReach.resolveSearchWikis(OTHER));
    }

    @Test
    void reachEnabledWithNonExistentWikiThrows() throws Exception
    {
        reachEnabled(true);
        when(this.wikiDescriptorManager.getById(OTHER)).thenReturn(null);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.wikiReach.resolveSearchWikis(OTHER));
        assertTrue(exception.getMessage().contains("does not exist"), exception.getMessage());
    }

    @Test
    void reachEnabledWithUnverifiableWikiThrows() throws Exception
    {
        reachEnabled(true);
        when(this.wikiDescriptorManager.getById(OTHER)).thenThrow(new WikiManagerException("boom"));

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.wikiReach.resolveSearchWikis(OTHER));
        assertTrue(exception.getMessage().contains("not available for cross-wiki search"), exception.getMessage());
        assertTrue(this.logCapture.getMessage(0).contains("Could not verify wiki [other]"),
            this.logCapture.getMessage(0));
    }

    @Test
    void nullContextWikiFailsClosed() throws Exception
    {
        when(this.context.getWikiId()).thenReturn(null);

        assertEquals(List.of(), this.wikiReach.resolveSearchWikis(null));
        assertFalse(this.wikiReach.isReachEnabled());
        assertFalse(this.wikiReach.canReachWiki(OTHER));
    }

    @Test
    void canReachSelfIsAlwaysTrue()
    {
        assertTrue(this.wikiReach.canReachWiki(CURRENT));
    }

    @Test
    void canReachOtherIsTrueWhenReachEnabledRegardlessOfTargetEnabled()
    {
        reachEnabled(true);

        assertTrue(this.wikiReach.canReachWiki(OTHER));
    }

    @Test
    void canReachOtherIsFalseWhenReachDisabled()
    {
        reachEnabled(false);

        assertFalse(this.wikiReach.canReachWiki(OTHER));
    }

    @Test
    void isReachEnabledReflectsTheCurrentWikiFlag()
    {
        reachEnabled(true);
        assertTrue(this.wikiReach.isReachEnabled());
    }

    @Test
    void singleWikiBlankParamResolvesToCurrentWiki() throws Exception
    {
        assertEquals(CURRENT, this.wikiReach.resolveSingleWiki(null));
        assertEquals(CURRENT, this.wikiReach.resolveSingleWiki(""));
    }

    @Test
    void singleWikiCurrentParamResolvesToCurrentWiki() throws Exception
    {
        assertEquals(CURRENT, this.wikiReach.resolveSingleWiki(CURRENT));
    }

    @Test
    void singleWikiReachDisabledWithOtherWikiThrows()
    {
        reachEnabled(false);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.wikiReach.resolveSingleWiki(OTHER));
        assertEquals("Cross-wiki access is not enabled for this endpoint. Omit the 'wiki' parameter to stay in "
            + "this wiki (\"xwiki\").", exception.getMessage());
    }

    @Test
    void singleWikiReachEnabledWithExistingWikiResolvesToThatWiki() throws Exception
    {
        reachEnabled(true);
        when(this.wikiDescriptorManager.getById(OTHER)).thenReturn(mock(WikiDescriptor.class));

        assertEquals(OTHER, this.wikiReach.resolveSingleWiki(OTHER));
    }

    @Test
    void singleWikiAllThrowsOneWikiPerCallMessage()
    {
        reachEnabled(true);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.wikiReach.resolveSingleWiki(ALL));
        assertEquals("This tool renders one wiki at a time; pass a single wiki id. Use list_wikis to see "
            + "reachable wikis.", exception.getMessage());
    }

    @Test
    void singleWikiNonExistentThrows() throws Exception
    {
        reachEnabled(true);
        when(this.wikiDescriptorManager.getById(OTHER)).thenReturn(null);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.wikiReach.resolveSingleWiki(OTHER));
        assertEquals("Wiki \"other\" does not exist. Use list_wikis to see reachable wikis.",
            exception.getMessage());
    }

    @Test
    void singleWikiUnverifiableFailsClosed() throws Exception
    {
        reachEnabled(true);
        when(this.wikiDescriptorManager.getById(OTHER)).thenThrow(new WikiManagerException("boom"));

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.wikiReach.resolveSingleWiki(OTHER));
        assertEquals("Wiki \"other\" is not available from this endpoint. Use list_wikis to see reachable "
            + "wikis.", exception.getMessage());
        assertTrue(this.logCapture.getMessage(0).contains("Could not verify wiki [other]"),
            this.logCapture.getMessage(0));
    }
}
