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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.NumberClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.classes.StringClass;
import com.xpn.xwiki.objects.classes.TextAreaClass;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPWriteSchemaTool}, against the real oldcore store and real property classes so field
 * creation, attribute application and removal are exercised end to end.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPWriteSchemaToolTest extends AbstractMCPWriteToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String OPERATION_KEY = "operation";

    private static final String FIELD_KEY = "field";

    private static final String TYPE_KEY = "type";

    private static final String PRETTY_NAME_KEY = "pretty_name";

    private static final String ATTRIBUTES_KEY = "attributes";

    private static final String BASE_VERSION_KEY = "base_version";

    private static final String ADD_FIELD = "add_field";

    private static final String MODIFY_FIELD = "modify_field";

    private static final String REMOVE_FIELD = "remove_field";

    private static final String REF = "MyApp.MyClass";

    private static final String LOCAL_NAME = "MyApp.MyClass";

    private static final String CANONICAL = "xwiki:MyApp.MyClass";

    private static final String TITLE_FIELD = "title";

    private static final String COUNT_FIELD = "count";

    private static final String SIZE_ATTR = "size";

    private static final String VIEW_URL = "https://wiki.example/bin/view/MyApp/MyClass";

    private static final DocumentReference DOC_REFERENCE = new DocumentReference("xwiki", "MyApp", "MyClass");

    private static final DocumentReference USERS_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "XWikiUsers");

    private static final DocumentReference GROUPS_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "XWikiGroups");

    private static final DocumentReference WEBPREFS_REFERENCE =
        new DocumentReference("xwiki", "MyApp", "WebPreferences");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPWriteSchemaTool tool;

    @MockComponent
    private MCPRowQuery rowQuery;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @BeforeEach
    void setUp(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(DOC_REFERENCE);
        lenient().when(this.serializer.serialize(any())).thenReturn(CANONICAL);
        lenient().when(this.localSerializer.serialize(DOC_REFERENCE)).thenReturn(LOCAL_NAME);
        lenient().when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);
        lenient().when(this.rowQuery.rows(anyString(), anyString(), anyMap(), anyInt())).thenReturn(List.of());

        allowSimpleSavePath(oldcore);
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    private void storeClass(MockitoOldcore oldcore) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        BaseClass xclass = doc.getXClass();
        xclass.addTextField(TITLE_FIELD, "Title", 30);
        xclass.addNumberField(COUNT_FIELD, "Count", 10, "integer");
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    private void storeFieldlessDocument(MockitoOldcore oldcore) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setContent("An ordinary page with no class fields.");
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    private String currentVersion(MockitoOldcore oldcore) throws Exception
    {
        return currentVersion(oldcore, DOC_REFERENCE);
    }

    private BaseClass loadClass(MockitoOldcore oldcore) throws Exception
    {
        return loadDocument(oldcore, DOC_REFERENCE).getXClass();
    }

    @Test
    void addFieldCreatesClassOnNewDocumentWithoutBaseVersion(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, TITLE_FIELD, TYPE_KEY, "String", ATTRIBUTES_KEY, Map.of(SIZE_ATTR, "60")));

        assertNotEquals(Boolean.TRUE, result.isError());
        BaseClass xclass = loadClass(oldcore);
        PropertyClass property = (PropertyClass) xclass.get(TITLE_FIELD);
        assertEquals("String", property.getClassType());
        assertEquals(60, ((StringClass) property).getSize());
        String text = textOf(result);
        assertTrue(text.contains("Added field \"title\" to class \"MyApp.MyClass\""), text);
        assertTrue(text.contains("title: String"), text);
        assertTrue(text.contains("View: " + VIEW_URL), text);
    }

    @Test
    void addFieldOnNewDocumentStampsTheWikiDefaultLocale(MockitoOldcore oldcore) throws Exception
    {
        doReturn(Locale.GERMAN).when(oldcore.getSpyXWiki()).getDefaultLocale(any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, TITLE_FIELD, TYPE_KEY, "String"));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The wiki's default locale is stamped on the created class document, surviving the tool's clone.
        assertEquals(Locale.GERMAN, loadDocument(oldcore, DOC_REFERENCE).getDefaultLocale());
    }

    @Test
    void addNumberFieldAppliesNumberTypeAttribute(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, COUNT_FIELD, TYPE_KEY, "Number", ATTRIBUTES_KEY, Map.of("numberType", "integer")));

        assertNotEquals(Boolean.TRUE, result.isError());
        PropertyClass property = (PropertyClass) loadClass(oldcore).get(COUNT_FIELD);
        assertEquals("Number", property.getClassType());
        assertEquals("integer", ((NumberClass) property).getNumberType());
        assertTrue(textOf(result).contains("count: Number(integer)"), textOf(result));
    }

    @Test
    void addStaticListFieldAppliesValuesAndMultiSelect(MockitoOldcore oldcore) throws Exception
    {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("values", "News|Personal|Other");
        attributes.put("multiSelect", "1");
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "tags", TYPE_KEY, "StaticList", ATTRIBUTES_KEY, attributes));

        assertNotEquals(Boolean.TRUE, result.isError());
        PropertyClass property = (PropertyClass) loadClass(oldcore).get("tags");
        assertEquals("StaticList", property.getClassType());
        String text = textOf(result);
        assertTrue(text.contains("StaticList(News|Personal|Other)"), text);
        assertTrue(text.contains("multiselect"), text);
    }

    @Test
    void addDbListFieldNeverEchoesTheSql(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "picker", TYPE_KEY, "DBList",
            ATTRIBUTES_KEY, Map.of("sql", "select doc.fullName from XWikiDocument doc")));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("DBList", ((PropertyClass) loadClass(oldcore).get("picker")).getClassType());
        String text = textOf(result);
        assertTrue(text.contains("picker: DBList"), text);
        assertFalse(text.contains("select doc.fullName"), text);
    }

    @Test
    void addComputedFieldNeverEchoesTheScript(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "total", TYPE_KEY, "ComputedField",
            ATTRIBUTES_KEY, Map.of("script", "{{velocity}}$secret{{/velocity}}")));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("ComputedField", ((PropertyClass) loadClass(oldcore).get("total")).getClassType());
        String text = textOf(result);
        assertTrue(text.contains("total: ComputedField"), text);
        assertFalse(text.contains("secret"), text);
    }

    @Test
    void textAreaContentTypeDisplayTokenIsNormalizedToTheStoredVocabulary(MockitoOldcore oldcore)
        throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "notes", TYPE_KEY, "TextArea", ATTRIBUTES_KEY, Map.of("contentType", "plain")));

        assertNotEquals(Boolean.TRUE, result.isError());
        TextAreaClass property = (TextAreaClass) loadClass(oldcore).get("notes");
        // The display token "plain" is stored as the platform vocabulary: stored raw it would match no
        // ContentType on read and the field would silently render as wiki content.
        assertEquals("puretext", property.getContentType());
        assertEquals(TextAreaClass.ContentType.PURE_TEXT,
            TextAreaClass.ContentType.getByValue(property.getContentType()));
        // get_schema round-trip: the stored value reads back as the display detail the agent wrote.
        assertTrue(textOf(result).contains("notes: TextArea(plain)"), textOf(result));
    }

    @Test
    void textAreaContentTypePlatformTokenPassesThrough(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "code", TYPE_KEY, "TextArea", ATTRIBUTES_KEY, Map.of("contentType", "VelocityCode")));

        assertNotEquals(Boolean.TRUE, result.isError());
        TextAreaClass property = (TextAreaClass) loadClass(oldcore).get("code");
        assertEquals("velocitycode", property.getContentType());
        assertTrue(textOf(result).contains("code: TextArea(velocityCode)"), textOf(result));
    }

    @Test
    void unknownContentTypeTokenRefusedListingTheDisplayVocabulary(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "notes", TYPE_KEY, "TextArea", ATTRIBUTES_KEY, Map.of("contentType", "fancy")));

        assertEquals(Boolean.TRUE, result.isError());
        // The error teaches the display vocabulary, since that is what get_schema shows.
        assertTrue(textOf(result).contains(
            "must be one of plain, wiki, velocityCode, velocityWiki, got \"fancy\""), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void textAreaEditorTokenIsNormalizedToTheStoredVocabulary(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "notes", TYPE_KEY, "TextArea", ATTRIBUTES_KEY, Map.of("editor", "wysiwyg")));

        assertNotEquals(Boolean.TRUE, result.isError());
        TextAreaClass property = (TextAreaClass) loadClass(oldcore).get("notes");
        // The stored meta-property carries the canonical platform token, not the raw lowercase input.
        assertEquals("Wysiwyg", property.getStringValue("editor"));
    }

    @Test
    void unknownEditorTokenRefusedListingThePlatformVocabulary(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "notes", TYPE_KEY, "TextArea", ATTRIBUTES_KEY, Map.of("editor", "emacs")));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("must be one of PureText, Text, Wysiwyg, got \"emacs\""),
            textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void addFieldToExistingDocumentRequiresBaseVersion(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "done", TYPE_KEY, "Boolean"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("pass the base_version"), textOf(result));
        assertNull(loadClass(oldcore).get("done"));
    }

    @Test
    void baseVersionRequiredEchoIsNeutralized(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);
        String hostile = "MyApp.MyClass\nInjected line";

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, hostile, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "done", TYPE_KEY, "Boolean"));

        // The raw reference argument is echoed through the fragment guard: a newline smuggled into the
        // parameter cannot forge an extra line of the tool's output grammar.
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Document \"MyApp.MyClassInjected line\" already exists. First read it with "
            + "get_document and pass the base_version it shows.", textOf(result));
        assertNull(loadClass(oldcore).get("done"));
    }

    @Test
    void addFieldWithStaleBaseVersionRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "done", TYPE_KEY, "Boolean", BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Version conflict"), textOf(result));
        assertNull(loadClass(oldcore).get("done"));
    }

    @Test
    void addFieldWithValidBaseVersionAdds(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "done", TYPE_KEY, "Boolean", BASE_VERSION_KEY, currentVersion(oldcore)));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals("Boolean", ((PropertyClass) loadClass(oldcore).get("done")).getClassType());
    }

    @Test
    void unknownTypeRefusedListingAcceptedTypes(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "x", TYPE_KEY, "Widget"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Unknown field type \"Widget\""), text);
        assertTrue(text.contains("String"), text);
        verifyNothingSaved(oldcore);
    }

    @Test
    void unknownAttributeForTypeRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, TITLE_FIELD, TYPE_KEY, "String", ATTRIBUTES_KEY, Map.of("numberType", "integer")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("not valid for a String field"), text);
        assertTrue(text.contains(SIZE_ATTR), text);
        verifyNothingSaved(oldcore);
    }

    @Test
    void nonNumericIntAttributeRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, TITLE_FIELD, TYPE_KEY, "String", ATTRIBUTES_KEY, Map.of(SIZE_ATTR, "abc")));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("must be a whole number"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void nonBooleanBoolAttributeRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "tags", TYPE_KEY, "StaticList", ATTRIBUTES_KEY, Map.of("multiSelect", "maybe")));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("0, 1, true, false"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void addingExistingFieldRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, TITLE_FIELD, TYPE_KEY, "Number", BASE_VERSION_KEY, currentVersion(oldcore)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("already exists"), text);
        assertTrue(text.contains("remove_field then add_field"), text);
        // The field kept its original type.
        assertEquals("String", ((PropertyClass) loadClass(oldcore).get(TITLE_FIELD)).getClassType());
    }

    @Test
    void addTypeMissingRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, TITLE_FIELD));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("type"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void modifyFieldChangesPrettyNameAndAttribute(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, MODIFY_FIELD,
            FIELD_KEY, TITLE_FIELD, PRETTY_NAME_KEY, "Task title", ATTRIBUTES_KEY, Map.of(SIZE_ATTR, "80"),
            BASE_VERSION_KEY, currentVersion(oldcore)));

        assertNotEquals(Boolean.TRUE, result.isError());
        PropertyClass property = (PropertyClass) loadClass(oldcore).get(TITLE_FIELD);
        assertEquals("Task title", property.getPrettyName());
        assertEquals(80, ((StringClass) property).getSize());
        assertTrue(textOf(result).contains("Updated field \"title\""), textOf(result));
    }

    @Test
    void modifyFieldWithTypeRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, MODIFY_FIELD,
            FIELD_KEY, TITLE_FIELD, TYPE_KEY, "Number", BASE_VERSION_KEY, currentVersion(oldcore)));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("only valid for add_field"), textOf(result));
        assertEquals("String", ((PropertyClass) loadClass(oldcore).get(TITLE_FIELD)).getClassType());
    }

    @Test
    void modifyUnknownFieldRefusedListingFields(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, MODIFY_FIELD,
            FIELD_KEY, "nope", PRETTY_NAME_KEY, "Nope", BASE_VERSION_KEY, currentVersion(oldcore)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("unknown field \"nope\""), text);
        assertTrue(text.contains(TITLE_FIELD), text);
    }

    @Test
    void modifyFieldWithNothingToChangeRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, MODIFY_FIELD,
            FIELD_KEY, TITLE_FIELD, BASE_VERSION_KEY, currentVersion(oldcore)));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Nothing to change"), textOf(result));
    }

    @Test
    void modifyFieldOnFieldlessDocumentRefused(MockitoOldcore oldcore) throws Exception
    {
        assertFieldlessDocumentRefused(oldcore, MODIFY_FIELD);
    }

    @Test
    void removeFieldWithPrettyNameRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, REMOVE_FIELD,
            FIELD_KEY, TITLE_FIELD, PRETTY_NAME_KEY, "Nope"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("remove_field takes no pretty_name or attributes"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void removeFieldOnFieldlessDocumentRefused(MockitoOldcore oldcore) throws Exception
    {
        assertFieldlessDocumentRefused(oldcore, REMOVE_FIELD);
    }

    private void assertFieldlessDocumentRefused(MockitoOldcore oldcore, String operation) throws Exception
    {
        storeFieldlessDocument(oldcore);
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, operation,
            FIELD_KEY, TITLE_FIELD, BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("defines no class fields"), textOf(result));
        // The refusal fires before any save, so the stored document is untouched.
        assertEquals(versionBefore, currentVersion(oldcore));
    }

    @Test
    void removeFieldRequiresBaseVersionAlways(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, REMOVE_FIELD,
            FIELD_KEY, TITLE_FIELD));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("destructive"), textOf(result));
        // Nothing removed.
        assertTrue(loadClass(oldcore).get(TITLE_FIELD) instanceof PropertyClass);
    }

    @Test
    void removeFieldStaleBaseVersionRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, REMOVE_FIELD,
            FIELD_KEY, TITLE_FIELD, BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Version conflict"), textOf(result));
        assertTrue(loadClass(oldcore).get(TITLE_FIELD) instanceof PropertyClass);
    }

    @Test
    void removeFieldRemovesItAndDisclosesReversibility(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, REMOVE_FIELD,
            FIELD_KEY, TITLE_FIELD, BASE_VERSION_KEY, currentVersion(oldcore)));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertNull(loadClass(oldcore).get(TITLE_FIELD));
        String text = textOf(result);
        assertTrue(text.contains("Removed field \"title\""), text);
        // No stored values under the field name (the door returns no rows), so the disclosure reports none.
        assertTrue(text.contains("No object held a value"), text);
        assertTrue(text.contains("reverted"), text);
    }

    @Test
    void removeFieldOrphanDisclosureCountsAuthorizedDocuments(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);
        // Two documents still hold a stored value under the removed field name, both viewable.
        when(this.rowQuery.rows(anyString(), anyString(), anyMap(), anyInt()))
            .thenReturn(scalarRows("MyApp.A", "MyApp.B"));
        when(this.rowQuery.authorizedDocument(anyString(), any(WikiReference.class)))
            .thenReturn(new DocumentReference("xwiki", "MyApp", "A"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, REMOVE_FIELD,
            FIELD_KEY, TITLE_FIELD, BASE_VERSION_KEY, currentVersion(oldcore)));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("2 object(s) held values"), textOf(result));
    }

    @Test
    void removeUnknownFieldRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClass(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, REMOVE_FIELD,
            FIELD_KEY, "nope", BASE_VERSION_KEY, currentVersion(oldcore)));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("unknown field \"nope\""), textOf(result));
        assertTrue(loadClass(oldcore).get(TITLE_FIELD) instanceof PropertyClass);
    }

    @Test
    void sensitiveUsersClassDefinitionRefused(MockitoOldcore oldcore) throws Exception
    {
        assertSensitiveClassRefused(oldcore, USERS_REFERENCE, "XWiki.XWikiUsers");
    }

    @Test
    void sensitiveGroupsClassDefinitionRefused(MockitoOldcore oldcore) throws Exception
    {
        assertSensitiveClassRefused(oldcore, GROUPS_REFERENCE, "XWiki.XWikiGroups");
    }

    private void assertSensitiveClassRefused(MockitoOldcore oldcore, DocumentReference reference,
        String localName) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(reference);
        when(this.localSerializer.serialize(reference)).thenReturn(localName);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, localName, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "x", TYPE_KEY, "String", BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("access rights, group membership"), textOf(result));
        // The denylist fires before the version check, and nothing is saved.
        assertFalse(textOf(result).contains("Version conflict"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void sensitiveDocumentRefused(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenReturn(WEBPREFS_REFERENCE);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "MyApp.WebPreferences",
            OPERATION_KEY, ADD_FIELD, FIELD_KEY, "x", TYPE_KEY, "String", BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("defines access rights or wiki configuration"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void doorDenialSurfacesMessageWithoutSaving(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to edit \"" + REF + "\"."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, TITLE_FIELD, TYPE_KEY, "String"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void storageFailureReturnsFixedMessageAndLeavesCacheDocumentUnmutated(MockitoOldcore oldcore)
        throws Exception
    {
        storeClass(oldcore);
        String versionBefore = currentVersion(oldcore);
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_UNKNOWN,
            "db down")).when(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, OPERATION_KEY, ADD_FIELD,
            FIELD_KEY, "done", TYPE_KEY, "Boolean", BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not save the document"), textOf(result));
        assertTrue(this.logCapture.getMessage(0).contains("db down"), this.logCapture.getMessage(0));
        // The mutation happened on the tool's clone, so the cached class definition is unchanged.
        assertNull(loadClass(oldcore).get("done"));
        assertEquals(versionBefore, currentVersion(oldcore));
    }

    @Test
    void catalogMetadataIsSet()
    {
        assertTrue(this.tool.isWrite());
        assertEquals("Structured Data", this.tool.getCategory());
        assertTrue(this.tool.getManPage().contains("WARNING"), this.tool.getManPage());
        assertTrue(this.tool.getManPage().contains("reversible"), this.tool.getManPage());
    }

    @Test
    void toolDefinitionRequiresReferenceOperationAndField()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();

        assertEquals(MCPWriteSchemaTool.TOOL_ID, definition.name());
        List<?> required = (List<?>) definition.inputSchema().get("required");
        assertTrue(required.contains(REFERENCE_KEY), required.toString());
        assertTrue(required.contains(OPERATION_KEY), required.toString());
        assertTrue(required.contains(FIELD_KEY), required.toString());
        assertFalse(required.contains(TYPE_KEY), required.toString());
    }

    @Test
    void reachOffDropsCrossWikiFromAdvertisedSchema()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        McpSchema.Tool definition = this.tool.getToolDefinition();
        assertFalse(definition.inputSchema().toString().contains("xwiki:"),
            "Reach-off advertised schema must not contain wiki-prefixed examples");
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> scalarRows(String... fullNames)
    {
        return (List<Object[]>) (List<?>) List.of(fullNames);
    }
}
