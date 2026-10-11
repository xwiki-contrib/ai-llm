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
package org.xwiki.contrib.llm.mcp.internal.server;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.contrib.llm.mcp.internal.access.MCPSpaceFilter;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private static final DocumentReference AUTHOR = new DocumentReference(SUB_WIKI, "XWiki", "Mallory");

    private static final DocumentReference DELETER = new DocumentReference(MAIN_WIKI, "XWiki", "Admin");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPConfigChangeEventListener listener;

    @MockComponent
    private XWikiMCPServerManager mcpServerManager;

    @MockComponent
    private MCPSpaceFilter spaceFilter;

    @MockComponent
    private MCPServerConfiguration mcpConfig;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private MCPConfigPageAuthorship authorship;

    @MockComponent
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @BeforeEach
    void setUp()
    {
        // The ignore path returns before the main-wiki check, so this stub is only used by the config-doc tests.
        lenient().when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        // Events originate on this node unless a test says otherwise.
        lenient().when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(false);
        // A saved page is trusted unless a test says otherwise, so the invalidation tests log nothing.
        lenient().when(this.mcpConfig.isConfigurationTrusted(anyString())).thenReturn(true);
        // The administrator is trusted on the wiki, Mallory is not.
        lenient().when(this.authorship.isTrusted(DELETER, SUB_WIKI)).thenReturn(true);
    }

    private DocumentReference configReference(String wikiId)
    {
        return new DocumentReference(wikiId, Arrays.asList("AI", "MCP", "Code"), "MCPServerConfig");
    }

    private DocumentModelBridge configDocument(String wikiId)
    {
        DocumentModelBridge doc = mock(DocumentModelBridge.class);
        when(doc.getDocumentReference()).thenReturn(configReference(wikiId));
        return doc;
    }

    /**
     * @return the configuration page of the wiki as the platform sends it (a document with a locale and objects)
     */
    private XWikiDocument configXWikiDocument(String wikiId, Locale locale, DocumentReference... objectClasses)
    {
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getDocumentReference()).thenReturn(configReference(wikiId));
        when(doc.getLocale()).thenReturn(locale);
        Map<DocumentReference, List<BaseObject>> objects = new HashMap<>();
        for (DocumentReference objectClass : objectClasses) {
            objects.put(objectClass, List.of(mock(BaseObject.class)));
        }
        lenient().when(doc.getXObjects()).thenReturn(objects);
        return doc;
    }

    @Test
    void onEventInvalidatesAllServersForMainWikiConfigDoc()
    {
        DocumentModelBridge doc = configDocument(MAIN_WIKI);

        this.listener.onEvent(new DocumentUpdatedEvent(doc.getDocumentReference()), doc, null);

        // The main-wiki config carries the farm-level reach grant, so a main-wiki save invalidates all servers.
        verify(this.mcpServerManager).invalidateAll();
        // The space-filter cache is dropped with the same granularity.
        verify(this.spaceFilter).invalidateAll();
    }

    @Test
    void onEventInvalidatesSavedWikiForSubWikiConfigDoc()
    {
        DocumentModelBridge doc = configDocument(SUB_WIKI);

        this.listener.onEvent(new DocumentUpdatedEvent(doc.getDocumentReference()), doc, null);

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
        verifyNoInteractions(this.mcpConfig);
    }

    @Test
    void onEventUsesTheSourceDocumentReferenceNotTheEventReference()
    {
        DocumentModelBridge doc = configDocument(MAIN_WIKI);

        // The event carries no reference; the source document is the main-wiki config, which is what counts.
        this.listener.onEvent(new DocumentUpdatedEvent(), doc, null);

        verify(this.mcpServerManager).invalidateAll();
        verify(this.spaceFilter).invalidateAll();
    }

    @Test
    void onEventInvalidatesAndLogsANonAdminDeleterOnConfigDocDeletion()
    {
        // The platform stamps the deleting user as the author of the blank source document of a deletion.
        XWikiDocument doc = configXWikiDocument(SUB_WIKI, Locale.ROOT);
        when(this.authorship.authorOf(doc)).thenReturn(AUTHOR);

        this.listener.onEvent(new DocumentDeletedEvent(doc.getDocumentReference()), doc, null);

        // Deleting the config document must drop the cached server and filter state so the defaults take
        // effect, not the last saved configuration.
        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        verify(this.spaceFilter).invalidate(SUB_WIKI);
        // A deleted page is not checked for trust (there is nothing left to trust); its deleter is.
        verify(this.mcpConfig, never()).isConfigurationTrusted(anyString());
        verify(this.authorship).isTrusted(AUTHOR, SUB_WIKI);
        assertEquals("The MCP configuration page of wiki [subwikiX] was deleted by [subwikiX:XWiki.Mallory], who has "
            + "no admin right on the wiki; the wiki now uses the default MCP configuration",
            this.logCapture.getMessage(0));
    }

    @Test
    void onEventInvalidatesButLogsNothingWhenAnAdministratorDeletesTheConfigDoc()
    {
        XWikiDocument doc = configXWikiDocument(SUB_WIKI, Locale.ROOT);
        when(this.authorship.authorOf(doc)).thenReturn(DELETER);

        this.listener.onEvent(new DocumentDeletedEvent(doc.getDocumentReference()), doc, null);

        // An administrator deleting (or replacing) the page is a legitimate action: invalidation only.
        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        verify(this.spaceFilter).invalidate(SUB_WIKI);
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void onEventLogsAnUnknownDeleterWhenTheSourceRecordsNoAuthor()
    {
        XWikiDocument doc = configXWikiDocument(SUB_WIKI, Locale.ROOT);
        when(this.authorship.authorOf(doc)).thenReturn(null);

        this.listener.onEvent(new DocumentDeletedEvent(doc.getDocumentReference()), doc, null);

        verify(this.authorship).isTrusted(null, SUB_WIKI);
        assertEquals("The MCP configuration page of wiki [subwikiX] was deleted by [an unknown user]; the wiki "
            + "now uses the default MCP configuration", this.logCapture.getMessage(0));
    }

    @Test
    void onEventLogsAnUnknownDeleterWhenTheSourceIsNotAnXWikiDocument()
    {
        DocumentModelBridge doc = configDocument(SUB_WIKI);

        this.listener.onEvent(new DocumentDeletedEvent(doc.getDocumentReference()), doc, null);

        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        verify(this.authorship, never()).authorOf(any());
        assertEquals("The MCP configuration page of wiki [subwikiX] was deleted by [an unknown user]; the wiki "
            + "now uses the default MCP configuration", this.logCapture.getMessage(0));
    }

    @Test
    void onEventLogsAnUntrustedSaveNamingTheWikiAndTheAuthor()
    {
        DocumentModelBridge doc = configDocument(SUB_WIKI);
        when(this.mcpConfig.isConfigurationTrusted(SUB_WIKI)).thenReturn(false);
        when(this.mcpConfig.getConfigurationAuthor(SUB_WIKI)).thenReturn(AUTHOR);

        this.listener.onEvent(new DocumentUpdatedEvent(doc.getDocumentReference()), doc, null);

        // The cache is still invalidated: the endpoint must stop serving the previously trusted configuration.
        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        assertEquals("The MCP configuration page of wiki [subwikiX] was saved by [subwikiX:XWiki.Mallory], who "
            + "has no admin right on the wiki; the endpoint of the wiki is disabled until an administrator resets "
            + "the configuration", this.logCapture.getMessage(0));
    }

    @Test
    void onEventLogsAnUntrustedCreationNamingTheWikiAndTheAuthor()
    {
        DocumentModelBridge doc = configDocument(SUB_WIKI);
        when(this.mcpConfig.isConfigurationTrusted(SUB_WIKI)).thenReturn(false);
        when(this.mcpConfig.getConfigurationAuthor(SUB_WIKI)).thenReturn(AUTHOR);

        this.listener.onEvent(new DocumentCreatedEvent(doc.getDocumentReference()), doc, null);

        // A page created by a non-admin is as untrusted as one they updated: same invalidation, same warning.
        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        verify(this.spaceFilter).invalidate(SUB_WIKI);
        assertEquals("The MCP configuration page of wiki [subwikiX] was saved by [subwikiX:XWiki.Mallory], who "
            + "has no admin right on the wiki; the endpoint of the wiki is disabled until an administrator resets "
            + "the configuration", this.logCapture.getMessage(0));
    }

    @Test
    void onEventLogsAnUntrustedSaveWithoutAuthor()
    {
        DocumentModelBridge doc = configDocument(SUB_WIKI);
        when(this.mcpConfig.isConfigurationTrusted(SUB_WIKI)).thenReturn(false);
        when(this.mcpConfig.getConfigurationAuthor(SUB_WIKI)).thenReturn(null);

        this.listener.onEvent(new DocumentUpdatedEvent(doc.getDocumentReference()), doc, null);

        assertEquals("The MCP configuration page of wiki [subwikiX] was saved without a recorded author; the "
            + "endpoint of the wiki is disabled until an administrator resets the configuration",
            this.logCapture.getMessage(0));
    }

    @Test
    void onEventInvalidatesButLogsNothingForAnEventReplayedFromAnotherNode()
    {
        DocumentModelBridge doc = configDocument(SUB_WIKI);
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(true);
        lenient().when(this.mcpConfig.isConfigurationTrusted(SUB_WIKI)).thenReturn(false);

        this.listener.onEvent(new DocumentUpdatedEvent(doc.getDocumentReference()), doc, null);
        this.listener.onEvent(new DocumentDeletedEvent(doc.getDocumentReference()), doc, null);

        // The cache invalidation is global (every node keeps its own cache); the audit trail is written once,
        // on the node where the save or deletion happened.
        verify(this.mcpServerManager, times(2)).invalidate(SUB_WIKI);
        verify(this.spaceFilter, times(2)).invalidate(SUB_WIKI);
        verifyNoInteractions(this.mcpConfig);
        verifyNoInteractions(this.authorship);
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void onEventLogsNothingForATrustedSave()
    {
        DocumentModelBridge doc = configDocument(SUB_WIKI);

        this.listener.onEvent(new DocumentUpdatedEvent(doc.getDocumentReference()), doc, null);

        verify(this.mcpConfig).isConfigurationTrusted(SUB_WIKI);
        verify(this.mcpConfig, never()).getConfigurationAuthor(anyString());
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void onEventIgnoresATranslationOfTheConfigDoc()
    {
        XWikiDocument french = configXWikiDocument(SUB_WIKI, Locale.FRENCH);

        // The translations of an untrusted page are deleted before the replacement page is saved; those deletions
        // carry no configuration change of their own.
        this.listener.onEvent(new DocumentDeletedEvent(french.getDocumentReference()), french, null);
        this.listener.onEvent(new DocumentUpdatedEvent(french.getDocumentReference()), french, null);

        verifyNoInteractions(this.mcpServerManager);
        verifyNoInteractions(this.spaceFilter);
        verifyNoInteractions(this.mcpConfig);
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void onEventIgnoresTheDeletionOfATranslationOfTheConfigDoc()
    {
        // The platform sends a blank root-locale document as the source of a deletion, with the deleted
        // document (here the French translation) as its original document.
        XWikiDocument french = configXWikiDocument(SUB_WIKI, Locale.FRENCH);
        XWikiDocument blank = configXWikiDocument(SUB_WIKI, Locale.ROOT);
        when(blank.getOriginalDocument()).thenReturn(french);

        this.listener.onEvent(new DocumentDeletedEvent(blank.getDocumentReference()), blank, null);

        verifyNoInteractions(this.mcpServerManager);
        verifyNoInteractions(this.spaceFilter);
        verifyNoInteractions(this.mcpConfig);
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void onEventLogsTheDeleterForTheDeletionOfTheDefaultLocaleConfigDoc()
    {
        XWikiDocument deleted = configXWikiDocument(SUB_WIKI, Locale.ROOT);
        XWikiDocument blank = configXWikiDocument(SUB_WIKI, Locale.ROOT);
        when(blank.getOriginalDocument()).thenReturn(deleted);
        when(this.authorship.authorOf(blank)).thenReturn(AUTHOR);

        this.listener.onEvent(new DocumentDeletedEvent(blank.getDocumentReference()), blank, null);

        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        assertEquals("The MCP configuration page of wiki [subwikiX] was deleted by [subwikiX:XWiki.Mallory], who has "
            + "no admin right on the wiki; the wiki now uses the default MCP configuration",
            this.logCapture.getMessage(0));
    }

    @Test
    void onEventLogsTheObjectsOfClassesTheExtensionDoesNotPutOnThePage()
    {
        XWikiDocument doc = configXWikiDocument(SUB_WIKI, Locale.ROOT,
            new DocumentReference(SUB_WIKI, Arrays.asList("AI", "MCP", "Code"), "MCPServerConfigClass"),
            new DocumentReference(SUB_WIKI, "XWiki", "ConfigurableClass"),
            new DocumentReference(SUB_WIKI, "XWiki", "JavaScriptExtension"));

        this.listener.onEvent(new DocumentUpdatedEvent(doc.getDocumentReference()), doc, null);

        // An audit line, not a trust decision: the page is still invalidated and read like any other save.
        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        verify(this.mcpConfig).isConfigurationTrusted(SUB_WIKI);
        assertEquals("The MCP configuration page of wiki [subwikiX] carries objects of classes "
            + "[subwikiX:XWiki.JavaScriptExtension] that the extension does not put on it",
            this.logCapture.getMessage(0));
    }

    @Test
    void onEventLogsNothingForTheObjectsTheExtensionPutsOnThePage()
    {
        XWikiDocument doc = configXWikiDocument(SUB_WIKI, Locale.ROOT,
            new DocumentReference(SUB_WIKI, Arrays.asList("AI", "MCP", "Code"), "MCPServerConfigClass"),
            new DocumentReference(SUB_WIKI, "XWiki", "ConfigurableClass"));

        this.listener.onEvent(new DocumentCreatedEvent(doc.getDocumentReference()), doc, null);

        verify(this.mcpServerManager).invalidate(SUB_WIKI);
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void listenerRegistersCreatedUpdatedAndDeletedEvents()
    {
        // The deletion registration is load-bearing: without it a removed config document would leave the
        // cached state serving the deleted configuration until a restart.
        assertEquals(3, this.listener.getEvents().size());
    }

    @Test
    void listenerNameIsStable()
    {
        // Guard against accidental renames that would break existing listener registrations.
        assertEquals("org.xwiki.contrib.llm.mcp.internal.MCPConfigChangeEventListener",
            MCPConfigChangeEventListener.NAME);
    }
}
