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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPEditDocumentTool}.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPEditDocumentToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String EDITS_KEY = "edits";

    private static final String TITLE_KEY = "title";

    private static final String OLD_STRING = "old_string";

    private static final String NEW_STRING = "new_string";

    private static final String REPLACE_ALL = "replace_all";

    private static final String REF = "Sandbox.WebHome";

    private static final String CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String COMPARE_URL = "https://wiki.example/bin/view/Sandbox/WebHome?viewer=changes";

    private static final String VIEW_URL = "https://wiki.example/bin/view/Sandbox/WebHome";

    private static final DocumentReference DOC_REFERENCE =
        new DocumentReference("xwiki", "Sandbox", "WebHome");

    private static final DocumentReference USER_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "Author");

    @InjectMockComponents
    private MCPEditDocumentTool tool;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @MockComponent
    private ContextualAuthorizationManager authorization;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @BeforeEach
    void setUp(MockitoOldcore oldcore) throws Exception
    {
        when(this.referenceResolver.resolve(anyString())).thenReturn(DOC_REFERENCE);
        when(this.authorization.hasAccess(eq(Right.EDIT), any())).thenReturn(true);
        when(this.serializer.serialize(any())).thenReturn(CANONICAL);

        // Take the simple save path in api.Document.save (skip the saveAsAuthor branch).
        oldcore.getConfigurationSource().setProperty("security.script.save.checkAuthor", false);
        when(oldcore.getMockRightService().hasAccessLevel(any(), any(), any(), any())).thenReturn(true);

        // XWikiContext.setUserReference() resolves the legacy string user via the "compactwiki" serializer.
        if (!oldcore.getMocker().hasComponent(EntityReferenceSerializer.TYPE_STRING, "compactwiki")) {
            oldcore.getMocker().registerMockComponent(EntityReferenceSerializer.TYPE_STRING, "compactwiki");
        }
        oldcore.getXWikiContext().setUserReference(USER_REFERENCE);
    }

    private void storeDocument(MockitoOldcore oldcore, String content, String title) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setContent(content);
        if (title != null) {
            doc.setTitle(title);
        }
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    private XWikiDocument loadDocument(MockitoOldcore oldcore) throws Exception
    {
        return oldcore.getSpyXWiki().getDocument(DOC_REFERENCE, oldcore.getXWikiContext());
    }

    private static String textOf(McpSchema.CallToolResult result)
    {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private McpSchema.CallToolResult call(Map<String, Object> args)
    {
        return this.tool.execute(new McpSchema.CallToolRequest(MCPEditDocumentTool.TOOL_ID, args));
    }

    private static Map<String, Object> edit(String oldString, String newString)
    {
        return Map.of(OLD_STRING, oldString, NEW_STRING, newString);
    }

    @Test
    void editAppliesAndSavesSingleRevisionWithAiCommentAndAuthor(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "alpha\nbeta\ngamma", "Old Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("beta", "BETA"))));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("alpha\nBETA\ngamma", saved.getContent());
        assertEquals("[AI] 1 edit", saved.getComment());
        assertEquals(USER_REFERENCE, saved.getAuthorReference());

        String text = textOf(result);
        assertTrue(text.contains("Updated document " + CANONICAL), text);
        assertTrue(text.contains("Compare: " + COMPARE_URL), text);
        assertTrue(text.contains("1 edit(s) applied."), text);
    }

    @Test
    void notFoundOldStringErrorsWithReReadHintAndDoesNotSave(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "alpha\nbeta", "Title");
        String versionBefore = loadDocument(oldcore).getVersion();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("missing", "x"))));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("old_string not found"), text);
        assertTrue(text.contains("re-read"), text);
        assertEquals("alpha\nbeta", loadDocument(oldcore).getContent());
        assertEquals(versionBefore, loadDocument(oldcore).getVersion());
    }

    @Test
    void ambiguousMatchErrorsAndDoesNotSave(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "foo\nfoo\nbar", "Title");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("foo", "baz"))));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("found 2 times"), textOf(result));
        assertEquals("foo\nfoo\nbar", loadDocument(oldcore).getContent());
    }

    @Test
    void replaceAllReplacesEveryOccurrence(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "foo foo foo", "Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, EDITS_KEY,
            List.of(Map.of(OLD_STRING, "foo", NEW_STRING, "bar", REPLACE_ALL, true))));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("bar bar bar", loadDocument(oldcore).getContent());
    }

    @Test
    void createPathCreatesDocumentWithDefaultSyntax(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, TITLE_KEY, "Hello", EDITS_KEY,
            List.of(edit("", "= Hello =\n\nBody."))));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertFalse(saved.isNew());
        assertEquals("= Hello =\n\nBody.", saved.getContent());
        assertEquals("Hello", saved.getTitle());
        // The tool never sets the syntax, so a created document keeps the wiki default syntax that the spy
        // assigns to a freshly loaded, not-yet-saved document of another reference.
        XWikiDocument freshDoc = oldcore.getSpyXWiki().getDocument(
            new DocumentReference("xwiki", "Sandbox", "Other"), oldcore.getXWikiContext());
        assertEquals(freshDoc.getSyntax(), saved.getSyntax());
        assertEquals("[AI] Created document", saved.getComment());
        // A creation is a normal (major) save, not a minor edit.
        verify(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), eq("[AI] Created document"), eq(false), any());

        String text = textOf(result);
        assertTrue(text.contains("Created document " + CANONICAL), text);
        assertTrue(text.contains("View: " + VIEW_URL), text);
    }

    @Test
    void createWithNonEmptyOldStringErrors(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("something", "x"))));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("does not exist"), textOf(result));
        assertTrue(loadDocument(oldcore).isNew());
    }

    @Test
    void retitleOnlySavesNewTitle(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "body stays", "Old Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, TITLE_KEY, "New Title"));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("New Title", saved.getTitle());
        assertEquals("body stays", saved.getContent());
        assertTrue(textOf(result).contains("Title updated."), textOf(result));
    }

    @Test
    void noOpDoesNotSaveAndReportsNoChanges(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "unchanged", "Same Title");
        String versionBefore = loadDocument(oldcore).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, EDITS_KEY,
            List.of(edit("unchanged", "unchanged")), TITLE_KEY, "Same Title"));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("No changes"), textOf(result));
        assertEquals(versionBefore, loadDocument(oldcore).getVersion());
    }

    @Test
    void notAuthorizedErrorsWithoutLeakingExistenceOrLoading(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "secret content", "Secret");
        when(this.authorization.hasAccess(eq(Right.EDIT), any())).thenReturn(false);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("secret", "x"))));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Not authorized"), text);
        assertFalse(text.contains("secret content"), text);
        // The document was not modified.
        assertEquals("secret content", loadDocument(oldcore).getContent());
    }

    @Test
    void notAuthorizedDoesNotSave(MockitoOldcore oldcore) throws Exception
    {
        when(this.authorization.hasAccess(eq(Right.EDIT), any())).thenReturn(false);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("a", "b"))));

        assertEquals(Boolean.TRUE, result.isError());
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }

    @Test
    void createWithMultipleEditsRejected(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, EDITS_KEY,
            List.of(edit("", "body one"), edit("", "body two"))));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("at most one edit"), textOf(result));
        assertTrue(loadDocument(oldcore).isNew());
    }

    @Test
    void tooManyEditsRejected()
    {
        List<Map<String, Object>> manyEdits = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            manyEdits.add(edit("a" + i, "b" + i));
        }

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, manyEdits));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("too many edits"), textOf(result));
    }

    @Test
    void multipleEditsAppliedSequentiallyInOneRevision(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "one\ntwo\nthree", "Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);
        String versionBefore = loadDocument(oldcore).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, EDITS_KEY,
            List.of(edit("one", "1"), edit("three", "3"))));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("1\ntwo\n3", saved.getContent());
        // A single revision step was taken (default increment is one minor version).
        assertNotEquals(versionBefore, saved.getVersion());
        assertEquals("[AI] 2 edits", saved.getComment());
    }

    @Test
    void missingReferenceReturnsError()
    {
        McpSchema.CallToolResult result = call(Map.of(EDITS_KEY, List.of(edit("a", "b"))));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains(REFERENCE_KEY), textOf(result));
    }

    @Test
    void emptyEditsAndNoTitleReturnsError(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "content", "Title");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("at least one edit or a title"), textOf(result));
        verify(this.referenceResolver, never()).resolve(anyString());
    }

    @Test
    void editsNotAnArrayReturnsError()
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, "not an array"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("must be an array"), textOf(result));
    }

    @Test
    void editMissingNewStringReturnsError()
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(Map.of(OLD_STRING, "a"))));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("new_string"), textOf(result));
    }

    @Test
    void crlfOldStringMatchesNormalizedSource(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "line1\nline2", "Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, EDITS_KEY,
            List.of(edit("line1\r\nline2", "replaced"))));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("replaced", loadDocument(oldcore).getContent());
    }

    @Test
    void successResultEchoesContextAroundChange(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "a\nb\nc\nd\ne\nf\ng", "Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("d", "DDD"))));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("@@ edit 1 @@"), text);
        assertTrue(text.contains("DDD"), text);
    }

    @Test
    void saveCommentMatchesSavedDocument(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "x", "Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("x", "y")), TITLE_KEY, "Retitled"));

        // The tool's save call carries the [AI] comment (storeDocument's earlier save used an empty comment)
        // and is a minor edit because the document already existed.
        verify(oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class), eq("[AI] 1 edit, retitled"),
            eq(true), any());
        assertEquals("[AI] 1 edit, retitled", loadDocument(oldcore).getComment());
    }

    @Test
    void replaceAllReportsReplacementCount(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "foo a foo b foo", "Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, EDITS_KEY,
            List.of(Map.of(OLD_STRING, "foo", NEW_STRING, "bar", REPLACE_ALL, true))));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("1 edit(s) applied (3 replacements)."), text);
        assertTrue(text.contains("@@ edit 1 (3 replacements, showing the first) @@"), text);
    }

    @Test
    void singleReplacementDoesNotAnnotateCount(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "alpha\nbeta", "Title");
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, EDITS_KEY, List.of(edit("beta", "BETA"))));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("1 edit(s) applied."), text);
        assertFalse(text.contains("replacements"), text);
    }

    @Test
    void baseVersionMatchAllowsSave(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "alpha\nbeta", "Title");
        String currentVersion = loadDocument(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF,
            "base_version", currentVersion, EDITS_KEY, List.of(edit("beta", "BETA"))));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("alpha\nBETA", loadDocument(oldcore).getContent());
    }

    @Test
    void baseVersionMismatchRefusesSaveWithConflictMessage(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, "alpha\nbeta", "Title");
        String currentVersion = loadDocument(oldcore).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF,
            "base_version", "1.0", EDITS_KEY, List.of(edit("beta", "BETA"))));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Version conflict"), text);
        assertTrue(text.contains(currentVersion), text);
        assertTrue(text.contains("re-apply"), text);
        assertEquals("alpha\nbeta", loadDocument(oldcore).getContent());
        assertEquals(currentVersion, loadDocument(oldcore).getVersion());
    }

    @Test
    void baseVersionOnCreateErrors(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF,
            "base_version", "1.1", EDITS_KEY, List.of(edit("", "new body"))));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("does not exist"), textOf(result));
        assertTrue(loadDocument(oldcore).isNew());
    }

    @Test
    void toolDefinitionRequiresReference()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertEquals(MCPEditDocumentTool.TOOL_ID, definition.name());
        assertTrue(definition.inputSchema().required().contains(REFERENCE_KEY));
    }

    @Test
    void isEnabledReturnsTrueByDefault()
    {
        assertTrue(this.tool.isEnabled());
    }

    @Test
    void summaryAndCategoryAreSet()
    {
        assertEquals("Authoring", this.tool.getCategory());
        assertTrue(this.tool.getManPage().contains("EXAMPLES"), this.tool.getManPage());
        assertTrue(this.tool.getSummary().contains("Create or edit"), this.tool.getSummary());
    }
}
