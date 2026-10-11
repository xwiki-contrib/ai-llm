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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptor;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.IntegerProperty;
import com.xpn.xwiki.objects.PropertyInterface;

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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /** The user who last saved the configuration page in the default (trusted) setup. */
    private static final DocumentReference ADMIN_REFERENCE = new DocumentReference(MAIN_WIKI, "XWiki", "Admin");

    /** The user acting in the context when a mutator saves the configuration page. */
    private static final DocumentReference USER_REFERENCE = new DocumentReference(MAIN_WIKI, "XWiki", "User");

    /** The version comment of a reset. */
    private static final String RESET_COMMENT = "Reset MCP configuration";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPServerConfiguration mcpServerConfiguration;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private ComponentManager componentManager;

    @MockComponent
    private MCPConfigPageAuthorship authorship;

    @MockComponent
    private MCPConfigPageTemplate template;

    @Mock
    private XWikiContext context;

    @Mock
    private XWiki xwiki;

    @Mock
    private XWikiDocument configDoc;

    @Mock
    private BaseObject configObject;

    @Mock
    private XWikiDocument clonedDoc;

    /** The page as the extension ships it, which replaces an untrusted page on a write. */
    @Mock
    private XWikiDocument shippedDoc;

    /** The page as read back from the store after a creating save. */
    @Mock
    private XWikiDocument reloadedDoc;

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
        // The configuration page exists and is trusted by default: it was last saved by a user with admin right
        // on both wikis. Tests exercising trust override the author or the trust decision explicitly.
        lenient().when(this.configDoc.isNew()).thenReturn(false);
        lenient().when(this.authorship.authorOf(this.configDoc)).thenReturn(ADMIN_REFERENCE);
        lenient().when(this.authorship.isTrusted(ADMIN_REFERENCE, MAIN_WIKI)).thenReturn(true);
        lenient().when(this.authorship.isTrusted(ADMIN_REFERENCE, SUB_WIKI)).thenReturn(true);
        // The mutators work on a clone of a trusted page, or on the shipped page when the page is untrusted.
        lenient().when(this.configDoc.clone()).thenReturn(this.clonedDoc);
        lenient().when(this.template.shippedPage(anyString(), eq(this.context))).thenReturn(this.shippedDoc);
        // The shipped page is new; a mock read back after the save carries no objects, attachments or translations.
        lenient().when(this.shippedDoc.isNew()).thenReturn(true);
        lenient().when(this.shippedDoc.getDocumentReference()).thenReturn(configRef(SUB_WIKI));
        lenient().when(this.context.getUserReference()).thenReturn(USER_REFERENCE);
    }

    private void mockConfigDocument(String wikiId) throws Exception
    {
        when(this.xwiki.getDocument(eq(configRef(wikiId)), eq(this.context))).thenReturn(this.configDoc);
    }

    /**
     * Stubs the stored page for the first read and {@link #reloadedDoc} for the read-back after a creating save.
     */
    private void mockConfigDocumentThenReload(String wikiId) throws Exception
    {
        when(this.xwiki.getDocument(eq(configRef(wikiId)), eq(this.context)))
            .thenReturn(this.configDoc, this.reloadedDoc);
    }

    private DocumentReference configRef(String wikiId)
    {
        return new DocumentReference(wikiId, MCPServerConfiguration.CONFIG_SPACES,
            MCPServerConfiguration.CONFIG_DOC_NAME);
    }

    private DocumentReference classRef(String wikiId)
    {
        return new DocumentReference(wikiId, MCPServerConfiguration.CONFIG_SPACES,
            MCPServerConfiguration.CONFIG_CLASS_NAME);
    }

    private void untrustedAuthorOn(String wikiId)
    {
        when(this.authorship.isTrusted(ADMIN_REFERENCE, wikiId)).thenReturn(false);
    }

    /**
     * Verifies the stamp-and-save contract of a write on a trusted page: the clone (not the cached page) is
     * stamped with the acting user as author and creator and saved, and ownership is not taken over (the page
     * is an administrator's page already).
     */
    private void verifyClonedPageStamped() throws Exception
    {
        verify(this.authorship).stamp(this.clonedDoc, this.context);
        verify(this.authorship, never()).takeOwnership(any(), any());
        verify(this.xwiki, never()).saveDocument(eq(this.configDoc), anyString(), anyBoolean(), any());
        verify(this.xwiki, never()).deleteAllDocuments(any(), any());
    }

    /**
     * Verifies the full write contract on a trusted page: {@link #verifyClonedPageStamped()} plus the stored
     * objects of the clone being kept.
     */
    private void verifyClonedPageStampedAndObjectsKept() throws Exception
    {
        verifyClonedPageStamped();
        verify(this.clonedDoc, never()).removeXObjects(any(DocumentReference.class));
    }

    /**
     * Verifies the write contract on an untrusted page: the stored page is not copied or read, the shipped
     * page takes its place with the acting user as owner, the value is written on it, and it is stamped and
     * saved.
     */
    private void verifyUntrustedPageReplacedAndSaved(String wikiId) throws Exception
    {
        // The order is the contract: the untrusted page is deleted with its translations, ownership of the shipped
        // page is taken, the object is created or found and written to, the stamped shipped page is saved, then
        // it is read back and found clean.
        InOrder inOrder = inOrder(this.authorship, this.shippedDoc, this.xwiki);
        inOrder.verify(this.xwiki).deleteAllDocuments(this.configDoc, this.context);
        inOrder.verify(this.authorship).takeOwnership(this.shippedDoc, this.context);
        inOrder.verify(this.shippedDoc).getXObject(classRef(wikiId), true, this.context);
        inOrder.verify(this.authorship).stamp(this.shippedDoc, this.context);
        inOrder.verify(this.xwiki).saveDocument(eq(this.shippedDoc), anyString(), eq(true), eq(this.context));
        inOrder.verify(this.xwiki).getDocument(configRef(wikiId), this.context);
        // The platform's deletion handles the translations: none is deleted one by one, and the clean read-back
        // deletes nothing.
        verify(this.xwiki).deleteAllDocuments(any(), any());
        verify(this.xwiki, never()).deleteDocument(any(XWikiDocument.class), anyBoolean(), any());
        // Nothing of the untrusted page is reused: it is neither copied, read nor saved.
        verify(this.configDoc, never()).clone();
        verify(this.configDoc, never()).getXObject(any(DocumentReference.class));
        verify(this.xwiki, never()).saveDocument(eq(this.configDoc), anyString(), anyBoolean(), any());
        verify(this.xwiki, never()).saveDocument(eq(this.clonedDoc), anyString(), anyBoolean(), any());
    }

    @Test
    void getServerNameReturnsConfiguredValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SERVER_NAME)).thenReturn("My Wiki");

        assertEquals("My Wiki", this.mcpServerConfiguration.getServerName(SUB_WIKI));
        // The configured value wins: the pretty-name fallback is never consulted.
        verify(this.wikiDescriptorManager, never()).getById(SUB_WIKI);
    }

    @Test
    void getServerNameTrimsConfiguredValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SERVER_NAME)).thenReturn("  My Wiki ");

        assertEquals("My Wiki", this.mcpServerConfiguration.getServerName(SUB_WIKI));
    }

    @Test
    void getServerNameFallsBackToPrettyNameWhenNotConfigured() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SERVER_NAME)).thenReturn("   ");
        WikiDescriptor descriptor = mock(WikiDescriptor.class);
        when(descriptor.getPrettyName()).thenReturn("Pretty Sub Wiki");
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenReturn(descriptor);

        assertEquals("Pretty Sub Wiki", this.mcpServerConfiguration.getServerName(SUB_WIKI));
    }

    @Test
    void getServerNameFallsBackToDefaultWhenPrettyNameBlank() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);
        WikiDescriptor descriptor = mock(WikiDescriptor.class);
        when(descriptor.getPrettyName()).thenReturn("   ");
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenReturn(descriptor);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName(SUB_WIKI));
    }

    @Test
    void getServerNameFallsBackToDefaultWhenNoDescriptor() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName(SUB_WIKI));
    }

    @Test
    void getServerNameReturnsDefaultOnException() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));
        when(this.wikiDescriptorManager.getById(MAIN_WIKI)).thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName(MAIN_WIKI));
        assertEquals("Failed to read MCP server config field [serverName], using default value: "
            + "[XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(0));
    }

    @Test
    void getServerNameFallsBackWhenTheConfigurationIsNotTrusted() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        lenient().when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        lenient().when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SERVER_NAME))
            .thenReturn("Attacker Wiki");
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_NAME,
            this.mcpServerConfiguration.getServerName(SUB_WIKI));
        // Nothing is read from an untrusted page.
        verify(this.configDoc, never()).getXObject(classRef(SUB_WIKI));
    }

    @Test
    void getServerDescriptionReturnsConfiguredValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SERVER_DESCRIPTION))
            .thenReturn("My custom description");

        assertEquals("My custom description",
            this.mcpServerConfiguration.getServerDescription(SUB_WIKI));
    }

    @Test
    void getServerDescriptionReturnsDefaultWhenNotConfigured() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);

        assertEquals(MCPServerConfiguration.DEFAULT_SERVER_DESCRIPTION,
            this.mcpServerConfiguration.getServerDescription(SUB_WIKI));
    }

    @Test
    void getServerDescriptionReturnsDefaultWhenBlank() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SERVER_DESCRIPTION)).thenReturn("   ");

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
    void isEnabledIsTrueByDefaultWhenTheStoreAddedTheFieldWithoutValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        // The Hibernate store materializes every class property of a saved object: the flag is present but
        // carries no value, which must read as "never set", not as 0.
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(new IntegerProperty());
        lenient().when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(0);

        assertTrue(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
    }

    @Test
    void isRenderedContentAllowedIsTrueByDefaultWhenTheStoreAddedTheFieldWithoutValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_RENDERED_CONTENT))
            .thenReturn(new IntegerProperty());
        lenient().when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ALLOW_RENDERED_CONTENT))
            .thenReturn(0);

        assertTrue(this.mcpServerConfiguration.isRenderedContentAllowed(SUB_WIKI));
    }

    @Test
    void isGuestAccessAllowedIsFalseByDefaultWhenTheStoreAddedTheFieldWithoutValue() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_GUEST)).thenReturn(new IntegerProperty());

        assertFalse(this.mcpServerConfiguration.isGuestAccessAllowed(SUB_WIKI));
        verify(this.configObject, never()).getIntValue(MCPServerConfiguration.FIELD_ALLOW_GUEST);
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
    void isEnabledIsFalseWhenTheConfigurationAuthorIsNotAnAdmin() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        // The page explicitly enables the endpoint, but its author has no admin right on the wiki.
        lenient().when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        lenient().when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED))
            .thenReturn(mock(PropertyInterface.class));
        lenient().when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ENABLED)).thenReturn(1);

        assertFalse(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
        verify(this.configDoc, never()).getXObject(classRef(SUB_WIKI));
    }

    @Test
    void isEnabledIsTrueWhenTheConfigurationPageDoesNotExist() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.isNew()).thenReturn(true);

        assertTrue(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
        // A missing page has no author to check: the defaults apply without any trust decision.
        verify(this.authorship, never()).authorOf(any());
        verify(this.authorship, never()).isTrusted(any(), any());
    }

    @Test
    void isEnabledIsFalseWhenTheConfigurationPageHasNoAuthor() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.authorship.authorOf(this.configDoc)).thenReturn(null);

        // The trust decision is made on the missing author, and a missing author is not trusted.
        assertFalse(this.mcpServerConfiguration.isEnabled(SUB_WIKI));
        verify(this.authorship).isTrusted(null, SUB_WIKI);
        verify(this.configDoc, never()).getXObject(classRef(SUB_WIKI));
    }

    @Test
    void isConfigurationTrustedIsTrueAndTheAuthorIsReportedForAnAdminAuthor() throws Exception
    {
        mockConfigDocument(SUB_WIKI);

        assertTrue(this.mcpServerConfiguration.isConfigurationTrusted(SUB_WIKI));
        assertEquals(ADMIN_REFERENCE, this.mcpServerConfiguration.getConfigurationAuthor(SUB_WIKI));
    }

    @Test
    void isConfigurationTrustedIsFalseAndTheAuthorIsReportedForANonAdminAuthor() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);

        assertFalse(this.mcpServerConfiguration.isConfigurationTrusted(SUB_WIKI));
        // The author is still reported so the UI can name who saved the page.
        assertEquals(ADMIN_REFERENCE, this.mcpServerConfiguration.getConfigurationAuthor(SUB_WIKI));
    }

    @Test
    void isConfigurationTrustedIsTrueAndTheAuthorIsNullWhenThePageDoesNotExist() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.isNew()).thenReturn(true);

        assertTrue(this.mcpServerConfiguration.isConfigurationTrusted(SUB_WIKI));
        assertNull(this.mcpServerConfiguration.getConfigurationAuthor(SUB_WIKI));
    }

    @Test
    void isConfigurationTrustedIsFalseAndTheAuthorIsNullWhenTheReadFails() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        assertFalse(this.mcpServerConfiguration.isConfigurationTrusted(SUB_WIKI));
        assertNull(this.mcpServerConfiguration.getConfigurationAuthor(SUB_WIKI));
        assertEquals("Could not read the MCP configuration page of wiki [subwiki]; treating it as not trusted: "
            + "[XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(0));
    }

    @Test
    void resetConfigurationDropsTheObjectsOfATrustedPageAndSaves() throws Exception
    {
        mockConfigDocument(SUB_WIKI);

        assertTrue(this.mcpServerConfiguration.resetConfiguration(SUB_WIKI));

        // The reset drops every stored setting even when the page is trusted, leaves a fresh empty object behind
        // (so the admin form's first visit does not save again to create one), and stamps the page.
        InOrder inOrder = inOrder(this.clonedDoc, this.authorship, this.xwiki);
        inOrder.verify(this.clonedDoc).removeXObjects(classRef(SUB_WIKI));
        inOrder.verify(this.clonedDoc).getXObject(classRef(SUB_WIKI), true, this.context);
        inOrder.verify(this.authorship).stamp(this.clonedDoc, this.context);
        inOrder.verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq(RESET_COMMENT), eq(true), eq(this.context));
        verifyClonedPageStamped();
        verify(this.xwiki).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(),
            any(XWikiContext.class));
    }

    @Test
    void resetConfigurationReplacesAnUntrustedPageWithTheShippedPageAndSaves() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);

        assertTrue(this.mcpServerConfiguration.resetConfiguration(SUB_WIKI));

        // The shipped page replaces the untrusted one; the reset then drops the shipped object's values and
        // leaves a fresh empty object before the single save.
        InOrder inOrder = inOrder(this.shippedDoc, this.xwiki);
        inOrder.verify(this.shippedDoc).removeXObjects(classRef(SUB_WIKI));
        inOrder.verify(this.shippedDoc).getXObject(classRef(SUB_WIKI), true, this.context);
        inOrder.verify(this.xwiki).saveDocument(eq(this.shippedDoc), eq(RESET_COMMENT), eq(true), eq(this.context));
        verifyUntrustedPageReplacedAndSaved(SUB_WIKI);
        verify(this.xwiki).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(),
            any(XWikiContext.class));
    }

    @Test
    void resetConfigurationReplacesAnUntrustedPageWithoutReadingItsAuthorTwice() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);

        assertTrue(this.mcpServerConfiguration.resetConfiguration(SUB_WIKI));

        // One trust decision per write, made on the stored page before anything is prepared.
        verify(this.authorship).authorOf(this.configDoc);
        verify(this.authorship).isTrusted(ADMIN_REFERENCE, SUB_WIKI);
        verify(this.template).shippedPage(SUB_WIKI, this.context);
    }

    @Test
    void resetConfigurationReturnsFalseAndLogsWhenWriteThrows() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Save down"));

        assertFalse(this.mcpServerConfiguration.resetConfiguration(SUB_WIKI));
        assertEquals("Failed to reset the MCP configuration of wiki [subwiki]: "
            + "[XWikiException: Error number 0 in 0: Save down]", this.logCapture.getMessage(0));
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
    void isGuestAccessAllowedIsFalseByDefaultWhenFieldUnset() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_GUEST)).thenReturn(null);

        assertFalse(this.mcpServerConfiguration.isGuestAccessAllowed(SUB_WIKI));
    }

    @Test
    void isGuestAccessAllowedIsFalseByDefaultWhenNoXObject() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(null);

        assertFalse(this.mcpServerConfiguration.isGuestAccessAllowed(SUB_WIKI));
    }

    @Test
    void isGuestAccessAllowedIsTrueWhenExplicitOne() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_GUEST))
            .thenReturn(mock(PropertyInterface.class));
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ALLOW_GUEST)).thenReturn(1);

        assertTrue(this.mcpServerConfiguration.isGuestAccessAllowed(SUB_WIKI));
    }

    @Test
    void isGuestAccessAllowedIsFalseWhenExplicitZero() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_GUEST))
            .thenReturn(mock(PropertyInterface.class));
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ALLOW_GUEST)).thenReturn(0);

        assertFalse(this.mcpServerConfiguration.isGuestAccessAllowed(SUB_WIKI));
    }

    @Test
    void isGuestAccessAllowedFailsClosedWhenReadThrows() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Store down"));

        // Unlike the rendering capability, this flag guards who may reach the endpoint at all, so a read
        // glitch must keep refusing guest callers rather than open the endpoint up.
        assertFalse(this.mcpServerConfiguration.isGuestAccessAllowed(SUB_WIKI));
        assertEquals("Could not read the MCP allow-guest flag for wiki [subwiki]; refusing guest callers: "
            + "[XWikiException: Error number 0 in 0: Store down]", this.logCapture.getMessage(0));
    }

    @Test
    void isGuestAccessAllowedIsFalseWhenTheConfigurationIsNotTrusted() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        // The page explicitly allows guests, but its author has no admin right on the wiki.
        lenient().when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        lenient().when(this.configObject.getField(MCPServerConfiguration.FIELD_ALLOW_GUEST))
            .thenReturn(mock(PropertyInterface.class));
        lenient().when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_ALLOW_GUEST)).thenReturn(1);

        assertFalse(this.mcpServerConfiguration.isGuestAccessAllowed(SUB_WIKI));
        verify(this.configDoc, never()).getXObject(classRef(SUB_WIKI));
    }

    @Test
    void isCanonicalWikiIdIsTrueWhenDescriptorCarriesTheExactId() throws Exception
    {
        WikiDescriptor descriptor = mock(WikiDescriptor.class);
        when(descriptor.getId()).thenReturn(SUB_WIKI);
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenReturn(descriptor);

        assertTrue(this.mcpServerConfiguration.isCanonicalWikiId(SUB_WIKI));
    }

    @Test
    void isCanonicalWikiIdIsFalseForCaseVariantOfTheMainWikiId() throws Exception
    {
        // The descriptor lookup resolves a case variant to the main wiki, whose descriptor carries the
        // canonical id: the variant must be refused.
        WikiDescriptor descriptor = mock(WikiDescriptor.class);
        when(descriptor.getId()).thenReturn(MAIN_WIKI);
        when(this.wikiDescriptorManager.getById("XWIKI")).thenReturn(descriptor);

        assertFalse(this.mcpServerConfiguration.isCanonicalWikiId("XWIKI"));
    }

    @Test
    void isCanonicalWikiIdIsFalseForUnknownWiki() throws Exception
    {
        when(this.wikiDescriptorManager.getById("nowiki")).thenReturn(null);

        assertFalse(this.mcpServerConfiguration.isCanonicalWikiId("nowiki"));
    }

    @Test
    void isCanonicalWikiIdIsFalseForBlankIdWithoutLookup() throws Exception
    {
        assertFalse(this.mcpServerConfiguration.isCanonicalWikiId(null));
        assertFalse(this.mcpServerConfiguration.isCanonicalWikiId("  "));
        verify(this.wikiDescriptorManager, never()).getById(any());
    }

    @Test
    void isCanonicalWikiIdFailsClosedWhenLookupThrows() throws Exception
    {
        when(this.wikiDescriptorManager.getById(SUB_WIKI)).thenThrow(new WikiManagerException("Descriptors down"));

        assertFalse(this.mcpServerConfiguration.isCanonicalWikiId(SUB_WIKI));
        assertEquals("Could not verify the wiki id [subwiki]; treating it as not canonical: "
            + "[WikiManagerException: Descriptors down]", this.logCapture.getMessage(0));
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
    void isCrossWikiReachAllowedIsFalseForEveryWikiWhenTheMainConfigurationIsNotTrusted() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        untrustedAuthorOn(MAIN_WIKI);
        // The main list grants both wikis, but its author has no admin right on the main wiki.
        lenient().when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        lenient().when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(1);
        lenient().when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(List.of(MAIN_WIKI, SUB_WIKI));

        assertFalse(this.mcpServerConfiguration.isCrossWikiReachAllowed(SUB_WIKI));
        // The "main wiki only" default does not apply either: an untrusted list grants nothing to anyone.
        assertFalse(this.mcpServerConfiguration.isCrossWikiReachAllowed(MAIN_WIKI));
        verify(this.configDoc, never()).getXObject(classRef(MAIN_WIKI));
    }

    @Test
    void isCrossWikiReachAllowedIsTrueForASubWikiWhoseOwnPageIsNotTrustedWhenTheMainListGrantsIt()
        throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        // The subwiki's own page is untrusted (its author has no admin right there), but reach is a main-wiki
        // decision: the trusted main list is what grants it.
        untrustedAuthorOn(SUB_WIKI);
        when(this.configDoc.getXObject(classRef(MAIN_WIKI))).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(1);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(List.of(SUB_WIKI));

        assertTrue(this.mcpServerConfiguration.isCrossWikiReachAllowed(SUB_WIKI));
    }

    @Test
    void setCrossWikiReachAddsWikiToMainListAndSaves() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(new ArrayList<>());

        assertTrue(this.mcpServerConfiguration.setCrossWikiReach(SUB_WIKI, true));

        // The write targets a clone of the MAIN wiki's config document, adding the granted wiki id to its list
        // and stamping the acting user as author; the cached original is never touched.
        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS, List.of(SUB_WIKI),
            this.context);
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq("Updated MCP cross-wiki reach"),
            eq(true), eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void setCrossWikiReachRemovesWikiFromMainListAndSaves() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(new ArrayList<>(List.of(SUB_WIKI, "otherwiki")));

        assertTrue(this.mcpServerConfiguration.setCrossWikiReach(SUB_WIKI, false));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS, List.of("otherwiki"),
            this.context);
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq("Updated MCP cross-wiki reach"),
            eq(true), eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void setCrossWikiReachSkipsSaveWhenListUnchanged() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        // The wiki is already granted, so re-granting it is a no-op and nothing is written.
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(new ArrayList<>(List.of(SUB_WIKI)));

        assertTrue(this.mcpServerConfiguration.setCrossWikiReach(SUB_WIKI, true));

        verify(this.configObject, never()).set(anyString(), any(), any());
        verify(this.xwiki, never()).saveDocument(any(), anyString(), anyBoolean(), any());
        verify(this.authorship, never()).stamp(any(), any());
    }

    @Test
    void setCrossWikiReachReplacesAnUntrustedMainPageBeforeWriting() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        untrustedAuthorOn(MAIN_WIKI);
        when(this.shippedDoc.getDocumentReference()).thenReturn(configRef(MAIN_WIKI));
        when(this.shippedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getListValue(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS))
            .thenReturn(new ArrayList<>());

        assertTrue(this.mcpServerConfiguration.setCrossWikiReach(SUB_WIKI, true));

        verifyUntrustedPageReplacedAndSaved(MAIN_WIKI);
        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS, List.of(SUB_WIKI),
            this.context);
    }

    @Test
    void initializeReachDefaultsSeedsMainWikiAndFlagAndSaves() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(0);

        assertTrue(this.mcpServerConfiguration.initializeReachDefaults());

        // The default (main wiki reaches, others do not) is materialized into the list as an explicit value.
        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_ENABLED_WIKIS, List.of(MAIN_WIKI),
            this.context);
        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_INITIALIZED, 1, this.context);
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq("Initialized MCP cross-wiki reach defaults"),
            eq(true), eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void initializeReachDefaultsStampsTheAuthorsWithoutATrustCheckWhenThePageIsNew() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.configDoc.isNew()).thenReturn(true);
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(0);

        assertTrue(this.mcpServerConfiguration.initializeReachDefaults());

        // A page that does not exist yet has no author to check and nothing to replace.
        verify(this.authorship, never()).authorOf(any());
        verify(this.authorship, never()).isTrusted(any(), any());
        verify(this.template, never()).shippedPage(any(), any());
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), anyString(), eq(true), eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void initializeReachDefaultsReadsTheCreatedPageBackAndKeepsItWhenClean() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocumentThenReload(MAIN_WIKI);
        when(this.configDoc.isNew()).thenReturn(true);
        when(this.clonedDoc.isNew()).thenReturn(true);
        when(this.clonedDoc.getDocumentReference()).thenReturn(configRef(MAIN_WIKI));
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(0);

        assertTrue(this.mcpServerConfiguration.initializeReachDefaults());

        // A first creation is verified like a replacement: the page is read back once after the save.
        verify(this.xwiki, times(2)).getDocument(configRef(MAIN_WIKI), this.context);
        verify(this.reloadedDoc).getAttachmentList();
        verify(this.reloadedDoc).getTranslationLocales(this.context);
        verify(this.xwiki, never()).deleteAllDocuments(any(), any());
    }

    @Test
    void initializeReachDefaultsIsNoOpWhenAlreadyInitialized() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(1);

        assertTrue(this.mcpServerConfiguration.initializeReachDefaults());

        verify(this.configObject, never()).set(anyString(), any(), any());
        verify(this.xwiki, never()).saveDocument(any(), anyString(), anyBoolean(), any());
        verify(this.authorship, never()).stamp(any(), any());
    }

    @Test
    void initializeReachDefaultsReplacesAnUntrustedMainPageBeforeWriting() throws Exception
    {
        when(this.wikiDescriptorManager.getMainWikiId()).thenReturn(MAIN_WIKI);
        mockConfigDocument(MAIN_WIKI);
        untrustedAuthorOn(MAIN_WIKI);
        when(this.shippedDoc.getDocumentReference()).thenReturn(configRef(MAIN_WIKI));
        when(this.shippedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);
        // The untrusted page claims the list is initialized; the shipped page's object reports 0, so the defaults
        // are seeded. The mock stands for the shipped object here.
        when(this.configObject.getIntValue(MCPServerConfiguration.FIELD_REACH_INITIALIZED)).thenReturn(0);

        assertTrue(this.mcpServerConfiguration.initializeReachDefaults());

        verifyUntrustedPageReplacedAndSaved(MAIN_WIKI);
        verify(this.configObject).set(MCPServerConfiguration.FIELD_REACH_INITIALIZED, 1, this.context);
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
        mockConfigDocument(SUB_WIKI);
        when(this.clonedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);

        assertTrue(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED, 1, this.context);
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq("Updated MCP enabled flag"), eq(true),
            eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void setEnabledWritesZeroForDisable() throws Exception
    {
        mockConfigDocument(MAIN_WIKI);
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);

        assertTrue(this.mcpServerConfiguration.setEnabled(MAIN_WIKI, false));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED, 0, this.context);
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq("Updated MCP enabled flag"), eq(true),
            eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void setEnabledReplacesAnUntrustedPageBeforeWriting() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        when(this.shippedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);

        assertTrue(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));

        // The untrusted page is replaced first; the admin's flag lands on the shipped page's object.
        verifyUntrustedPageReplacedAndSaved(SUB_WIKI);
        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED, 1, this.context);
    }

    @Test
    void setEnabledReturnsFalseAndLogsWhenTheUntrustedPageCannotBeDeleted() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        doThrow(new XWikiException(0, 0, "Delete down")).when(this.xwiki)
            .deleteAllDocuments(this.configDoc, this.context);

        assertFalse(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));

        // Nothing is saved on top of a page that could not be removed.
        verify(this.template, never()).shippedPage(any(), any());
        verify(this.xwiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
        assertEquals("Failed to set the MCP enabled flag for wiki [subwiki]: "
            + "[XWikiException: Error number 0 in 0: Delete down]", this.logCapture.getMessage(0));
    }

    @Test
    void setEnabledKeepsTheReplacementWhenTheReadBackCarriesOnlyWhatWasSaved() throws Exception
    {
        mockConfigDocumentThenReload(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        when(this.shippedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.shippedDoc.getXObjects()).thenReturn(Map.of(classRef(SUB_WIKI), List.of(this.configObject)));
        when(this.reloadedDoc.getXObjects())
            .thenReturn(Map.of(classRef(SUB_WIKI), List.of(mock(BaseObject.class))));

        assertTrue(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));

        // The stored untrusted page was deleted before the replacement; the read-back page is kept.
        verify(this.xwiki).deleteAllDocuments(this.configDoc, this.context);
        verify(this.xwiki, never()).deleteAllDocuments(eq(this.reloadedDoc), any());
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void setEnabledDeletesTheReplacementAndFailsWhenTheReadBackCarriesAForeignObject() throws Exception
    {
        mockConfigDocumentThenReload(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        when(this.shippedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.shippedDoc.getXObjects()).thenReturn(Map.of(classRef(SUB_WIKI), List.of(this.configObject)));
        // A removed object leaves a null slot behind, which does not count; the object of another class does.
        List<BaseObject> removed = new ArrayList<>();
        removed.add(null);
        when(this.reloadedDoc.getXObjects()).thenReturn(Map.of(classRef(SUB_WIKI), removed,
            new DocumentReference(SUB_WIKI, "XWiki", "JavaScriptExtension"), List.of(mock(BaseObject.class))));

        assertFalse(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));

        // The page was changed concurrently: the replacement is deleted and the write reports failure.
        InOrder inOrder = inOrder(this.xwiki);
        inOrder.verify(this.xwiki).saveDocument(eq(this.shippedDoc), anyString(), eq(true), eq(this.context));
        inOrder.verify(this.xwiki).deleteAllDocuments(this.reloadedDoc, this.context);
        assertEquals("The MCP configuration page of wiki [subwiki] was changed concurrently with its replacement and "
            + "carries content that was not saved (more objects of classes [subwiki:XWiki.JavaScriptExtension] than "
            + "saved, [0] attachment(s), [0] translation(s)); the page is deleted and the wiki uses the default MCP "
            + "configuration", this.logCapture.getMessage(0));
        assertEquals("Failed to set the MCP enabled flag for wiki [subwiki]: [XWikiException: Error number 0 in 2: "
            + "The MCP configuration page of wiki [subwiki] was changed concurrently with its replacement]",
            this.logCapture.getMessage(1));
    }

    @Test
    void setEnabledDeletesTheReplacementAndFailsWhenTheReadBackCarriesASecondConfigurationObject()
        throws Exception
    {
        mockConfigDocumentThenReload(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        when(this.shippedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.shippedDoc.getXObjects()).thenReturn(Map.of(classRef(SUB_WIKI), List.of(this.configObject)));
        // Same class as the saved object, one more of them: it would carry the other author's values.
        when(this.reloadedDoc.getXObjects()).thenReturn(Map.of(classRef(SUB_WIKI),
            List.of(mock(BaseObject.class), mock(BaseObject.class))));

        assertFalse(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));

        verify(this.xwiki).deleteAllDocuments(this.reloadedDoc, this.context);
        assertEquals("The MCP configuration page of wiki [subwiki] was changed concurrently with its replacement and "
            + "carries content that was not saved (more objects of classes [subwiki:AI.MCP.Code.MCPServerConfigClass] "
            + "than saved, [0] attachment(s), [0] translation(s)); the page is deleted and the wiki uses the default "
            + "MCP configuration", this.logCapture.getMessage(0));
        assertEquals("Failed to set the MCP enabled flag for wiki [subwiki]: [XWikiException: Error number 0 in 2: "
            + "The MCP configuration page of wiki [subwiki] was changed concurrently with its replacement]",
            this.logCapture.getMessage(1));
    }

    @Test
    void setEnabledDeletesTheReplacementAndFailsWhenTheReadBackCarriesAnAttachmentOrATranslation()
        throws Exception
    {
        mockConfigDocumentThenReload(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        when(this.shippedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.reloadedDoc.getAttachmentList()).thenReturn(List.of(mock(XWikiAttachment.class)));
        when(this.reloadedDoc.getTranslationLocales(this.context)).thenReturn(List.of(Locale.FRENCH));

        assertFalse(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));

        verify(this.xwiki).deleteAllDocuments(this.reloadedDoc, this.context);
        assertEquals("The MCP configuration page of wiki [subwiki] was changed concurrently with its replacement and "
            + "carries content that was not saved (more objects of classes [] than saved, [1] attachment(s), [1] "
            + "translation(s)); the page is deleted and the wiki uses the default MCP configuration",
            this.logCapture.getMessage(0));
        assertEquals("Failed to set the MCP enabled flag for wiki [subwiki]: [XWikiException: Error number 0 in 2: "
            + "The MCP configuration page of wiki [subwiki] was changed concurrently with its replacement]",
            this.logCapture.getMessage(1));
    }

    @Test
    void setEnabledReturnsFalseAndLogsWhenWriteThrows() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Save down"));

        assertFalse(this.mcpServerConfiguration.setEnabled(SUB_WIKI, true));
        assertEquals("Failed to set the MCP enabled flag for wiki [subwiki]: "
            + "[XWikiException: Error number 0 in 0: Save down]", this.logCapture.getMessage(0));
    }

    @Test
    void setGuestAccessAllowedWritesOneForAllow() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.clonedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);

        assertTrue(this.mcpServerConfiguration.setGuestAccessAllowed(SUB_WIKI, true));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ALLOW_GUEST, 1, this.context);
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq("Updated MCP guest access flag"), eq(true),
            eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void setGuestAccessAllowedWritesZeroForRefuse() throws Exception
    {
        mockConfigDocument(MAIN_WIKI);
        when(this.clonedDoc.getXObject(classRef(MAIN_WIKI), true, this.context)).thenReturn(this.configObject);

        assertTrue(this.mcpServerConfiguration.setGuestAccessAllowed(MAIN_WIKI, false));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ALLOW_GUEST, 0, this.context);
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq("Updated MCP guest access flag"), eq(true),
            eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void setGuestAccessAllowedReplacesAnUntrustedPageBeforeWriting() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        when(this.shippedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);

        assertTrue(this.mcpServerConfiguration.setGuestAccessAllowed(SUB_WIKI, false));

        verifyUntrustedPageReplacedAndSaved(SUB_WIKI);
        verify(this.configObject).set(MCPServerConfiguration.FIELD_ALLOW_GUEST, 0, this.context);
    }

    @Test
    void setGuestAccessAllowedReturnsFalseAndLogsWhenWriteThrows() throws Exception
    {
        when(this.xwiki.getDocument(any(DocumentReference.class), eq(this.context)))
            .thenThrow(new XWikiException(0, 0, "Save down"));

        assertFalse(this.mcpServerConfiguration.setGuestAccessAllowed(SUB_WIKI, true));
        assertEquals("Failed to set the MCP allow-guest flag for wiki [subwiki]: "
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
    void getEnabledToolIdsAppliesTheDefaultPolicyWhenTheConfigurationIsNotTrusted() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        // The page explicitly enables the write tool, but its author has no admin right on the wiki.
        lenient().when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        lenient().when(this.configObject.getListValue(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(List.of("write_document"));
        mockToolMap();

        assertEquals(Set.of("query_documents", "man"),
            this.mcpServerConfiguration.getEnabledToolIds(SUB_WIKI));
        verify(this.configDoc, never()).getXObject(classRef(SUB_WIKI));
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
    void setEnabledToolIdsWritesListAndSaves() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.clonedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(mock(PropertyInterface.class));

        List<String> toolIds = List.of("man", "query_documents");
        assertTrue(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, toolIds));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED_TOOLS, toolIds, this.context);
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), eq("Updated MCP tool configuration"),
            eq(true), eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void setEnabledToolIdsStampsTheAuthorsWithoutATrustCheckWhenThePageIsNew() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.configDoc.isNew()).thenReturn(true);
        when(this.clonedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(mock(PropertyInterface.class));

        assertTrue(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, List.of("man")));

        verify(this.authorship, never()).authorOf(any());
        verify(this.authorship, never()).isTrusted(any(), any());
        verify(this.xwiki).saveDocument(eq(this.clonedDoc), anyString(), eq(true), eq(this.context));
        verifyClonedPageStampedAndObjectsKept();
    }

    @Test
    void setEnabledToolIdsReplacesAnUntrustedPageBeforeWriting() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        when(this.shippedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(mock(PropertyInterface.class));

        assertTrue(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, List.of("man")));

        verifyUntrustedPageReplacedAndSaved(SUB_WIKI);
        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED_TOOLS, List.of("man"), this.context);
    }

    @Test
    void setEnabledToolIdsWritesEmptyListForNull() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.clonedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        when(this.configObject.getField(MCPServerConfiguration.FIELD_ENABLED_TOOLS))
            .thenReturn(mock(PropertyInterface.class));

        assertTrue(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, null));

        verify(this.configObject).set(MCPServerConfiguration.FIELD_ENABLED_TOOLS, List.of(), this.context);
    }

    @Test
    void setEnabledToolIdsReturnsFalseAndWarnsWhenFieldAbsentFromClass() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        when(this.clonedDoc.getXObject(classRef(SUB_WIKI), true, this.context)).thenReturn(this.configObject);
        // No getField stub: the deployed config class lacks the enabledTools field, so set() silently no-ops.

        assertFalse(this.mcpServerConfiguration.setEnabledToolIds(SUB_WIKI, List.of("man")));

        verify(this.xwiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(),
            any(XWikiContext.class));
        verify(this.authorship, never()).stamp(any(), any());
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
    void getSpaceFilterModeIsNoneWhenTheConfigurationIsNotTrusted() throws Exception
    {
        mockConfigDocument(SUB_WIKI);
        untrustedAuthorOn(SUB_WIKI);
        lenient().when(this.configDoc.getXObject(classRef(SUB_WIKI))).thenReturn(this.configObject);
        lenient().when(this.configObject.getStringValue(MCPServerConfiguration.FIELD_SPACE_FILTER_MODE))
            .thenReturn("whitelist");

        // The endpoint of an untrusted wiki is off anyway; its filter settings are never read.
        assertEquals(MCPServerConfiguration.SPACE_FILTER_MODE_NONE,
            this.mcpServerConfiguration.getSpaceFilterMode(SUB_WIKI));
        verify(this.configDoc, never()).getXObject(classRef(SUB_WIKI));
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
