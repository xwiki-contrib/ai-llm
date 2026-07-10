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

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPConfigChangeEventListener}.
 *
 * @version $Id$
 */
@ComponentTest
class MCPConfigChangeEventListenerTest
{
    private static final String MAIN_WIKI = "xwiki";

    private static final String SUB_WIKI = "subwikiX";

    @InjectMockComponents
    private MCPConfigChangeEventListener listener;

    @MockComponent
    private XWikiMCPServerManager mcpServerManager;

    @MockComponent
    private MCPSpaceFilter spaceFilter;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @BeforeEach
    void setUp()
    {
        // The ignore path returns before the main-wiki check, so this stub is only used by the config-doc tests.
        lenient().when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
    }

    @Test
    void onEventInvalidatesAllServersForMainWikiConfigDoc()
    {
        DocumentReference configRef = new DocumentReference(MAIN_WIKI,
            Arrays.asList("AI", "MCP", "Code"), "MCPServerConfig");
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getDocumentReference()).thenReturn(configRef);

        this.listener.onEvent(new DocumentUpdatedEvent(configRef), doc, null);

        // The main-wiki config carries the farm-level reach grant, so a main-wiki save invalidates all servers.
        verify(this.mcpServerManager).invalidateAll();
        // The space-filter cache is dropped with the same granularity.
        verify(this.spaceFilter).invalidateAll();
    }

    @Test
    void onEventInvalidatesSavedWikiForSubWikiConfigDoc()
    {
        DocumentReference configRef = new DocumentReference(SUB_WIKI,
            Arrays.asList("AI", "MCP", "Code"), "MCPServerConfig");
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getDocumentReference()).thenReturn(configRef);

        this.listener.onEvent(new DocumentUpdatedEvent(configRef), doc, null);

        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        verify(this.spaceFilter).invalidate(SUB_WIKI);
    }

    @Test
    void onEventIgnoresOtherDocuments()
    {
        DocumentReference otherRef = new DocumentReference(MAIN_WIKI,
            Arrays.asList("Some", "Other"), "Page");
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getDocumentReference()).thenReturn(otherRef);

        this.listener.onEvent(new DocumentUpdatedEvent(otherRef), doc, null);

        verifyNoInteractions(this.mcpServerManager);
        verifyNoInteractions(this.spaceFilter);
    }

    @Test
    void onEventFallsBackToSourceDocumentReference()
    {
        DocumentReference configRef = new DocumentReference(MAIN_WIKI,
            Arrays.asList("AI", "MCP", "Code"), "MCPServerConfig");
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getDocumentReference()).thenReturn(configRef);

        this.listener.onEvent(new DocumentUpdatedEvent(), doc, null);

        // The source document reference is the main-wiki config, so the fallback path invalidates all servers.
        verify(this.mcpServerManager).invalidateAll();
        verify(this.spaceFilter).invalidateAll();
    }

    @Test
    void onEventInvalidatesOnConfigDocDeletion()
    {
        DocumentReference configRef = new DocumentReference(SUB_WIKI,
            Arrays.asList("AI", "MCP", "Code"), "MCPServerConfig");
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getDocumentReference()).thenReturn(configRef);

        this.listener.onEvent(new DocumentDeletedEvent(configRef), doc, null);

        // Deleting the config document must drop the cached server and filter state so the defaults take
        // effect, not the last saved configuration.
        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        verify(this.spaceFilter).invalidate(SUB_WIKI);
    }

    @Test
    void listenerRegistersCreatedUpdatedAndDeletedEvents()
    {
        // The deletion registration is load-bearing: without it a removed config document would leave the
        // cached state serving the deleted configuration until a restart.
        org.junit.jupiter.api.Assertions.assertEquals(3, this.listener.getEvents().size());
    }

    @Test
    void listenerNameIsStable()
    {
        // Guard against accidental renames that would break existing listener registrations.
        org.junit.jupiter.api.Assertions.assertEquals(
            "org.xwiki.contrib.llm.mcp.internal.MCPConfigChangeEventListener",
            MCPConfigChangeEventListener.NAME);
    }
}
