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
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.ComputedFieldClass;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPWriteObjectTool}, against the real oldcore store and real class definitions so the
 * schema validation, coercion and property wiring are exercised end to end.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPWriteObjectToolTest extends AbstractMCPWriteToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String CLASS_KEY = "class";

    private static final String OBJECT_KEY = "object";

    private static final String FIELDS_KEY = "fields";

    private static final String TITLE_KEY = "title";

    private static final String BASE_VERSION_KEY = "base_version";

    private static final String COMMENT_KEY = "comment";

    private static final String MAJOR_KEY = "major";

    private static final String REF = "Blog.MyPost";

    private static final String CLASS_REF = "Blog.BlogPostClass";

    private static final String CANONICAL = "xwiki:Blog.MyPost";

    private static final String TITLE_FIELD = "title";

    private static final String PUBLISHED_FIELD = "published";

    private static final String COUNT_FIELD = "count";

    private static final String CATEGORY_FIELD = "category";

    private static final String DATE_FIELD = "publishDate";

    private static final String DATE_FORMAT = "dd/MM/yyyy";

    private static final String HELLO = "Hello";

    private static final String VIEW_URL = "https://wiki.example/bin/view/Blog/MyPost";

    private static final DocumentReference DOC_REFERENCE = new DocumentReference("xwiki", "Blog", "MyPost");

    private static final DocumentReference CLASS_REFERENCE =
        new DocumentReference("xwiki", "Blog", "BlogPostClass");

    private static final DocumentReference WEBPREFS_REFERENCE =
        new DocumentReference("xwiki", "Blog", "WebPreferences");

    private static final DocumentReference RIGHTS_CLASS_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "XWikiRights");

    private static final DocumentReference GROUPS_CLASS_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "XWikiGroups");

    private static final DocumentReference USERS_CLASS_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "XWikiUsers");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPWriteObjectTool tool;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @BeforeEach
    void setUp(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(DOC_REFERENCE);
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenReturn(CLASS_REFERENCE);
        lenient().when(this.serializer.serialize(any())).thenReturn(CANONICAL);
        lenient().when(this.localSerializer.serialize(CLASS_REFERENCE)).thenReturn(CLASS_REF);
        lenient().when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);

        allowSimpleSavePath(oldcore);
        registerCurrentResolver(oldcore, CLASS_REFERENCE);
        registerLocalizationContext(oldcore);
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    /**
     * Stores a real class definition covering the type dispatch under test: text, boolean, integer,
     * multi-select list, password and computed fields.
     */
    private void storeClassDocument(MockitoOldcore oldcore) throws Exception
    {
        XWikiDocument classDoc = new XWikiDocument(CLASS_REFERENCE);
        BaseClass xclass = classDoc.getXClass();
        xclass.addTextField(TITLE_FIELD, "Title", 30);
        xclass.addBooleanField(PUBLISHED_FIELD, "Published", "yesno");
        xclass.addNumberField(COUNT_FIELD, "Count", 10, "integer");
        xclass.addStaticListField(CATEGORY_FIELD, "Category", 5, true, "News|Personal|Other");
        xclass.addDateField(DATE_FIELD, "Publish date", DATE_FORMAT);
        xclass.addPasswordField("secret", "Secret", 10);
        ComputedFieldClass computed = new ComputedFieldClass();
        computed.setName("total");
        computed.setPrettyName("Total");
        computed.setObject(xclass);
        xclass.addField("total", computed);
        oldcore.getSpyXWiki().saveDocument(classDoc, oldcore.getXWikiContext());
    }

    private void storeDocument(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, DOC_REFERENCE, "body", null);
    }

    private void storeDocumentWithObject(MockitoOldcore oldcore, String title) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setContent("body");
        BaseObject object = doc.newXObject(CLASS_REFERENCE, oldcore.getXWikiContext());
        object.setStringValue(TITLE_FIELD, title);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    private XWikiDocument loadDocument(MockitoOldcore oldcore) throws Exception
    {
        return loadDocument(oldcore, DOC_REFERENCE);
    }

    private String currentVersion(MockitoOldcore oldcore) throws Exception
    {
        return currentVersion(oldcore, DOC_REFERENCE);
    }

    @Test
    void createsObjectOnExistingDocumentAndSetsValidatedFields(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);
        String versionBefore = currentVersion(oldcore);

        // A LinkedHashMap so the result's "Fields set" order (which mirrors the request's field order)
        // is deterministic - Map.of does not preserve iteration order.
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TITLE_FIELD, HELLO);
        fields.put(PUBLISHED_FIELD, "1");
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, versionBefore, FIELDS_KEY, fields));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        BaseObject object = saved.getXObject(CLASS_REFERENCE, 0);
        assertNotNull(object);
        assertEquals(HELLO, object.getStringValue(TITLE_FIELD));
        assertEquals(1, object.getIntValue(PUBLISHED_FIELD));
        assertNotEquals(versionBefore, saved.getVersion());
        assertEquals("[AI] created Blog.BlogPostClass object 0", saved.getComment());

        String text = textOf(result);
        assertTrue(text.contains("Created Blog.BlogPostClass object 0 on document " + CANONICAL), text);
        assertTrue(text.contains("Version: " + versionBefore + " -> " + saved.getVersion()), text);
        assertTrue(text.contains("Fields set: title, published"), text);
        assertTrue(text.contains("Compare: " + VIEW_URL), text);
    }

    @Test
    void newObjectNumberIsAppendedAfterExistingObjects(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocumentWithObject(oldcore, "First");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of(TITLE_FIELD, "Second")));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Created Blog.BlogPostClass object 1"), textOf(result));
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("First", saved.getXObject(CLASS_REFERENCE, 0).getStringValue(TITLE_FIELD));
        assertEquals("Second", saved.getXObject(CLASS_REFERENCE, 1).getStringValue(TITLE_FIELD));
    }

    @Test
    void updatesExistingObjectByNumber(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocumentWithObject(oldcore, "First");
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, BASE_VERSION_KEY, versionBefore, FIELDS_KEY, Map.of(TITLE_FIELD, "Updated")));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("Updated", saved.getXObject(CLASS_REFERENCE, 0).getStringValue(TITLE_FIELD));
        assertEquals("[AI] set 1 field(s) on Blog.BlogPostClass object 0", saved.getComment());
        String text = textOf(result);
        assertTrue(text.contains("Updated Blog.BlogPostClass object 0 on document " + CANONICAL), text);
        // The default save of an existing document is a minor edit.
        verify(oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class),
            eq("[AI] set 1 field(s) on Blog.BlogPostClass object 0"), eq(true), any());
    }

    @Test
    void createWithTitleSetsThePageTitleInTheSameSave(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            TITLE_KEY, "Draft post", FIELDS_KEY, Map.of(TITLE_FIELD, "Draft")));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("Draft post", saved.getTitle());
        // One save, one version: the object-first creation names its page in the same call.
        assertEquals("1.1", saved.getVersion());
        assertNotNull(saved.getXObject(CLASS_REFERENCE, 0));
        assertTrue(textOf(result).contains("Title set."), textOf(result));
    }

    @Test
    void updateWithTitleUpdatesThePageTitle(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocumentWithObject(oldcore, "First");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, TITLE_KEY, "Renamed", BASE_VERSION_KEY, currentVersion(oldcore),
            FIELDS_KEY, Map.of(TITLE_FIELD, "Updated")));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("Renamed", saved.getTitle());
        assertEquals("Updated", saved.getXObject(CLASS_REFERENCE, 0).getStringValue(TITLE_FIELD));
        assertTrue(textOf(result).contains("Title updated."), textOf(result));
    }

    @Test
    void titleIdenticalToTheCurrentOneIsNotEchoedAsAChange(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setTitle("Kept title");
        doc.newXObject(CLASS_REFERENCE, oldcore.getXWikiContext());
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, TITLE_KEY, "Kept title", BASE_VERSION_KEY, currentVersion(oldcore),
            FIELDS_KEY, Map.of(TITLE_FIELD, "Updated")));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("Kept title", saved.getTitle());
        assertEquals("Updated", saved.getXObject(CLASS_REFERENCE, 0).getStringValue(TITLE_FIELD));
        // A title argument identical to the current title changes nothing, so no "Title updated." line.
        assertFalse(textOf(result).contains("Title updated."), textOf(result));
    }

    @Test
    void omittedTitleLeavesThePageTitleUntouched(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setTitle("Existing title");
        doc.newXObject(CLASS_REFERENCE, oldcore.getXWikiContext());
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, BASE_VERSION_KEY, currentVersion(oldcore),
            FIELDS_KEY, Map.of(TITLE_FIELD, "Updated")));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertEquals("Existing title", saved.getTitle());
        assertFalse(textOf(result).contains("Title"), textOf(result));
    }

    @Test
    void createsDocumentAndObjectInOneCallWithoutBaseVersion(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            FIELDS_KEY, Map.of(TITLE_FIELD, "Draft")));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadDocument(oldcore);
        assertFalse(saved.isNew());
        assertEquals("Draft", saved.getXObject(CLASS_REFERENCE, 0).getStringValue(TITLE_FIELD));
        assertEquals("[AI] Created document", saved.getComment());
        // A creation is a normal (major) save, not a minor edit.
        verify(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), eq("[AI] Created document"), eq(false), any());
        String text = textOf(result);
        assertTrue(text.contains("Created document " + CANONICAL + " with Blog.BlogPostClass object 0"),
            text);
        assertTrue(text.contains("View: " + VIEW_URL), text);
    }

    @Test
    void createdDocumentIsStampedWithTheWikiDefaultLocale(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        doReturn(Locale.GERMAN).when(oldcore.getSpyXWiki()).getDefaultLocale(any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            FIELDS_KEY, Map.of(TITLE_FIELD, "Draft")));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The wiki's default locale is stamped on the created document, surviving the tool's clone.
        assertEquals(Locale.GERMAN, loadDocument(oldcore).getDefaultLocale());
    }

    @Test
    void baseVersionRequiredOnExistingDocument(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("already exists"), text);
        assertTrue(text.contains(BASE_VERSION_KEY), text);
        assertEquals(versionBefore, currentVersion(oldcore));
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void staleBaseVersionRefusedWithConflictMessage(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, "9.9", FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Version conflict"), text);
        assertTrue(text.contains(versionBefore), text);
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void baseVersionRequiredEchoIsNeutralized(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);
        String hostile = "Blog.MyPost\nInjected line";

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, hostile, CLASS_KEY, CLASS_REF,
            FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        // The raw reference argument is echoed through the fragment guard: a newline smuggled into the
        // parameter cannot forge an extra line of the tool's output grammar.
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Document \"Blog.MyPostInjected line\" already exists. First read it with "
            + "get_document and pass the base_version it shows, so the object change is based on a "
            + "recent read.", textOf(result));
    }

    @Test
    void baseVersionOnCreateRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, "1.1", FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("does not exist"), text);
        assertTrue(text.contains("omit base_version"), text);
        assertTrue(loadDocument(oldcore).isNew());
    }

    @Test
    void unknownFieldRefusedListingTheClassFields(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of("nope", "x")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("unknown field \"nope\""), text);
        assertTrue(text.contains(TITLE_FIELD), text);
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void passwordFieldRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of("secret", "hunter2")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        // The refusal follows the same write-path convention as the computed-field one: what failed, that
        // nothing was saved, and the corrective next action.
        assertTrue(text.contains("Cannot set Password field \"secret\": set passwords via the wiki UI. "
            + "Nothing was saved - omit the field and retry."), text);
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void computedFieldRefused(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of("total", "5")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        // The refusal follows the write-path convention: what failed, that nothing was saved, and the
        // corrective next action.
        assertTrue(text.contains("Cannot set field \"total\": it is computed by the class's script and "
            + "has no stored value. Nothing was saved - omit the field and retry."), text);
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void unparseableNumberValueRefusedNamingTheExpectedType(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of(COUNT_FIELD, "abc")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("invalid value \"abc\""), text);
        assertTrue(text.contains("a number of type integer"), text);
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
        // The 17.4 platform parse logs its own WARN for the rejected number before returning null.
        assertTrue(this.logCapture.getMessage(0).contains("Invalid number"), this.logCapture.getMessage(0));
    }

    @Test
    void booleanVocabularyIsPreValidated(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of(PUBLISHED_FIELD, "maybe")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("one of 0, 1, true, false"), text);
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void multiSelectListValueIsSplitOnTheSeparator(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore),
            FIELDS_KEY, Map.of(CATEGORY_FIELD, "News|Personal")));

        assertNotEquals(Boolean.TRUE, result.isError());
        BaseObject object = loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0);
        assertEquals(List.of("News", "Personal"), object.getListValue(CATEGORY_FIELD));
    }

    @Test
    void garbageDateValueRefusedNamingTheClassDateFormat(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of(DATE_FIELD, "not a date")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("invalid value \"not a date\""), text);
        assertTrue(text.contains(DATE_FORMAT), text);
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void validDateValueWrittenAndStored(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of(DATE_FIELD, "31/01/2026")));

        assertNotEquals(Boolean.TRUE, result.isError());
        BaseObject object = loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0);
        assertNotNull(object.getDateValue(DATE_FIELD));
        // The stored date round-trips through the class's own format.
        assertEquals("31/01/2026",
            new java.text.SimpleDateFormat(DATE_FORMAT).format(object.getDateValue(DATE_FIELD)));
    }

    @Test
    void negativeObjectNumberRefusedBeforeTheLookup(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocumentWithObject(oldcore, "First");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, -1, BASE_VERSION_KEY, currentVersion(oldcore),
            FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("non-negative"), textOf(result));
        assertEquals("First", loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0).getStringValue(TITLE_FIELD));
    }

    @Test
    void numberBeyondTheListRefusedListingExistingNumbers(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocumentWithObject(oldcore, "First");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 5, BASE_VERSION_KEY, currentVersion(oldcore),
            FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No object of class \"Blog.BlogPostClass\" at number 5"), text);
        assertTrue(text.contains("Existing object numbers: 0"), text);
    }

    @Test
    void nullHoleRefusedListingTheSurvivingNumbers(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        BaseObject first = doc.newXObject(CLASS_REFERENCE, oldcore.getXWikiContext());
        doc.newXObject(CLASS_REFERENCE, oldcore.getXWikiContext());
        // Removing the first object nulls its slot: number 0 becomes a hole, number 1 survives.
        doc.removeXObject(first);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            OBJECT_KEY, 0, BASE_VERSION_KEY, currentVersion(oldcore),
            FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("at number 0"), text);
        assertTrue(text.contains("Existing object numbers: 1"), text);
    }

    @Test
    void classDefiningNoFieldsRefusedWithSchemaHint(MockitoOldcore oldcore) throws Exception
    {
        XWikiDocument emptyClassDoc = new XWikiDocument(CLASS_REFERENCE);
        oldcore.getSpyXWiki().saveDocument(emptyClassDoc, oldcore.getXWikiContext());
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("defines no class fields"), text);
        assertTrue(text.contains("get_schema"), text);
    }

    @Test
    void missingClassDocumentRefused(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("No such class"), textOf(result));
    }

    @Test
    void crossWikiPrefixedClassRefusedByTheDoor(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenThrow(new MCPAccessDeniedException("Reference \"other:XWiki.XWikiRights\" is in wiki "
                + "\"other\" but the call targets wiki \"xwiki\"; drop the wiki prefix or make them agree."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY,
            "other:XWiki.XWikiRights", BASE_VERSION_KEY, "1.1", FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("drop the wiki prefix"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void sensitiveDocumentRefusedRegardlessOfVersion(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenReturn(WEBPREFS_REFERENCE);
        XWikiDocument doc = new XWikiDocument(WEBPREFS_REFERENCE);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());

        // The base_version is deliberately stale: the denylist fires before the version comparison.
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "Blog.WebPreferences",
            CLASS_KEY, CLASS_REF, BASE_VERSION_KEY, "9.9", FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("defines access rights or wiki configuration"), text);
        assertTrue(text.contains(VIEW_URL), text);
        assertFalse(text.contains("Version conflict"), text);
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), eq("[AI] created Blog.BlogPostClass object 0"),
                anyBoolean(), any());
    }

    @Test
    void sensitiveRightsClassRefusedAndNothingSaved(MockitoOldcore oldcore) throws Exception
    {
        assertSensitiveClassRefused(oldcore, RIGHTS_CLASS_REFERENCE, "XWiki.XWikiRights", "allow");
    }

    @Test
    void sensitiveGroupsClassRefusedAndNothingSaved(MockitoOldcore oldcore) throws Exception
    {
        // A group membership is an XWikiGroups object on the group document: writing one would add an
        // account to a privileged group, the indirect equivalent of editing XWikiRights.
        assertSensitiveClassRefused(oldcore, GROUPS_CLASS_REFERENCE, "XWiki.XWikiGroups", "member");
    }

    @Test
    void sensitiveUsersClassRefusedAndNothingSaved(MockitoOldcore oldcore) throws Exception
    {
        // Writing an XWikiUsers object would provision an account or flip its active/email fields.
        assertSensitiveClassRefused(oldcore, USERS_CLASS_REFERENCE, "XWiki.XWikiUsers", "active");
    }

    private void assertSensitiveClassRefused(MockitoOldcore oldcore, DocumentReference classReference,
        String localName, String field) throws Exception
    {
        storeDocument(oldcore);
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenReturn(classReference);
        when(this.localSerializer.serialize(classReference)).thenReturn(localName);
        String versionBefore = currentVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, localName,
            BASE_VERSION_KEY, versionBefore, FIELDS_KEY, Map.of(field, "1")));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("objects of this class define access rights, group membership, "
            + "user accounts or wiki configuration"), text);
        // The version is unchanged and the object never landed, so the tool saved nothing (the setup's
        // own document save is the only save that ran).
        assertEquals(versionBefore, currentVersion(oldcore));
        assertNull(loadDocument(oldcore).getXObject(classReference, 0));
    }

    @Test
    void doorDenialSurfacesMessageWithoutSaving(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to edit \"" + REF + "\"."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void storageFailureReturnsFixedMessageAndLogsRootCause(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);
        String versionBefore = currentVersion(oldcore);
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_UNKNOWN, "db down"))
            .when(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, versionBefore, FIELDS_KEY, Map.of(TITLE_FIELD, HELLO)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        // The fixed message reaches the wire; the storage internals stay in the server logs.
        assertTrue(text.contains("Could not save the document"), text);
        assertFalse(text.contains("db down"), text);
        assertTrue(this.logCapture.getMessage(0).contains("db down"), this.logCapture.getMessage(0));
        // The mutation happened on the tool's clone, so the stored document still has no object.
        assertNull(loadDocument(oldcore).getXObject(CLASS_REFERENCE, 0));
    }

    @Test
    void agentCommentIsPrefixedAndMajorFlagIsHonored(MockitoOldcore oldcore) throws Exception
    {
        storeClassDocument(oldcore);
        storeDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF,
            BASE_VERSION_KEY, currentVersion(oldcore), FIELDS_KEY, Map.of(TITLE_FIELD, HELLO),
            COMMENT_KEY, "add blog metadata", MAJOR_KEY, true));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The [AI] prefix is always prepended, and major=true records a non-minor version.
        verify(oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class),
            eq("[AI] add blog metadata"), eq(false), any());
        assertEquals("[AI] add blog metadata", loadDocument(oldcore).getComment());
    }

    @Test
    void missingFieldsParameterReturnsRequiredError(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, CLASS_KEY, CLASS_REF));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains(FIELDS_KEY), text);
        assertTrue(text.contains("required"), text);
        verify(this.documentAccess, never()).resolveAndAuthorize(anyString(), any());
    }

    @Test
    void toolDefinitionRequiresReferenceClassAndFields()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();

        assertEquals(MCPWriteObjectTool.TOOL_ID, definition.name());
        List<?> required = (List<?>) definition.inputSchema().get("required");
        assertTrue(required.contains(REFERENCE_KEY), required.toString());
        assertTrue(required.contains(CLASS_KEY), required.toString());
        assertTrue(required.contains(FIELDS_KEY), required.toString());
        assertFalse(required.contains(OBJECT_KEY), required.toString());
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
        assertTrue(this.tool.getSummary().contains("schema-validated"), this.tool.getSummary());
        assertTrue(this.tool.getManPage().contains("EXAMPLES"), this.tool.getManPage());
        assertTrue(this.tool.getManPage().contains("base_version"), this.tool.getManPage());
    }
}
