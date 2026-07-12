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

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.localization.LocalizationContext;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.StringProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.BooleanClass;
import com.xpn.xwiki.objects.classes.ComputedFieldClass;
import com.xpn.xwiki.objects.classes.DateClass;
import com.xpn.xwiki.objects.classes.NumberClass;
import com.xpn.xwiki.objects.classes.PasswordClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.classes.StaticListClass;
import com.xpn.xwiki.objects.classes.StringClass;
import com.xpn.xwiki.objects.classes.TextAreaClass;
import com.xpn.xwiki.web.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPObjectQuerySupport}: the composed statement text is the tool's security-critical
 * contract (only closed-map fragments may reach it), so the broad shapes are pinned as exact-text
 * assertions over real oldcore property classes.
 *
 * @version $Id$
 */
class MCPObjectQuerySupportTest
{
    private static final String CLASS_NAME = "Blog.BlogPostClass";

    private static final String SELECT_PREFIX = "select distinct doc.fullName, obj.number";

    private static final String BASE_FROM = " from XWikiDocument doc, BaseObject obj";

    private static final String BASE_WHERE =
        " where doc.fullName = obj.name and doc.translation = 0 and obj.className = :className";

    private static final String DEFAULT_ORDER = " order by doc.fullName asc, obj.number asc";

    private static final String P0_CLAUSES = " and p0.id.id = obj.id and p0.id.name = :p0name";

    private static final String TITLE_FIELD = "title";

    private static final String AMOUNT_FIELD = "amount";

    private static final String DATE_FIELD = "publishDate";

    private static final String DATE_FORMAT = "dd/MM/yyyy";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    private final XWikiDocument classDoc = mock(XWikiDocument.class);

    @BeforeEach
    void setUp() throws Exception
    {
        // The platform's Date parse resolves the context locale through Utils; without a component
        // manager the lookup failure would spill a WARN per parse. A stubbed manager serves a fixed
        // locale instead.
        ComponentManager componentManager = mock(ComponentManager.class);
        LocalizationContext localizationContext = mock(LocalizationContext.class);
        lenient().when(localizationContext.getCurrentLocale()).thenReturn(Locale.ENGLISH);
        lenient().when(componentManager.getInstance((Type) LocalizationContext.class))
            .thenReturn(localizationContext);
        lenient().when(componentManager.getInstance(eq((Type) LocalizationContext.class), anyString()))
            .thenReturn(localizationContext);
        // Utils resolves the context component manager first and looks the role up through it.
        lenient().when(componentManager.getInstance((Type) ComponentManager.class))
            .thenReturn(componentManager);
        lenient().when(componentManager.getInstance(eq((Type) ComponentManager.class), anyString()))
            .thenReturn(componentManager);
        Utils.setComponentManager(componentManager);
    }

    @AfterEach
    void tearDown()
    {
        Utils.setComponentManager(null);
    }

    private static <T extends PropertyClass> T field(T property, String name, int number)
    {
        property.setName(name);
        property.setPrettyName(name);
        property.setNumber(number);
        return property;
    }

    private static void add(BaseClass xclass, PropertyClass property)
    {
        xclass.addField(property.getName(), property);
        // The platform back-sets the owning class only on stored properties, not on class fields; a
        // loaded class definition carries the back-reference, so the fixture mirrors that state (the
        // platform's own number-parse warning dereferences it).
        property.setObject(xclass);
    }

    private static NumberClass number(String name, String numberType, int position)
    {
        NumberClass property = field(new NumberClass(), name, position);
        property.setNumberType(numberType);
        return property;
    }

    /**
     * A class covering every stored-entity kind: scalar strings, large strings, all four number storages,
     * dates, booleans, single-select lists (string-stored), multi-select lists (list-stored, both
     * serialized and relational), passwords and computed fields.
     */
    private static BaseClass blogClass()
    {
        BaseClass xclass = new BaseClass();
        add(xclass, field(new StringClass(), TITLE_FIELD, 1));
        add(xclass, field(new TextAreaClass(), "content", 2));
        add(xclass, number(AMOUNT_FIELD, "integer", 3));
        add(xclass, number("views", "long", 4));
        add(xclass, number("rating", "float", 5));
        add(xclass, number("price", "double", 6));
        DateClass publishDate = field(new DateClass(), DATE_FIELD, 7);
        publishDate.setDateFormat(DATE_FORMAT);
        add(xclass, publishDate);
        add(xclass, field(new BooleanClass(), "published", 8));
        StaticListClass category = field(new StaticListClass(), "category", 9);
        category.setValues("News|Personal");
        add(xclass, category);
        StaticListClass tags = field(new StaticListClass(), "tags", 10);
        tags.setMultiSelect(true);
        add(xclass, tags);
        StaticListClass keywords = field(new StaticListClass(), "keywords", 11);
        keywords.setMultiSelect(true);
        keywords.setRelationalStorage(true);
        add(xclass, keywords);
        add(xclass, field(new PasswordClass(), "secret", 12));
        add(xclass, field(new ComputedFieldClass(), "total", 13));
        return xclass;
    }

    private XWikiDocument classDoc()
    {
        when(this.classDoc.getXClass()).thenReturn(blogClass());
        return this.classDoc;
    }

    private MCPObjectQuerySupport.CompiledObjectQuery compile(List<String> filters, String sort,
        String documentFullName)
    {
        return MCPObjectQuerySupport.compile(classDoc(), CLASS_NAME, filters, sort, documentFullName);
    }

    private IllegalArgumentException refusal(List<String> filters, String sort)
    {
        return assertThrows(IllegalArgumentException.class, () -> compile(filters, sort, null));
    }

    @Test
    void noFiltersComposeTheBareStatement()
    {
        MCPObjectQuerySupport.CompiledObjectQuery compiled = compile(null, null, null);

        assertEquals(SELECT_PREFIX + BASE_FROM + BASE_WHERE + DEFAULT_ORDER, compiled.statement());
        assertEquals(1, compiled.binds().size());
        assertEquals(CLASS_NAME, compiled.binds().get("className"));
    }

    @Test
    void filterValueIsTheRemainderAndMayContainSpaces()
    {
        MCPObjectQuerySupport.CompiledObjectQuery compiled =
            compile(List.of("title = Hello World Wide"), null, null);

        assertEquals(SELECT_PREFIX + BASE_FROM + ", StringProperty as p0" + BASE_WHERE + P0_CLAUSES
            + " and p0.value = :v0" + DEFAULT_ORDER, compiled.statement());
        assertEquals(TITLE_FIELD, compiled.binds().get("p0name"));
        assertEquals("Hello World Wide", compiled.binds().get("v0"));
    }

    @Test
    void filterEntriesAreTrimmedBeforeParsing()
    {
        MCPObjectQuerySupport.CompiledObjectQuery compiled =
            compile(List.of("   title = x"), "  title asc  ", null);

        assertEquals(TITLE_FIELD, compiled.binds().get("p0name"));
        assertEquals("x", compiled.binds().get("v0"));
        assertEquals(TITLE_FIELD, compiled.binds().get("psname"));
    }

    @Test
    void filterListIsCappedWithATeachingRefusal()
    {
        List<String> oversized = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            oversized.add("title = v" + i);
        }

        IllegalArgumentException error = refusal(oversized, null);

        // Every filter is one more entity join, so the list is capped before any join is composed.
        assertTrue(error.getMessage().contains("'filters' accepts at most 10 entries"), error.getMessage());
    }

    @Test
    void everyOperatorMapsToItsFixedHqlToken()
    {
        assertTrue(compile(List.of("title != x"), null, null).statement()
            .contains("p0.value <> :v0"));
        assertTrue(compile(List.of("amount > 5"), null, null).statement()
            .contains("p0.value > :v0"));
        assertTrue(compile(List.of("amount < 5"), null, null).statement()
            .contains("p0.value < :v0"));
        assertTrue(compile(List.of("title contains x"), null, null).statement()
            .contains("p0.value like :v0"));
    }

    @Test
    void unknownOperatorIsRefusedWithTheOpsList()
    {
        IllegalArgumentException error = refusal(List.of("title ~ x"), null);

        assertTrue(error.getMessage().contains("unknown op \"~\""), error.getMessage());
        assertTrue(error.getMessage().contains("=, !=, >, <, contains"), error.getMessage());
    }

    @Test
    void malformedFilterEntryIsRefusedWithTheGrammar()
    {
        IllegalArgumentException error = refusal(List.of("title ="), null);

        assertTrue(error.getMessage().contains("<field> <op> <value>"), error.getMessage());
    }

    @Test
    void eachPropertyTypeJoinsItsOwnStoredEntity()
    {
        assertTrue(compile(List.of("title = x"), null, null).statement()
            .contains(", StringProperty as p0"));
        assertTrue(compile(List.of("content = x"), null, null).statement()
            .contains(", LargeStringProperty as p0"));
        assertTrue(compile(List.of("amount = 5"), null, null).statement()
            .contains(", IntegerProperty as p0"));
        assertTrue(compile(List.of("views = 5"), null, null).statement()
            .contains(", LongProperty as p0"));
        assertTrue(compile(List.of("rating = 5"), null, null).statement()
            .contains(", FloatProperty as p0"));
        assertTrue(compile(List.of("price = 5"), null, null).statement()
            .contains(", DoubleProperty as p0"));
        assertTrue(compile(List.of("publishDate > 2026-01-01"), null, null).statement()
            .contains(", DateProperty as p0"));
        // A single-select static list stores as a plain string.
        assertTrue(compile(List.of("category = News"), null, null).statement()
            .contains(", StringProperty as p0"));
    }

    @Test
    void numberValuesCoerceToTheFieldsStorageType()
    {
        assertEquals(5, compile(List.of("amount = 5"), null, null).binds().get("v0"));
        assertEquals(5L, compile(List.of("views = 5"), null, null).binds().get("v0"));
        assertEquals(5.5f, compile(List.of("rating = 5.5"), null, null).binds().get("v0"));
        assertEquals(5.5d, compile(List.of("price = 5.5"), null, null).binds().get("v0"));
    }

    @Test
    void invalidNumberValueNamesTheFieldAndItsNumberType()
    {
        IllegalArgumentException error = refusal(List.of("amount = twelve"), null);

        assertTrue(error.getMessage().contains("invalid value \"twelve\""), error.getMessage());
        assertTrue(error.getMessage().contains("field \"amount\""), error.getMessage());
        assertTrue(error.getMessage().contains("a number of type integer"), error.getMessage());
        // The platform's own parse logs the rejection before returning null; the compiler's validation
        // error is the agent-facing signal on top of it.
        assertTrue(this.logCapture.getMessage(0).contains("Invalid number"), this.logCapture.getMessage(0));
    }

    @Test
    void filterValueThrowingOnParseBecomesTheTeachingRefusal()
    {
        // On the 17.10 runtime PropertyClass.fromString throws instead of returning null (XWIKI-20910);
        // the 17.4 build cannot reproduce that, so a stub throws in its place. A RuntimeException is used
        // because Mockito rejects thenThrow of a checked exception fromString does not declare here.
        PropertyClass property = mock(PropertyClass.class);
        when(property.getClassType()).thenReturn("String");
        when(property.getName()).thenReturn(TITLE_FIELD);
        when(property.newProperty()).thenReturn(new StringProperty());
        when(property.fromString(anyString())).thenThrow(new RuntimeException("boom"));
        BaseClass xclass = mock(BaseClass.class);
        when(xclass.get(TITLE_FIELD)).thenReturn(property);
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getXClass()).thenReturn(xclass);

        // parseOrNull turns the throw into null, so coerce raises the teaching refusal rather than letting
        // the XWikiException escape the tool as a raw MCP error.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> MCPObjectQuerySupport.compile(doc, CLASS_NAME, List.of(TITLE_FIELD + " = x"), null, null));

        assertTrue(error.getMessage().contains("invalid value \"x\""), error.getMessage());
        assertTrue(error.getMessage().contains("field \"" + TITLE_FIELD + "\""), error.getMessage());
    }

    @Test
    void booleanVocabularyIsPreValidated()
    {
        assertEquals(1, compile(List.of("published = true"), null, null).binds().get("v0"));
        assertEquals(1, compile(List.of("published = 1"), null, null).binds().get("v0"));
        assertEquals(0, compile(List.of("published = FALSE"), null, null).binds().get("v0"));
        assertEquals(0, compile(List.of("published = 0"), null, null).binds().get("v0"));

        // The platform parse stores garbage as a null value instead of failing, so the vocabulary is the
        // compiler's own check.
        IllegalArgumentException error = refusal(List.of("published = maybe"), null);
        assertTrue(error.getMessage().contains("one of 0, 1, true, false"), error.getMessage());
    }

    @Test
    void dateValueParsesWithTheClassFormat()
    {
        Object bound = compile(List.of("publishDate > 15/06/2026"), null, null).binds().get("v0");

        assertInstanceOf(Date.class, bound);
    }

    @Test
    void dateValueFallsBackToIsoInstantAndIsoDate()
    {
        assertEquals(Date.from(Instant.parse("2026-01-01T10:00:00Z")),
            compile(List.of("publishDate > 2026-01-01T10:00:00Z"), null, null).binds().get("v0"));
        assertEquals(Date.from(Instant.parse("2026-01-01T00:00:00Z")),
            compile(List.of("publishDate > 2026-01-01"), null, null).binds().get("v0"));
    }

    @Test
    void invalidDateValueNamesTheClassFormatAndIso()
    {
        IllegalArgumentException error = refusal(List.of("publishDate > not-a-date"), null);

        assertTrue(error.getMessage().contains("\"" + DATE_FORMAT + "\""), error.getMessage());
        assertTrue(error.getMessage().contains("ISO-8601"), error.getMessage());
    }

    @Test
    void containsWrapsTheRawTextForTheDoorToEscape()
    {
        Object bound = compile(List.of("title contains 50% off"), null, null).binds().get("v0");

        // The raw text, wildcards included: escaping is the door's job at bind time.
        assertEquals(new MCPRowQuery.Contains("50% off"), bound);
    }

    @Test
    void containsOnANonTextFieldIsRefused()
    {
        IllegalArgumentException error = refusal(List.of("amount contains 5"), null);

        assertTrue(error.getMessage().contains("'contains' only applies to text fields"),
            error.getMessage());
        assertTrue(error.getMessage().contains("field \"amount\""), error.getMessage());
    }

    @Test
    void aliasesAndBindNamesAreGeneratedPerFilterPosition()
    {
        MCPObjectQuerySupport.CompiledObjectQuery compiled =
            compile(List.of("title = a", "amount > 2"), null, null);

        assertTrue(compiled.statement().contains(", StringProperty as p0, IntegerProperty as p1"),
            compiled.statement());
        assertTrue(compiled.statement().contains("p1.id.name = :p1name and p1.value > :v1"),
            compiled.statement());
        assertEquals("a", compiled.binds().get("v0"));
        assertEquals(2, compiled.binds().get("v1"));
        assertEquals(AMOUNT_FIELD, compiled.binds().get("p1name"));
    }

    @Test
    void unknownFilterFieldListsTheClassFields()
    {
        IllegalArgumentException error = refusal(List.of("nosuch = x"), null);

        assertTrue(error.getMessage().contains("unknown field \"nosuch\""), error.getMessage());
        assertTrue(error.getMessage().contains(TITLE_FIELD), error.getMessage());
        assertTrue(error.getMessage().contains(DATE_FIELD), error.getMessage());
    }

    @Test
    void passwordAndComputedAndListFieldsAreRefusedAsFilters()
    {
        assertTrue(refusal(List.of("secret = x"), null).getMessage()
            .contains("Cannot filter on Password fields"));
        assertTrue(refusal(List.of("total = x"), null).getMessage()
            .contains("computed fields have no stored values"));
        assertTrue(refusal(List.of("tags = x"), null).getMessage()
            .contains("filtering on list fields is not supported"));
        assertTrue(refusal(List.of("keywords = x"), null).getMessage()
            .contains("filtering on list fields is not supported"));
    }

    @Test
    void sortJoinsItsEntityAddsTheValueColumnAndTiebreaks()
    {
        MCPObjectQuerySupport.CompiledObjectQuery compiled = compile(null, "publishDate desc", null);

        assertEquals("select distinct doc.fullName, obj.number, ps.value" + BASE_FROM
            + ", DateProperty as ps" + BASE_WHERE + " and ps.id.id = obj.id and ps.id.name = :psname"
            + " order by ps.value desc, doc.fullName asc, obj.number asc", compiled.statement());
        assertEquals(DATE_FIELD, compiled.binds().get("psname"));
    }

    @Test
    void sortDirectionIsCaseInsensitiveAndComesFromOwnConstants()
    {
        assertTrue(compile(null, "title ASC", null).statement().contains("order by ps.value asc,"));
        assertTrue(compile(null, "title Desc", null).statement().contains("order by ps.value desc,"));
    }

    @Test
    void malformedSortIsRefused()
    {
        assertTrue(refusal(null, TITLE_FIELD).getMessage().contains("'sort' must be"));
        assertTrue(refusal(null, "title sideways").getMessage().contains("'sort' must be"));
    }

    @Test
    void sortRefusalsNameTheSortAction()
    {
        assertTrue(refusal(null, "secret asc").getMessage().contains("Cannot sort by Password fields"));
        assertTrue(refusal(null, "tags asc").getMessage().contains("Cannot sort by"));
    }

    @Test
    void sortOnALargeTextFieldIsRefused()
    {
        // A large-text sort would put SELECT DISTINCT over a CLOB column, which several DBMS reject; the
        // same field stays perfectly filterable.
        IllegalArgumentException error = refusal(null, "content asc");

        assertTrue(error.getMessage().contains("sorting on large text fields is not supported"),
            error.getMessage());
        assertTrue(compile(List.of("content = x"), null, null).statement()
            .contains(", LargeStringProperty as p0"));
    }

    @Test
    void documentRestrictionIsABoundClause()
    {
        MCPObjectQuerySupport.CompiledObjectQuery compiled = compile(null, null, "Blog.MyPost");

        assertTrue(compiled.statement().contains(" and doc.fullName = :docFullName order by"),
            compiled.statement());
        assertEquals("Blog.MyPost", compiled.binds().get("docFullName"));
    }

    @Test
    void selectValidationCapsTheListAndChecksTheFields()
    {
        XWikiDocument document = classDoc();

        MCPObjectQuerySupport.validateSelect(document, null);
        MCPObjectQuerySupport.validateSelect(document, List.of(TITLE_FIELD, "secret", "total"));

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
            () -> MCPObjectQuerySupport.validateSelect(document, List.of("nosuch")));
        assertTrue(unknown.getMessage().contains("unknown field \"nosuch\""), unknown.getMessage());

        List<String> oversized = List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11");
        IllegalArgumentException capped = assertThrows(IllegalArgumentException.class,
            () -> MCPObjectQuerySupport.validateSelect(document, oversized));
        assertTrue(capped.getMessage().contains("at most 10"), capped.getMessage());
    }

    @Test
    void definesNoFieldsDetectsANonClassDocument()
    {
        XWikiDocument plainPage = mock(XWikiDocument.class);
        when(plainPage.getXClass()).thenReturn(new BaseClass());

        assertTrue(MCPObjectQuerySupport.definesNoFields(plainPage));
        assertFalse(MCPObjectQuerySupport.definesNoFields(classDoc()));
    }
}
