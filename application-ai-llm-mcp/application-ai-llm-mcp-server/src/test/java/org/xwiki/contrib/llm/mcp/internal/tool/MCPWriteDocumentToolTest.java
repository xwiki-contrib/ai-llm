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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPWriteDocumentTool}.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPWriteDocumentToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String CONTENT_KEY = "content";

    private static final String TITLE_KEY = "title";

    private static final String BASE_VERSION_KEY = "base_version";

    private static final String LOCALE_KEY = "locale";

    private static final String COMMENT_KEY = "comment";

    private static final String MAJOR_KEY = "major";

    private static final String REF = "Sandbox.WebHome";

    private static final String CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String COMPARE_URL = "https://wiki.example/bin/view/Sandbox/WebHome?viewer=changes";

    private static final String VIEW_URL = "https://wiki.example/bin/view/Sandbox/WebHome";

    private static final String OLD_BODY = "old body";

    private static final String NEW_BODY = "new body";

    private static final String TITLE = "Title";

    private static final DocumentReference DOC_REFERENCE =
        new DocumentReference("xwiki", "Sandbox", "WebHome");

    private static final DocumentReference USER_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "Author");

    private static final DocumentReference FR_REFERENCE = new DocumentReference(DOC_REFERENCE, Locale.FRENCH);

    private static final String FR_BODY = "corps";

    private static final String NEW_FR_BODY = "nouveau corps";

    @InjectMockComponents
    private MCPWriteDocumentTool tool;

    @MockComponent
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private MCPWikiReach wikiReach;

    @BeforeEach
    void setUp(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(DOC_REFERENCE);
        when(this.serializer.serialize(any())).thenReturn(CANONICAL);
        // By default the endpoint has cross-wiki reach, so the advertised reference description mentions it;
        // the reach-off test overrides this.
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);

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

    private void storeTranslation(MockitoOldcore oldcore, String content) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE, Locale.FRENCH);
        doc.setContent(content);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    private XWikiDocument loadTranslation(MockitoOldcore oldcore) throws Exception
    {
        return oldcore.getSpyXWiki().getDocument(FR_REFERENCE, oldcore.getXWikiContext());
    }

    private static String textOf(McpSchema.CallToolResult result)
    {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private McpSchema.CallToolResult call(Map<String, Object> args)
    {
        return this.tool.execute(
            McpSchema.CallToolRequest.builder(MCPWriteDocumentTool.TOOL_ID).arguments(args).build());
    }

    @Test
    void notAuthorizedErrorsWithoutLoadingOrSaving(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to edit \"" + REF + "\"."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"), textOf(result));
        // The denial happens before the document is even loaded, so existence is not leaked.
        verify(oldcore.getSpyXWiki(), never())
            .getDocument(eq(DOC_REFERENCE), any(XWikiContext.class));
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }

    @Test
    void createSavesContentWithDefaultSyntaxAsMajorVersion(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, "= Hello =\n\nBody."));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertFalse(saved.isNew());
        assertEquals("= Hello =\n\nBody.", saved.getContent());
        assertEquals("1.1", saved.getVersion());
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
        assertTrue(text.contains("Version: 1.1"), text);
        assertTrue(text.contains("Syntax: " + freshDoc.getSyntax().toIdString()), text);
        assertTrue(text.contains("View: " + VIEW_URL), text);
    }

    @Test
    void createWithTitleSetsTitle(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, "body", TITLE_KEY, "Hello"));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("Hello", saved.getTitle());
        assertTrue(textOf(result).contains("Title set."), textOf(result));
    }

    @Test
    void scriptMacroBodyGetsViewTimeNoteAndPlainBodyDoesNot(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult scripted =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, "{{Velocity}}$doc.title{{/velocity}}"));

        assertNotEquals(Boolean.TRUE, scripted.isError());
        assertTrue(textOf(scripted).contains("Note: the body contains script macros, so what users see is "
            + "generated at view time - check the result with get_document rendered=true."), textOf(scripted));

        String version = loadDocument(oldcore).getVersion();
        McpSchema.CallToolResult plain =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY, BASE_VERSION_KEY, version));

        assertNotEquals(Boolean.TRUE, plain.isError());
        assertFalse(textOf(plain).contains("generated at view time"), textOf(plain));
    }

    @Test
    void createWithBaseVersionErrorsAndDoesNotSave(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY, BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("does not exist"), text);
        assertTrue(text.contains("omit base_version"), text);
        // A state outcome, not a malformed call: the same call succeeds once the document exists.
        assertFalse(text.startsWith("Error:"), text);
        assertTrue(loadDocument(oldcore).isNew());
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }

    @Test
    void overwriteWithoutBaseVersionRefusesWithReadFirstHint(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        String currentVersion = loadDocument(oldcore).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("already exists"), text);
        assertTrue(text.contains("get_document"), text);
        assertTrue(text.contains("base_version"), text);
        // The current version is deliberately not echoed: the agent must read the document before
        // overwriting it, not retry blindly with the version from this message.
        assertFalse(text.contains(currentVersion), text);
        assertTrue(text.contains("edit_document"), text);
        assertEquals(OLD_BODY, loadDocument(oldcore).getContent());
        assertEquals(currentVersion, loadDocument(oldcore).getVersion());
    }

    @Test
    void overwriteWithWrongBaseVersionRefusesWithConflictMessage(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        String currentVersion = loadDocument(oldcore).getVersion();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY, BASE_VERSION_KEY, "1.0"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Version conflict"), text);
        assertTrue(text.contains(currentVersion), text);
        assertTrue(text.contains("retry"), text);
        assertEquals(OLD_BODY, loadDocument(oldcore).getContent());
        assertEquals(currentVersion, loadDocument(oldcore).getVersion());
    }

    @Test
    void overwriteWithCorrectBaseVersionReplacesContentAsMinorEdit(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        String currentVersion = loadDocument(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY, BASE_VERSION_KEY, currentVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals(NEW_BODY, saved.getContent());
        // The whole-body replacement of an existing document is a minor edit by default.
        verify(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), eq("[AI] Replaced content"), eq(true), any());
        assertEquals("[AI] Replaced content", saved.getComment());

        String text = textOf(result);
        assertTrue(text.contains("Overwrote document " + CANONICAL), text);
        assertTrue(text.contains(currentVersion + " -> " + saved.getVersion()), text);
        assertTrue(text.contains("Compare: " + COMPARE_URL), text);
    }

    @Test
    void overwriteWithTitleSetsTitle(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, "Old Title");
        String currentVersion = loadDocument(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, OLD_BODY,
            BASE_VERSION_KEY, currentVersion, TITLE_KEY, "New Title"));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("New Title", saved.getTitle());
        assertEquals(OLD_BODY, saved.getContent());
        assertTrue(textOf(result).contains("Title updated."), textOf(result));
    }

    @Test
    void agentCommentIsPrefixedAndSavedAsMinorEdit(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        String currentVersion = loadDocument(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY,
            BASE_VERSION_KEY, currentVersion, COMMENT_KEY, "rewrite the installation steps"));

        assertNotEquals(Boolean.TRUE, result.isError());
        verify(oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class),
            eq("[AI] rewrite the installation steps"), eq(true), any());
        assertEquals("[AI] rewrite the installation steps", loadDocument(oldcore).getComment());
    }

    @Test
    void longCommentIsTruncatedAndKeepsAiPrefix(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        String currentVersion = loadDocument(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY,
            BASE_VERSION_KEY, currentVersion, COMMENT_KEY, "c".repeat(2000)));

        assertNotEquals(Boolean.TRUE, result.isError());
        String savedComment = loadDocument(oldcore).getComment();
        assertTrue(savedComment.startsWith("[AI] c"), savedComment);
        assertEquals(1000, savedComment.length());
        assertTrue(savedComment.endsWith("..."), savedComment);
    }

    @Test
    void majorTrueOnOverwriteSavesMajorVersion(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        String currentVersion = loadDocument(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY,
            BASE_VERSION_KEY, currentVersion, MAJOR_KEY, true));

        assertNotEquals(Boolean.TRUE, result.isError());
        verify(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), eq("[AI] Replaced content"), eq(false), any());
    }

    @Test
    void crlfContentIsNormalizedToLf(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, "line1\r\nline2\rline3"));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("line1\nline2\nline3", loadDocument(oldcore).getContent());
    }

    @Test
    void contentTrailingNewlineIsPreservedVerbatim(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);
        String bodyWithTrailingNewline = "body\n";

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, bodyWithTrailingNewline));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The content argument is read raw, not through the trimming accessor: the trailing newline is
        // part of the document source and must survive the round-trip byte-for-byte.
        assertEquals(bodyWithTrailingNewline, loadDocument(oldcore).getContent());
    }

    @Test
    void noOpDoesNotSaveAndReportsNoChanges(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        String currentVersion = loadDocument(oldcore).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, OLD_BODY,
            BASE_VERSION_KEY, currentVersion, TITLE_KEY, TITLE));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("No changes"), textOf(result));
        assertEquals(currentVersion, loadDocument(oldcore).getVersion());
    }

    @Test
    void contentOverCapErrorsAndDoesNotSave(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, "x".repeat(1_000_001)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.startsWith("Error: "), text);
        assertTrue(text.contains("maximum size"), text);
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }

    @Test
    void missingContentReturnsError(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains(CONTENT_KEY), textOf(result));
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }

    @Test
    void toolDefinitionDeclaresParamsInImportanceOrder()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        Map<?, ?> properties = (Map<?, ?>) definition.inputSchema().get("properties");
        assertEquals(
            List.of(REFERENCE_KEY, CONTENT_KEY, TITLE_KEY, LOCALE_KEY, BASE_VERSION_KEY, COMMENT_KEY, MAJOR_KEY),
            List.copyOf(properties.keySet()));
    }

    @Test
    void toolDefinitionRequiresReferenceAndContent()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertEquals(MCPWriteDocumentTool.TOOL_ID, definition.name());
        List<?> required = (List<?>) definition.inputSchema().get("required");
        assertTrue(required.contains(REFERENCE_KEY), required.toString());
        assertTrue(required.contains(CONTENT_KEY), required.toString());
    }

    @Test
    void reachOnMentionsCrossWikiInReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);

        assertTrue(referenceDescription().contains("reaches another wiki (see list_wikis)"),
            referenceDescription());
        assertTrue(referenceDescription().contains("\"xwiki:Help.Foo\""), referenceDescription());
    }

    @Test
    void reachOffDropsCrossWikiFromReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        assertFalse(referenceDescription().contains("cross-wiki"), referenceDescription());
        // The examples must be prefix-free so an agent on a reach-off endpoint never tries a prefixed ref.
        assertTrue(referenceDescription().contains("\"Help.Foo\""), referenceDescription());
        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertFalse(definition.inputSchema().toString().contains("xwiki:"),
            "Reach-off advertised schema must not contain wiki-prefixed examples");
        assertFalse(definition.description().contains("xwiki:"), definition.description());
    }

    private String referenceDescription()
    {
        Map<?, ?> properties = (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");
        Map<?, ?> reference = (Map<?, ?>) properties.get(REFERENCE_KEY);
        return (String) reference.get("description");
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
        assertTrue(this.tool.getSummary().contains("replace"), this.tool.getSummary());
    }

    @Test
    void crossWikiSaveRunsInTargetWikiAndRestoresContextWiki(MockitoOldcore oldcore) throws Exception
    {
        DocumentReference otherRef = new DocumentReference("otherwiki", "Sandbox", "WebHome");
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(otherRef);
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        String originalWiki = oldcore.getXWikiContext().getWikiId();
        AtomicReference<String> wikiAtSave = new AtomicReference<>();
        // Record the context wiki at save time without persisting: oldcore only registers components for the
        // main wiki, so a real save under the switched "otherwiki" namespace cannot resolve its serializers.
        doAnswer(invocation -> {
            wikiAtSave.set(oldcore.getXWikiContext().getWikiId());
            return null;
        }).when(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "otherwiki:Sandbox.WebHome",
            CONTENT_KEY, NEW_BODY));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The save ran with the context wiki switched to the target wiki, so save-time rights and class
        // resolution apply in that wiki.
        assertEquals("otherwiki", wikiAtSave.get());
        // The original context wiki is restored once the save completes.
        assertEquals(originalWiki, oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void isWriteIsTrue()
    {
        assertTrue(this.tool.isWrite());
    }

    @Test
    void localeCreateSavesTranslationRowWithCopiedSyntaxAndLanguageUrl(MockitoOldcore oldcore) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setContent(OLD_BODY);
        doc.setSyntax(Syntax.XWIKI_2_0);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
        String defaultVersion = loadDocument(oldcore).getVersion();
        doReturn(true).when(oldcore.getSpyXWiki()).isMultiLingual(any());
        // Echo the query string so the language pin on the emitted URL is observable.
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenAnswer(invocation -> VIEW_URL + "?" + invocation.getArgument(2));

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr", CONTENT_KEY, FR_BODY));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument translation = loadTranslation(oldcore);
        assertFalse(translation.isNew());
        assertEquals(FR_BODY, translation.getContent());
        assertEquals("1.1", translation.getVersion());
        // The page's syntax is copied from the default document onto the new row.
        assertEquals(Syntax.XWIKI_2_0, translation.getSyntax());
        // A translation row never carries the default-locale stamp; the stamp belongs to default rows.
        assertEquals(Locale.ROOT, translation.getDefaultLocale());
        // The default document is untouched.
        XWikiDocument defaultDoc = loadDocument(oldcore);
        assertEquals(OLD_BODY, defaultDoc.getContent());
        assertEquals(defaultVersion, defaultDoc.getVersion());

        String text = textOf(result);
        assertTrue(text.contains("Created the fr translation of " + CANONICAL), text);
        assertTrue(text.contains("Version: 1.1"), text);
        assertTrue(text.contains("language=fr"), text);
    }

    @Test
    void localeCreateWithBaseVersionRefusedAndNothingSaved(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        doReturn(true).when(oldcore.getSpyXWiki()).isMultiLingual(any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr",
            CONTENT_KEY, FR_BODY, BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("The fr translation of \"" + REF + "\" does not exist"), text);
        assertTrue(text.contains("omit base_version when creating a translation"), text);
        assertTrue(loadTranslation(oldcore).isNew());
    }

    @Test
    void localeUpdateWithoutBaseVersionPointsToSameLocaleRead(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        storeTranslation(oldcore, FR_BODY);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr", CONTENT_KEY, NEW_FR_BODY));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("The fr translation of \"" + REF + "\" already exists"), text);
        // The agent must read the row with the SAME locale, so the version it fetches is the row's own.
        assertTrue(text.contains("get_document and locale=\"fr\""), text);
        assertEquals(FR_BODY, loadTranslation(oldcore).getContent());
    }

    @Test
    void localeUpdateWithStaleBaseVersionNamesTheTranslationAndItsVersions(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        storeTranslation(oldcore, FR_BODY);
        String rowVersion = loadTranslation(oldcore).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr",
            CONTENT_KEY, NEW_FR_BODY, BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Version conflict: the fr translation is now at version " + rowVersion
            + " but base_version is 9.9"), text);
        assertEquals(FR_BODY, loadTranslation(oldcore).getContent());
    }

    @Test
    void localeUpdateWithCorrectBaseVersionOverwritesTheRowOnly(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        storeTranslation(oldcore, FR_BODY);
        String defaultVersion = loadDocument(oldcore).getVersion();
        String rowVersion = loadTranslation(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenAnswer(invocation -> VIEW_URL + "?" + invocation.getArgument(2));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr",
            CONTENT_KEY, NEW_FR_BODY, BASE_VERSION_KEY, rowVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals(NEW_FR_BODY, loadTranslation(oldcore).getContent());
        assertEquals(OLD_BODY, loadDocument(oldcore).getContent());
        assertEquals(defaultVersion, loadDocument(oldcore).getVersion());
        String text = textOf(result);
        assertTrue(text.contains("Overwrote the fr translation of " + CANONICAL), text);
        // The compare URL pins the row, so the diff a reviewer opens is the fr row's.
        assertTrue(text.contains("rev1=" + rowVersion), text);
        assertTrue(text.contains("&language=fr"), text);
    }

    @Test
    void localeUpdateOfExistingRowSucceedsWithMultilingualOff(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        storeTranslation(oldcore, FR_BODY);
        // The multilingual gate only guards row CREATION; an existing row is editable regardless, so
        // this stub is never consulted (hence lenient).
        lenient().doReturn(false).when(oldcore.getSpyXWiki()).isMultiLingual(any());
        String rowVersion = loadTranslation(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr",
            CONTENT_KEY, NEW_FR_BODY, BASE_VERSION_KEY, rowVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals(NEW_FR_BODY, loadTranslation(oldcore).getContent());
    }

    @Test
    void localeCreateOnMonolingualWikiRefused(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        String defaultVersion = loadDocument(oldcore).getVersion();
        doReturn(false).when(oldcore.getSpyXWiki()).isMultiLingual(any());

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr", CONTENT_KEY, FR_BODY));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("not multilingual"), text);
        assertTrue(text.contains("enable multilingual support"), text);
        assertTrue(loadTranslation(oldcore).isNew());
        assertEquals(defaultVersion, loadDocument(oldcore).getVersion());
    }

    @Test
    void localeOnMissingDocumentRefusesWithCreateDefaultFirst(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr", CONTENT_KEY, FR_BODY));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("does not exist"), text);
        assertTrue(text.contains("Create the default language version first"), text);
        assertTrue(loadDocument(oldcore).isNew());
        assertTrue(loadTranslation(oldcore).isNew());
    }

    @Test
    void localeEqualToDefaultLanguageWritesTheDefaultRow(MockitoOldcore oldcore) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setContent(OLD_BODY);
        doc.setDefaultLocale(Locale.ENGLISH);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
        String currentVersion = loadDocument(oldcore).getVersion();
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), anyString(), any(), eq(true)))
            .thenReturn(COMPARE_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "en",
            CONTENT_KEY, NEW_BODY, BASE_VERSION_KEY, currentVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The default-language short-circuit: no "en" row is created next to the default row.
        assertTrue(oldcore.getSpyXWiki()
            .getDocument(new DocumentReference(DOC_REFERENCE, Locale.ENGLISH), oldcore.getXWikiContext())
            .isNew());
        assertEquals(NEW_BODY, loadDocument(oldcore).getContent());
        assertTrue(textOf(result).contains("Overwrote document " + CANONICAL), textOf(result));
    }

    @Test
    void invalidLocaleReturnsSharedTeachingMessage(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "french", CONTENT_KEY, NEW_BODY));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains(
            "Error: 'locale' is not a valid locale: \"french\". Use forms like \"fr\" or \"pt_BR\"."),
            textOf(result));
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }

    @Test
    void createStampsTheWikiDefaultLocaleOnTheNewDocument(MockitoOldcore oldcore) throws Exception
    {
        doReturn(Locale.GERMAN).when(oldcore.getSpyXWiki()).getDefaultLocale(any());
        when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CONTENT_KEY, NEW_BODY));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The wiki's default locale is stamped on the created document (the platform UI also stamps on
        // create, but persists its request-resolved locale preference instead of the wiki default).
        assertEquals(Locale.GERMAN, loadDocument(oldcore).getDefaultLocale());
    }

    @Test
    void localeNoOpNamesTheTranslationRow(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        storeTranslation(oldcore, FR_BODY);
        String rowVersion = loadTranslation(oldcore).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr",
            CONTENT_KEY, FR_BODY, BASE_VERSION_KEY, rowVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The no-op result names the row it compared against, not "the current document".
        assertTrue(textOf(result).contains(
            "No changes: the given content is identical to the current fr translation. Nothing was saved."),
            textOf(result));
        assertEquals(rowVersion, loadTranslation(oldcore).getVersion());
    }

    @Test
    void wikiDefaultLocaleOnRootDefaultLocaleDocWritesTheDefaultRowWithoutBogusEnRow(MockitoOldcore oldcore)
        throws Exception
    {
        // A programmatically created page (extension initializer, REST import, older tool) declares no
        // default locale: it stores ROOT. The shared predicate falls back to the WIKI default, so
        // locale="en" writes the default row instead of fabricating an "en" translation row next to it.
        storeDocument(oldcore, OLD_BODY, TITLE);
        assertEquals(Locale.ROOT, loadDocument(oldcore).getDefaultLocale());
        doReturn(Locale.ENGLISH).when(oldcore.getSpyXWiki()).getDefaultLocale(any());
        String currentVersion = loadDocument(oldcore).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "en",
            CONTENT_KEY, NEW_BODY, BASE_VERSION_KEY, currentVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        // No bogus "en" translation row exists next to the default row.
        assertTrue(oldcore.getSpyXWiki()
            .getDocument(new DocumentReference(DOC_REFERENCE, Locale.ENGLISH), oldcore.getXWikiContext())
            .isNew());
        assertEquals(NEW_BODY, loadDocument(oldcore).getContent());
        assertTrue(textOf(result).contains("Overwrote document " + CANONICAL), textOf(result));
    }

    @Test
    void wikiDefaultLocaleOnMissingPageCreatesTheDefaultDocument(MockitoOldcore oldcore) throws Exception
    {
        doReturn(Locale.GERMAN).when(oldcore.getSpyXWiki()).getDefaultLocale(any());

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "de", CONTENT_KEY, NEW_BODY));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument created = loadDocument(oldcore);
        // locale=<wiki default> on a missing page is a plain default-document creation (the creation
        // stamp applies), not a create-the-default-first refusal and not a translation row.
        assertFalse(created.isNew());
        assertEquals(NEW_BODY, created.getContent());
        assertEquals(Locale.GERMAN, created.getDefaultLocale());
        assertTrue(oldcore.getSpyXWiki()
            .getDocument(new DocumentReference(DOC_REFERENCE, Locale.GERMAN), oldcore.getXWikiContext())
            .isNew());
        assertTrue(textOf(result).contains("Created document " + CANONICAL), textOf(result));
    }

    @Test
    void nonDefaultLocaleOnRootDefaultLocaleDocStillWritesATranslationRow(MockitoOldcore oldcore)
        throws Exception
    {
        storeDocument(oldcore, OLD_BODY, TITLE);
        doReturn(Locale.ENGLISH).when(oldcore.getSpyXWiki()).getDefaultLocale(any());
        doReturn(true).when(oldcore.getSpyXWiki()).isMultiLingual(any());

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, LOCALE_KEY, "fr", CONTENT_KEY, FR_BODY));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The ROOT-defaultLocale fallback covers the wiki default only: "fr" on this English-default
        // wiki is still a translation write, leaving the default row untouched.
        XWikiDocument translation = loadTranslation(oldcore);
        assertFalse(translation.isNew());
        assertEquals(FR_BODY, translation.getContent());
        assertEquals(OLD_BODY, loadDocument(oldcore).getContent());
    }
}
