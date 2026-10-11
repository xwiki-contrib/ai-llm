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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.refactoring.batch.BatchOperation;
import org.xwiki.refactoring.batch.BatchOperationExecutor;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the write path of {@link MCPServerConfiguration} against the in-memory document store of the oldcore
 * fixture, where real documents are saved, deleted and read back: what a write leaves in the store when the
 * configuration page is untrusted. The fixture stubs {@code XWiki#deleteDocument} to remove the document from its
 * store; {@code XWiki#deleteAllDocuments} runs for real on top of it, inside a pass-through batch executor.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class MCPServerConfigurationReplacementTest
{
    private static final String WIKI = "xwiki";

    private static final String FIELD_ENABLED = "enabled";

    private static final DocumentReference CONFIG_REFERENCE =
        new DocumentReference(WIKI, List.of("AI", "MCP", "Code"), "MCPServerConfig");

    private static final DocumentReference CLASS_REFERENCE =
        new DocumentReference(WIKI, List.of("AI", "MCP", "Code"), "MCPServerConfigClass");

    /** A class the extension does not put on the page: stands for a skin extension the untrusted author added. */
    private static final DocumentReference FOREIGN_CLASS_REFERENCE =
        new DocumentReference(WIKI, "XWiki", "JavaScriptExtension");

    private static final DocumentReference MALLORY = new DocumentReference(WIKI, "XWiki", "Mallory");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPServerConfiguration configuration;

    @MockComponent
    private MCPConfigPageAuthorship authorship;

    @MockComponent
    private MCPConfigPageTemplate template;

    private MockitoOldcore oldcore;

    private XWikiContext context;

    @BeforeEach
    void setUp(MockitoOldcore mockitoOldcore) throws Exception
    {
        this.oldcore = mockitoOldcore;
        this.context = mockitoOldcore.getXWikiContext();

        // XWiki#deleteAllDocuments wraps the deletes in a batch; the fixture registers no executor, so a
        // pass-through mock runs the operation inline.
        BatchOperationExecutor batchOperationExecutor =
            mockitoOldcore.getMocker().registerMockComponent(BatchOperationExecutor.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, BatchOperation.class).execute();
            return null;
        }).when(batchOperationExecutor).execute(any());

        // The stored page was saved by a non-admin and carries their title, content, an extra object and an
        // attachment...
        XWikiDocument stored = new XWikiDocument(CONFIG_REFERENCE);
        stored.setTitle("Pwned");
        stored.setContent("{{velocity}}$services.foo{{/velocity}}");
        stored.newXObject(FOREIGN_CLASS_REFERENCE, this.context).setStringValue("code", "alert(1)");
        stored.newXObject(CLASS_REFERENCE, this.context).setIntValue(FIELD_ENABLED, 1);
        stored.setAttachment("evil.js", new ByteArrayInputStream("alert(1)".getBytes(StandardCharsets.UTF_8)),
            this.context);
        this.oldcore.getSpyXWiki().saveDocument(stored, this.context);
        // ... and a translation, which a save of the default-locale page leaves alone.
        XWikiDocument french = new XWikiDocument(CONFIG_REFERENCE, Locale.FRENCH);
        french.setTitle("Pwned (fr)");
        this.oldcore.getSpyXWiki().saveDocument(french, this.context);
        assertEquals(List.of(Locale.FRENCH), storedPage().getTranslationLocales(this.context));

        when(this.authorship.authorOf(any())).thenReturn(MALLORY);
        when(this.authorship.isTrusted(MALLORY, WIKI)).thenReturn(false);
    }

    private XWikiDocument shippedPage()
    {
        XWikiDocument shipped = new XWikiDocument(CONFIG_REFERENCE);
        shipped.setTitle("MCP Server Configuration");
        shipped.setHidden(true);
        try {
            shipped.newXObject(CLASS_REFERENCE, this.context).setIntValue(FIELD_ENABLED, 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return shipped;
    }

    private XWikiDocument storedPage() throws Exception
    {
        return this.oldcore.getSpyXWiki().getDocument(CONFIG_REFERENCE, this.context);
    }

    @Test
    void resetConfigurationReplacesAnUntrustedPageWithTheShippedPage() throws Exception
    {
        XWikiDocument shipped = shippedPage();
        when(this.template.shippedPage(WIKI, this.context)).thenReturn(shipped);

        assertTrue(this.configuration.resetConfiguration(WIKI));

        XWikiDocument saved = storedPage();
        // Nothing of the untrusted page survives: the shipped page replaces it entirely.
        assertEquals("MCP Server Configuration", saved.getTitle());
        assertEquals("", saved.getContent());
        assertTrue(saved.isHidden());
        assertTrue(saved.getXObjects(FOREIGN_CLASS_REFERENCE).isEmpty());
        assertTrue(saved.getAttachmentList().isEmpty());
        assertTrue(saved.getTranslationLocales(this.context).isEmpty());
        // The reset leaves a fresh empty configuration object, not the shipped object's values.
        BaseObject object = saved.getXObject(CLASS_REFERENCE);
        assertNotNull(object);
        assertNull(object.getField(FIELD_ENABLED));
        verify(this.authorship).takeOwnership(shipped, this.context);
        verify(this.authorship).stamp(shipped, this.context);
    }

    @Test
    void resetConfigurationReplacesAnUntrustedPageWithAnEmptyPageWhenTheShippedPageIsUnavailable()
        throws Exception
    {
        when(this.template.shippedPage(WIKI, this.context)).thenReturn(null);

        assertTrue(this.configuration.resetConfiguration(WIKI));

        XWikiDocument saved = storedPage();
        assertEquals("", saved.getTitle());
        assertEquals("", saved.getContent());
        assertTrue(saved.isHidden());
        assertTrue(saved.getXObjects(FOREIGN_CLASS_REFERENCE).isEmpty());
        assertTrue(saved.getAttachmentList().isEmpty());
        assertTrue(saved.getTranslationLocales(this.context).isEmpty());
        BaseObject object = saved.getXObject(CLASS_REFERENCE);
        assertNotNull(object);
        assertNull(object.getField(FIELD_ENABLED));
        verify(this.authorship).takeOwnership(any(XWikiDocument.class), any());
        assertEquals("The MCP configuration page of wiki [xwiki] could not be restored from the extension package; "
            + "it is replaced by an empty page", this.logCapture.getMessage(0));
    }

    @Test
    void setEnabledReplacesAnUntrustedPageAndWritesOnTheShippedObject() throws Exception
    {
        XWikiDocument shipped = shippedPage();
        when(this.template.shippedPage(WIKI, this.context)).thenReturn(shipped);

        assertTrue(this.configuration.setEnabled(WIKI, false));

        XWikiDocument saved = storedPage();
        assertEquals("MCP Server Configuration", saved.getTitle());
        assertTrue(saved.getXObjects(FOREIGN_CLASS_REFERENCE).isEmpty());
        assertTrue(saved.getAttachmentList().isEmpty());
        assertTrue(saved.getTranslationLocales(this.context).isEmpty());
        // The administrator's value lands on the shipped object.
        assertEquals(0, saved.getXObject(CLASS_REFERENCE).getIntValue(FIELD_ENABLED));
    }

    @Test
    void resetConfigurationKeepsEverythingButTheConfigurationObjectsOfATrustedPage() throws Exception
    {
        when(this.authorship.isTrusted(MALLORY, WIKI)).thenReturn(true);

        assertTrue(this.configuration.resetConfiguration(WIKI));

        XWikiDocument saved = storedPage();
        // A trusted page is an administrator's page: the reset drops the settings and nothing else.
        assertEquals("Pwned", saved.getTitle());
        assertFalse(saved.getXObjects(FOREIGN_CLASS_REFERENCE).isEmpty());
        assertEquals(1, saved.getAttachmentList().size());
        assertEquals(List.of(Locale.FRENCH), saved.getTranslationLocales(this.context));
        assertNull(saved.getXObject(CLASS_REFERENCE).getField(FIELD_ENABLED));
        verify(this.template, never()).shippedPage(any(), any());
        verify(this.authorship, never()).takeOwnership(any(), any());
    }
}
