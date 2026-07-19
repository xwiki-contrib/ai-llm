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

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.query.QueryParameter;
import org.xwiki.refactoring.batch.BatchOperation;
import org.xwiki.refactoring.batch.BatchOperationExecutor;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPDeleteDocumentTool}.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPDeleteDocumentToolTest extends AbstractMCPWriteToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String BASE_VERSION_KEY = "base_version";

    private static final String TERMINAL_REF = "Sandbox.Page";

    private static final String WEBHOME_REF = "Sandbox.WebHome";

    private static final String TERMINAL_CANONICAL = "xwiki:Sandbox.Page";

    private static final String WEBHOME_CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String CHILDREN_XWQL = "where doc.fullName like :space and doc.fullName <> :fullName";

    private static final String COUNT_XWQL = "select count(doc.fullName) from Document doc " + CHILDREN_XWQL;

    private static final DocumentReference TERMINAL_REFERENCE =
        new DocumentReference("xwiki", "Sandbox", "Page");

    private static final DocumentReference WEBHOME_REFERENCE =
        new DocumentReference("xwiki", "Sandbox", "WebHome");

    private static final DocumentReference WEBPREFS_REFERENCE =
        new DocumentReference("xwiki", "Sandbox", "WebPreferences");

    private static final DocumentReference XWIKI_PREFS_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "XWikiPreferences");

    private static final DocumentReference DESCRIPTOR_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "XWikiServerFoo");

    private static final DocumentReference OTHER_DESCRIPTOR_REFERENCE =
        new DocumentReference("otherwiki", "XWiki", "XWikiServerNotes");

    private static final DocumentReference CONFIG_REFERENCE =
        new DocumentReference("xwiki", List.of("AI", "MCP", "Code"), "MCPServerConfig");

    private static final String VIEW_URL = "https://wiki.example/bin/view/Some/Page";

    private static final String SENSITIVE_MESSAGE_PART = "defines access rights or wiki configuration";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPDeleteDocumentTool tool;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @MockComponent
    private QueryManager queryManager;

    /**
     * The escaped-literal parameter of the last mocked children query, kept so tests can verify the
     * space-prefix bind.
     */
    private QueryParameter spaceParameter;

    @BeforeEach
    void setUp(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(TERMINAL_REFERENCE);
        lenient().when(this.serializer.serialize(TERMINAL_REFERENCE)).thenReturn(TERMINAL_CANONICAL);
        lenient().when(this.serializer.serialize(WEBHOME_REFERENCE)).thenReturn(WEBHOME_CANONICAL);
        lenient().when(this.localSerializer.serialize(WEBHOME_REFERENCE)).thenReturn(WEBHOME_REF);
        lenient().when(this.localSerializer.serialize(WEBHOME_REFERENCE.getLastSpaceReference()))
            .thenReturn("Sandbox");
        // The manual-deletion URL of the sensitive-reference refusals.
        lenient().when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        // The recycle bin is available unless a test says otherwise.
        lenient().doReturn(true).when(oldcore.getSpyXWiki()).hasRecycleBin(any());

        // XWiki#deleteAllDocuments wraps the per-translation deletes in a batch (one shared recycle-bin
        // batch ID); MockitoOldcore registers no BatchOperationExecutor, so a pass-through mock runs the
        // operation inline.
        BatchOperationExecutor batchOperationExecutor =
            oldcore.getMocker().registerMockComponent(BatchOperationExecutor.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, BatchOperation.class).execute();
            return null;
        }).when(batchOperationExecutor).execute(any());
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    private void storeDocument(MockitoOldcore oldcore, DocumentReference reference, String content) throws Exception
    {
        storeDocument(oldcore, reference, content, null);
    }

    /**
     * Mocks the children lookup query so it returns the given child full names, wiring the
     * escaped-parameter chain the tool binds the space prefix through.
     *
     * @param children the child full names the query returns
     * @return the mocked query
     */
    private Query mockChildrenQuery(List<String> children) throws Exception
    {
        Query query = mock(Query.class);
        this.spaceParameter = mock(QueryParameter.class);
        when(query.bindValue("space")).thenReturn(this.spaceParameter);
        when(this.spaceParameter.literal(anyString())).thenReturn(this.spaceParameter);
        when(this.spaceParameter.anyChars()).thenReturn(this.spaceParameter);
        when(this.spaceParameter.query()).thenReturn(query);
        when(query.<String>execute()).thenReturn(children);
        when(this.queryManager.createQuery(CHILDREN_XWQL, Query.XWQL)).thenReturn(query);
        return query;
    }

    private Query mockCountQuery(long count) throws Exception
    {
        return mockCountRows(List.of(count));
    }

    private Query mockCountRows(List<?> rows) throws Exception
    {
        Query query = mock(Query.class);
        QueryParameter parameter = mock(QueryParameter.class);
        when(query.bindValue("space")).thenReturn(parameter);
        when(parameter.literal(anyString())).thenReturn(parameter);
        when(parameter.anyChars()).thenReturn(parameter);
        when(parameter.query()).thenReturn(query);
        doReturn(rows).when(query).execute();
        when(this.queryManager.createQuery(COUNT_XWQL, Query.XWQL)).thenReturn(query);
        return query;
    }

    private void verifyNothingDeleted(MockitoOldcore oldcore) throws Exception
    {
        verify(oldcore.getSpyXWiki(), never())
            .deleteDocument(any(XWikiDocument.class), any(Boolean.class), any(XWikiContext.class));
    }

    @Test
    void missingReferenceParamReturnsRequiredError() throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(REFERENCE_KEY), text);
        assertTrue(text.contains("required"), text);
        verify(this.documentAccess, never()).resolveAndAuthorize(anyString(), any());
    }

    @Test
    void missingBaseVersionReturnsRequiredError() throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, TERMINAL_REF));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(BASE_VERSION_KEY), text);
        assertTrue(text.contains("required"), text);
        verify(this.documentAccess, never()).resolveAndAuthorize(anyString(), any());
    }

    @Test
    void nonexistentDocumentErrorsAndNothingIsDeleted(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, TERMINAL_REF, BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("does not exist"), text);
        assertTrue(text.contains("nothing to delete"), text);
        verifyNothingDeleted(oldcore);
    }

    @Test
    void nonexistentDocumentEchoIsNeutralized(MockitoOldcore oldcore) throws Exception
    {
        String hostile = "Sandbox.Page\nInjected line";

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, hostile, BASE_VERSION_KEY, "1.1"));

        // The raw reference argument is echoed through the fragment guard: a newline smuggled into the
        // parameter cannot forge an extra line of the tool's output grammar.
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Document \"Sandbox.PageInjected line\" does not exist; nothing to delete.",
            textOf(result));
        verifyNothingDeleted(oldcore);
    }

    @Test
    void doorDenialSurfacesMessageWithoutLoadingOrDeleting(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenThrow(new MCPAccessDeniedException(
                "You do not have permission to delete \"" + TERMINAL_REF + "\"."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, TERMINAL_REF, BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("You do not have permission to delete"), textOf(result));
        verify(oldcore.getSpyXWiki(), never())
            .getDocument(any(DocumentReference.class), any(XWikiContext.class));
        verifyNothingDeleted(oldcore);
    }

    @Test
    void staleBaseVersionRefusesAndDocumentSurvives(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, TERMINAL_REFERENCE, "content");
        String currentVersion = loadDocument(oldcore, TERMINAL_REFERENCE).getVersion();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, TERMINAL_REF, BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Version conflict"), text);
        assertTrue(text.contains(currentVersion), text);
        assertFalse(loadDocument(oldcore, TERMINAL_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
    }

    @Test
    void terminalPageDeletedWithRecycleBinAndVersionInResult(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, TERMINAL_REFERENCE, "content");
        String currentVersion = loadDocument(oldcore, TERMINAL_REFERENCE).getVersion();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, TERMINAL_REF, BASE_VERSION_KEY, currentVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Deleted document " + TERMINAL_CANONICAL), text);
        assertTrue(text.contains("recycle bin"), text);
        assertTrue(text.contains("Version: " + currentVersion), text);
        // A page without translations reports none.
        assertFalse(text.contains("translation"), text);
        assertTrue(loadDocument(oldcore, TERMINAL_REFERENCE).isNew());
        // A terminal page has no children by definition, so the children lookup never runs.
        verify(this.queryManager, never()).createQuery(anyString(), anyString());
    }

    @Test
    void pageWithTranslationsDeletesAllRowsAndReportsThem(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, TERMINAL_REFERENCE, "content");
        storeTranslation(oldcore, TERMINAL_REFERENCE, Locale.FRENCH, "contenu");
        storeTranslation(oldcore, TERMINAL_REFERENCE, Locale.GERMAN, "inhalt");
        String currentVersion = loadDocument(oldcore, TERMINAL_REFERENCE).getVersion();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, TERMINAL_REF, BASE_VERSION_KEY, currentVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Deleted document " + TERMINAL_CANONICAL), text);
        // Pin the locale list itself: a bare contains("fr") also matches "...restore it FRom there" in the
        // fixed message text. The list order follows the mock store's iteration order, so assert membership.
        String localeList = StringUtils.substringBetween(text, "2 translation(s) (", ")");
        assertNotNull(localeList, text);
        assertTrue(localeList.contains("fr"), text);
        assertTrue(localeList.contains("de"), text);
        // Every row is gone: the default document and both translation rows.
        assertTrue(loadDocument(oldcore, TERMINAL_REFERENCE).isNew());
        assertTrue(loadDocument(oldcore, new DocumentReference(TERMINAL_REFERENCE, Locale.FRENCH)).isNew());
        assertTrue(loadDocument(oldcore, new DocumentReference(TERMINAL_REFERENCE, Locale.GERMAN)).isNew());
    }

    @Test
    void apiLevelRightsRecheckDenialRefusesAfterDoorPassed(MockitoOldcore oldcore) throws Exception
    {
        // The door check passed (the mocked MCPDocumentAccess resolves without throwing); the api-level
        // re-check consults the rights service, which now denies.
        when(oldcore.getMockRightService().hasAccessLevel(any(), any(), any(), any())).thenReturn(false);
        storeDocument(oldcore, TERMINAL_REFERENCE, "content");
        String currentVersion = loadDocument(oldcore, TERMINAL_REFERENCE).getVersion();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, TERMINAL_REF, BASE_VERSION_KEY, currentVersion));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("You do not have permission to delete"), textOf(result));
        assertFalse(loadDocument(oldcore, TERMINAL_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
    }

    @Test
    void recycleBinDisabledRefusesAndDocumentSurvives(MockitoOldcore oldcore) throws Exception
    {
        doReturn(false).when(oldcore.getSpyXWiki()).hasRecycleBin(any());
        storeDocument(oldcore, TERMINAL_REFERENCE, "content");
        String currentVersion = loadDocument(oldcore, TERMINAL_REFERENCE).getVersion();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, TERMINAL_REF, BASE_VERSION_KEY, currentVersion));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("no recycle bin"), text);
        assertTrue(text.contains("permanent"), text);
        assertFalse(loadDocument(oldcore, TERMINAL_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
    }

    @Test
    void webHomeWithChildrenRefusedWithAccurateCount(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(WEBHOME_REFERENCE);
        storeDocument(oldcore, WEBHOME_REFERENCE, "home");
        String currentVersion = loadDocument(oldcore, WEBHOME_REFERENCE).getVersion();
        Query childrenQuery = mockChildrenQuery(List.of("Sandbox.A", "Sandbox.B"));
        mockCountQuery(5L);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, WEBHOME_REF, BASE_VERSION_KEY, currentVersion));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("home page of a space with 5 child pages"), text);
        assertTrue(text.contains("no recursive delete"), text);
        assertFalse(loadDocument(oldcore, WEBHOME_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
        // The lookup is scoped to the target wiki, capped, and bound with the escaped space prefix.
        verify(childrenQuery).setWiki("xwiki");
        verify(childrenQuery).setLimit(2);
        verify(childrenQuery).bindValue("fullName", WEBHOME_REF);
        verify(this.spaceParameter).literal("Sandbox.");
    }

    @Test
    void webHomeChildCountSurvivesAlternateRowShape(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(WEBHOME_REFERENCE);
        storeDocument(oldcore, WEBHOME_REFERENCE, "home");
        String currentVersion = loadDocument(oldcore, WEBHOME_REFERENCE).getVersion();
        mockChildrenQuery(List.of("Sandbox.A"));
        // The count row's runtime shape depends on the store: pin the dispatch on an Object[] row
        // carrying a non-Long Number, so a refactor cannot silently revert to the raw Long cast.
        mockCountRows(List.<Object>of(new Object[] {3}));

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, WEBHOME_REF, BASE_VERSION_KEY, currentVersion));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("home page of a space with 3 child pages"), text);
        assertFalse(loadDocument(oldcore, WEBHOME_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
    }

    @Test
    void webHomeWithOnlyWebPreferencesChildDeletedWithNote(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(WEBHOME_REFERENCE);
        storeDocument(oldcore, WEBHOME_REFERENCE, "home");
        String currentVersion = loadDocument(oldcore, WEBHOME_REFERENCE).getVersion();
        mockChildrenQuery(List.of("Sandbox.WebPreferences"));

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, WEBHOME_REF, BASE_VERSION_KEY, currentVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Deleted document " + WEBHOME_CANONICAL), text);
        assertTrue(text.contains("WebPreferences page (space settings) remains"), text);
        assertTrue(loadDocument(oldcore, WEBHOME_REFERENCE).isNew());
    }

    @Test
    void webHomeWithZeroChildrenDeletedWithoutNote(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(WEBHOME_REFERENCE);
        storeDocument(oldcore, WEBHOME_REFERENCE, "home");
        String currentVersion = loadDocument(oldcore, WEBHOME_REFERENCE).getVersion();
        mockChildrenQuery(List.of());

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, WEBHOME_REF, BASE_VERSION_KEY, currentVersion));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Deleted document " + WEBHOME_CANONICAL), text);
        assertFalse(text.contains("WebPreferences"), text);
        assertTrue(loadDocument(oldcore, WEBHOME_REFERENCE).isNew());
    }

    @Test
    void childCheckFailureRefusesDeletionFailClosed(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(WEBHOME_REFERENCE);
        storeDocument(oldcore, WEBHOME_REFERENCE, "home");
        String currentVersion = loadDocument(oldcore, WEBHOME_REFERENCE).getVersion();
        Query childrenQuery = mockChildrenQuery(List.of());
        when(childrenQuery.execute()).thenThrow(new QueryException("boom", childrenQuery, null));

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, WEBHOME_REF, BASE_VERSION_KEY, currentVersion));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not verify"), textOf(result));
        assertFalse(loadDocument(oldcore, WEBHOME_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
        assertTrue(this.logCapture.getMessage(0).contains("boom"), this.logCapture.getMessage(0));
    }

    @Test
    void storageFailureReportsPossiblePartialDeletionAndLogsRootCause(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, TERMINAL_REFERENCE, "content");
        String currentVersion = loadDocument(oldcore, TERMINAL_REFERENCE).getVersion();
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_UNKNOWN, "db down"))
            .when(oldcore.getSpyXWiki())
            .deleteDocument(any(XWikiDocument.class), any(Boolean.class), any(XWikiContext.class));

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, TERMINAL_REF, BASE_VERSION_KEY, currentVersion));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        // The per-row deletion is not transactional, so a failure of the delete itself is reported as
        // possibly partial; the storage internals stay in the server logs, off the wire.
        assertTrue(text.contains("deletion failed partway"), text);
        assertTrue(text.contains("retry the deletion"), text);
        assertFalse(text.contains("db down"), text);
        assertFalse(loadDocument(oldcore, TERMINAL_REFERENCE).isNew());
        assertTrue(this.logCapture.getMessage(0).contains("db down"), this.logCapture.getMessage(0));
    }

    @Test
    void webPreferencesPageRefusedForManualDeletion(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(WEBPREFS_REFERENCE);
        when(this.serializer.serialize(WEBPREFS_REFERENCE)).thenReturn("xwiki:Sandbox.WebPreferences");
        storeDocument(oldcore, WEBPREFS_REFERENCE, "space rights");

        // The base_version is deliberately stale: the denylist fires before the version comparison.
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, "Sandbox.WebPreferences", BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(SENSITIVE_MESSAGE_PART), text);
        assertTrue(text.contains(VIEW_URL), text);
        assertFalse(text.contains("Version conflict"), text);
        assertFalse(loadDocument(oldcore, WEBPREFS_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
        // The denylist fires before the children guard, so no query ever runs.
        verify(this.queryManager, never()).createQuery(anyString(), anyString());
    }

    @Test
    void xwikiPreferencesPageRefusedForManualDeletion(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(XWIKI_PREFS_REFERENCE);
        when(this.serializer.serialize(XWIKI_PREFS_REFERENCE)).thenReturn("xwiki:XWiki.XWikiPreferences");
        storeDocument(oldcore, XWIKI_PREFS_REFERENCE, "wiki rights");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, "XWiki.XWikiPreferences", BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(SENSITIVE_MESSAGE_PART), text);
        assertTrue(text.contains(VIEW_URL), text);
        assertFalse(loadDocument(oldcore, XWIKI_PREFS_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
    }

    @Test
    void mainWikiDescriptorPageRefusedForManualDeletion(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(DESCRIPTOR_REFERENCE);
        when(this.serializer.serialize(DESCRIPTOR_REFERENCE)).thenReturn("xwiki:XWiki.XWikiServerFoo");
        when(this.localSerializer.serialize(DESCRIPTOR_REFERENCE.getLastSpaceReference())).thenReturn("XWiki");
        storeDocument(oldcore, DESCRIPTOR_REFERENCE, "descriptor");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, "XWiki.XWikiServerFoo", BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(SENSITIVE_MESSAGE_PART), text);
        assertTrue(text.contains(VIEW_URL), text);
        assertFalse(loadDocument(oldcore, DESCRIPTOR_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
    }

    @Test
    void mcpConfigurationDocumentRefusedForManualDeletion(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(CONFIG_REFERENCE);
        when(this.serializer.serialize(CONFIG_REFERENCE)).thenReturn("xwiki:AI.MCP.Code.MCPServerConfig");
        when(this.localSerializer.serialize(CONFIG_REFERENCE)).thenReturn("AI.MCP.Code.MCPServerConfig");
        storeDocument(oldcore, CONFIG_REFERENCE, "mcp config");

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, "AI.MCP.Code.MCPServerConfig", BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(SENSITIVE_MESSAGE_PART), text);
        assertTrue(text.contains(VIEW_URL), text);
        assertFalse(loadDocument(oldcore, CONFIG_REFERENCE).isNew());
        verifyNothingDeleted(oldcore);
    }

    @Test
    void nonMainWikiDescriptorLookalikeIsNotDenylisted(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.DELETE)))
            .thenReturn(OTHER_DESCRIPTOR_REFERENCE);
        when(this.serializer.serialize(OTHER_DESCRIPTOR_REFERENCE))
            .thenReturn("otherwiki:XWiki.XWikiServerNotes");
        // Insert directly into the store (keyed by the locale-carrying reference): a real save under the
        // non-main "otherwiki" namespace cannot resolve its serializers in the oldcore fixture.
        XWikiDocument doc = new XWikiDocument(OTHER_DESCRIPTOR_REFERENCE);
        doc.setContent("notes");
        doc.setNew(false);
        oldcore.getDocuments().put(doc.getDocumentReferenceWithLocale(), doc);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "otherwiki:XWiki.XWikiServerNotes",
            BASE_VERSION_KEY, doc.getVersion()));

        // The descriptor rule is main-wiki-scoped, so the page deletes normally.
        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Deleted document otherwiki:XWiki.XWikiServerNotes"),
            textOf(result));
        assertTrue(loadDocument(oldcore, OTHER_DESCRIPTOR_REFERENCE).isNew());
    }

    @Test
    void toolDefinitionRequiresReferenceAndBaseVersion()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();

        assertEquals(MCPDeleteDocumentTool.TOOL_ID, definition.name());
        List<?> required = (List<?>) definition.inputSchema().get("required");
        assertTrue(required.contains(REFERENCE_KEY), required.toString());
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
        assertEquals("Authoring", this.tool.getCategory());
        assertTrue(this.tool.getSummary().contains("recycle bin"), this.tool.getSummary());
        assertTrue(this.tool.getManPage().contains("EXAMPLES"), this.tool.getManPage());
        assertTrue(this.tool.getManPage().contains("recycle bin"), this.tool.getManPage());
    }
}
