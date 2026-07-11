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
package org.xwiki.contrib.llm.mcp.internal.tool;

import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPDeleteObjectTool}, against the real oldcore store so the removal semantics (nulled
 * slot, stable numbers of the remaining objects) are exercised end to end.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPDeleteObjectToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String CLASS_KEY = "class";

    private static final String OBJECT_KEY = "object";

    private static final String BASE_VERSION_KEY = "base_version";

    private static final String COMMENT_KEY = "comment";

    private static final String REF = "Blog.MyPost";

    private static final String CLASS_REF = "Blog.CommentClass";

    private static final String CANONICAL = "xwiki:Blog.MyPost";

    private static final String TEXT_FIELD = "text";

    private static final String VIEW_URL = "https://wiki.example/bin/view/Blog/MyPost";

    private static final DocumentReference DOC_REFERENCE = new DocumentReference("xwiki", "Blog", "MyPost");

    private static final DocumentReference CLASS_REFERENCE =
        new DocumentReference("xwiki", "Blog", "CommentClass");

    private static final DocumentReference WEBPREFS_REFERENCE =
        new DocumentReference("xwiki", "Blog", "WebPreferences");

    private static final DocumentReference RIGHTS_CLASS_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "XWikiRights");

    private static final DocumentReference USER_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "Author");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPDeleteObjectTool tool;

    @MockComponent
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private MCPWikiReach wikiReach;

    @BeforeEach
    void setUp(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(DOC_REFERENCE);
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenReturn(CLASS_REFERENCE);
        lenient().when(this.serializer.serialize(any())).thenReturn(CANONICAL);
        lenient().when(this.localSerializer.serialize(CLASS_REFERENCE)).thenReturn(CLASS_REF);
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
        lenient().when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        // Take the simple save path in api.Document.save (skip the saveAsAuthor branch).
        oldcore.getConfigurationSource().setProperty("security.script.save.checkAuthor", false);
        when(oldcore.getMockRightService().hasAccessLevel(any(), any(), any(), any())).thenReturn(true);

        // XWikiContext.setUserReference() resolves the legacy string user via the "compactwiki" serializer.
        if (!oldcore.getMocker().hasComponent(EntityReferenceSerializer.TYPE_STRING, "compactwiki")) {
            oldcore.getMocker().registerMockComponent(EntityReferenceSerializer.TYPE_STRING, "compactwiki");
        }
        oldcore.getXWikiContext().setUserReference(USER_REFERENCE);

        // removeXObject resolves the object's relative class reference through the "current" resolver,
        // which the oldcore fixture does not provide; the tests use a single class, so a fixed answer
        // reproduces the platform resolution.
        DocumentReferenceResolver<EntityReference> currentResolver = oldcore.getMocker()
            .registerMockComponent(DocumentReferenceResolver.TYPE_REFERENCE, "current");
        lenient().when(currentResolver.resolve(any(EntityReference.class), any()))
            .thenReturn(CLASS_REFERENCE);
    }

    /**
     * Stores the class definition and a document carrying two objects of it ({@code first} at number 0,
     * {@code second} at number 1).
     */
    private void storeDocumentWithTwoObjects(MockitoOldcore oldcore) throws Exception
    {
        XWikiDocument classDoc = new XWikiDocument(CLASS_REFERENCE);
        classDoc.getXClass().addTextField(TEXT_FIELD, "Text", 30);
        oldcore.getSpyXWiki().saveDocument(classDoc, oldcore.getXWikiContext());

        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setContent("body");
        BaseObject first = doc.newXObject(CLASS_REFERENCE, oldcore.getXWikiContext());
        first.setStringValue(TEXT_FIELD, "first");
        BaseObject second = doc.newXObject(CLASS_REFERENCE, oldcore.getXWikiContext());
        second.setStringValue(TEXT_FIELD, "second");
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    private XWikiDocument loadDocument(MockitoOldcore oldcore) throws Exception
    {
        return oldcore.getSpyXWiki().getDocument(DOC_REFERENCE, oldcore.getXWikiContext());
    }

    private String currentVersion(MockitoOldcore oldcore) throws Exception
    {
        return loadDocument(oldcore).getVersion();
    }

    private static String textOf(McpSchema.CallToolResult result)
    {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private McpSchema.CallToolResult call(Map<String, Object> args)
    {
        return this.tool.execute(
            McpSchema.CallToolRequest.builder(MCPDeleteObjectTool.TOOL_ID).arguments(args).build());
    }

    @Test
    void deletesTheObjectAndTheOthersKeepTheirNumbers(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithTwoObjects(oldcore);
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, BASE_VERSION_KEY, versionBefore));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        // The removed object's slot is nulled; the second object keeps number 1.
        assertNull(saved.getXObject(CLASS_REFERENCE, 0));
        BaseObject second = saved.getXObject(CLASS_REFERENCE, 1);
        assertNotNull(second);
        assertEquals("second", second.getStringValue(TEXT_FIELD));
        assertEquals(1, second.getNumber());
        assertEquals("[AI] deleted Blog.CommentClass object 0", saved.getComment());
        // The removal is saved as a minor edit of the document.
        verify(oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class),
            eq("[AI] deleted Blog.CommentClass object 0"), eq(true), any());

        String text = textOf(result);
        assertTrue(text.contains("Deleted Blog.CommentClass object 0 from document " + CANONICAL), text);
        assertTrue(text.contains("Version: " + versionBefore + " -> " + saved.getVersion()), text);
        assertTrue(text.contains("Compare: " + VIEW_URL), text);
        assertTrue(text.contains("history"), text);
        assertTrue(text.contains("reverted"), text);
    }

    @Test
    void agentCommentIsPrefixedOnTheRemovalSave(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithTwoObjects(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 1, BASE_VERSION_KEY, currentVersion(oldcore), COMMENT_KEY, "remove spam comment"));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("[AI] remove spam comment", loadDocument(oldcore).getComment());
    }

    @Test
    void missingBaseVersionReturnsRequiredError() throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(BASE_VERSION_KEY), text);
        assertTrue(text.contains("required"), text);
        verify(this.documentAccess, never()).resolveAndAuthorize(anyString(), any());
    }

    @Test
    void missingObjectNumberReturnsRequiredError() throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(OBJECT_KEY), text);
        assertTrue(text.contains("required"), text);
        verify(this.documentAccess, never()).resolveAndAuthorize(anyString(), any());
    }

    @Test
    void staleBaseVersionRefusedAndObjectSurvives(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithTwoObjects(oldcore);
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Version conflict"), text);
        assertTrue(text.contains(versionBefore), text);
        assertNotNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void nonexistentDocumentRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("does not exist"), text);
        assertTrue(text.contains("no object to delete"), text);
    }

    @Test
    void noObjectAtNumberRefusedListingExistingNumbers(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithTwoObjects(oldcore);
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 7, BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No object of class \"Blog.CommentClass\" at number 7"), text);
        assertTrue(text.contains("Existing object numbers: 0, 1"), text);
        assertEquals(versionBefore, currentVersion(oldcore));
    }

    @Test
    void sensitiveDocumentRefusedRegardlessOfVersion(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenReturn(WEBPREFS_REFERENCE);
        XWikiDocument doc = new XWikiDocument(WEBPREFS_REFERENCE);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());

        // The base_version is deliberately stale: the denylist fires before the version comparison.
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "Blog.WebPreferences",
            CLASS_KEY, CLASS_REF, OBJECT_KEY, 0, BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("defines access rights or wiki configuration"), text);
        assertTrue(text.contains(VIEW_URL), text);
        assertFalse(text.contains("Version conflict"), text);
    }

    @Test
    void sensitiveClassRefusedAndNothingSaved(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithTwoObjects(oldcore);
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenReturn(RIGHTS_CLASS_REFERENCE);
        when(this.localSerializer.serialize(RIGHTS_CLASS_REFERENCE)).thenReturn("XWiki.XWikiRights");
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, "XWiki.XWikiRights",
            OBJECT_KEY, 0, BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("objects of this class define access rights, group membership, "
            + "user accounts or wiki configuration"), text);
        assertEquals(versionBefore, currentVersion(oldcore));
    }

    @Test
    void doorDenialSurfacesMessageWithoutSaving(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to edit \"" + REF + "\"."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"), textOf(result));
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }

    @Test
    void storageFailureReturnsFixedMessageAndLogsRootCause(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithTwoObjects(oldcore);
        String versionBefore = currentVersion(oldcore);
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_UNKNOWN, "db down"))
            .when(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        // The fixed message reaches the wire; the storage internals stay in the server logs.
        assertTrue(text.contains("Could not save the document"), text);
        assertFalse(text.contains("db down"), text);
        assertTrue(this.logCapture.getMessage(0).contains("db down"), this.logCapture.getMessage(0));
        // The removal happened on the tool's clone, so the stored object 0 survives the failed save.
        assertNotNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void toolDefinitionRequiresReferenceClassObjectAndBaseVersion()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();

        assertEquals(MCPDeleteObjectTool.TOOL_ID, definition.name());
        List<?> required = (List<?>) definition.inputSchema().get("required");
        assertTrue(required.contains(REFERENCE_KEY), required.toString());
        assertTrue(required.contains(CLASS_KEY), required.toString());
        assertTrue(required.contains(OBJECT_KEY), required.toString());
        assertTrue(required.contains(BASE_VERSION_KEY), required.toString());
    }

    @Test
    void reachOffDropsCrossWikiFromAdvertisedSchema()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertFalse(definition.inputSchema().toString().contains("xwiki:"),
            "Reach-off advertised schema must not contain wiki-prefixed examples");
    }

    @Test
    void isWriteAndCatalogMetadataAreSet()
    {
        assertTrue(this.tool.isWrite());
        assertEquals("Structured Data", this.tool.getCategory());
        assertTrue(this.tool.getSummary().contains("Remove"), this.tool.getSummary());
        assertTrue(this.tool.getManPage().contains("EXAMPLES"), this.tool.getManPage());
        assertTrue(this.tool.getManPage().contains("reverted"), this.tool.getManPage());
    }
}
