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

import javax.inject.Named;

import org.junit.jupiter.api.Test;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

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

    @InjectMockComponents
    private MCPConfigChangeEventListener listener;

    @MockComponent
    private XWikiMCPServerManager mcpServerManager;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localReferenceSerializer;

    @Test
    void processLocalEventTriggersRebuildForConfigDoc()
    {
        DocumentReference configRef = new DocumentReference(MAIN_WIKI,
            Arrays.asList("AI", "MCP", "Code"), "MCPServerConfig");
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getDocumentReference()).thenReturn(configRef);
        when(this.localReferenceSerializer.serialize(configRef)).thenReturn("AI.MCP.Code.MCPServerConfig");

        this.listener.processLocalEvent(new DocumentUpdatedEvent(), doc, null);

        verify(this.mcpServerManager).rebuildServer();
    }

    @Test
    void processLocalEventIgnoresOtherDocuments()
    {
        DocumentReference otherRef = new DocumentReference(MAIN_WIKI,
            Arrays.asList("Some", "Other"), "Page");
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getDocumentReference()).thenReturn(otherRef);
        when(this.localReferenceSerializer.serialize(otherRef)).thenReturn("Some.Other.Page");

        this.listener.processLocalEvent(new DocumentUpdatedEvent(), doc, null);

        verifyNoInteractions(this.mcpServerManager);
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
