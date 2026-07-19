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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPSourceText;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.contrib.llm.mcp.internal.access.DefaultMCPRowQuery;
import org.xwiki.contrib.llm.mcp.internal.access.MCPSpaceFilter;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.query.QueryParameter;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.ComputedFieldClass;
import com.xpn.xwiki.objects.classes.DateClass;
import com.xpn.xwiki.objects.classes.PasswordClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.classes.StaticListClass;
import com.xpn.xwiki.objects.classes.StringClass;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPQueryObjectsTool}.
 *
 * <p>The real {@link DefaultMCPRowQuery} door is registered between the tool and the mocked query manager,
 * resolver, space filter and authorization manager, so the compiled page and count statements flow textually
 * into the in-memory query fake and the per-row authorization stays meaningful. Result documents are mock
 * shells carrying REAL oldcore objects and class definitions, so the value rendering exercises the real
 * property types.</p>
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList(DefaultMCPRowQuery.class)
class MCPQueryObjectsToolTest extends AbstractMCPToolTest
{
    private static final String WIKI = "xwiki";

    private static final String SECOND = "second";

    private static final String BLOG_CLASS = "Blog.BlogPostClass";

    private static final String POST_ONE = "Blog.Post1";

    private static final String POST_SECRET = "Secret.Post";

    private static final String TITLE_FIELD = "title";

    private static final String SECRET_VALUE = "hunter2";

    private static final Date PUBLISHED = Date.from(Instant.parse("2026-01-01T10:00:00Z"));

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPQueryObjectsTool tool;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> currentResolver;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @MockComponent
    private ContextualAuthorizationManager authorization;

    @MockComponent
    private MCPSpaceFilter spaceFilter;

    @MockComponent
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private MCPWikiReach wikiReach;

    private final List<Object[]> resultRows = new ArrayList<>();

    private final List<String> statements = new ArrayList<>();

    private final List<String> wikisSet = new ArrayList<>();

    private final List<Integer> limitsSet = new ArrayList<>();

    private final List<Integer> offsetsSet = new ArrayList<>();

    private final Map<String, Object> boundValues = new HashMap<>();

    private final List<String> escapedBindNames = new ArrayList<>();

    private final List<String> escapedLiterals = new ArrayList<>();

    private XWikiDocument classDoc;

    private DocumentReference classRef;

    @BeforeEach
    void setUp() throws Exception
    {
        lenient().when(this.wikiReach.resolveSingleWiki(any())).thenAnswer(invocation -> {
            String wikiParam = invocation.getArgument(0);
            return StringUtils.isBlank(wikiParam) ? WIKI : wikiParam;
        });

        lenient().when(this.spaceFilter.isAllowed(any())).thenReturn(true);
        lenient().when(this.authorization.hasAccess(eq(Right.VIEW), any())).thenReturn(true);

        lenient().when(this.currentResolver.resolve(anyString(), any()))
            .thenAnswer(invocation -> parseRef(invocation.getArgument(0), invocation.getArgument(1)));

        lenient().when(this.localSerializer.serialize(any(EntityReference.class)))
            .thenAnswer(invocation -> localName(invocation.getArgument(0)));

        lenient().when(this.queryManager.createQuery(anyString(), eq(Query.HQL)))
            .thenAnswer(invocation -> buildQueryMock(invocation.getArgument(0)));
    }

    private Query buildQueryMock(String statement) throws Exception
    {
        this.statements.add(statement);
        Query query = mock(Query.class);
        lenient().when(query.setWiki(any())).thenAnswer(invocation -> {
            this.wikisSet.add(invocation.getArgument(0));
            return query;
        });
        lenient().when(query.setLimit(anyInt())).thenAnswer(invocation -> {
            this.limitsSet.add(invocation.getArgument(0));
            return query;
        });
        lenient().when(query.setOffset(anyInt())).thenAnswer(invocation -> {
            this.offsetsSet.add(invocation.getArgument(0));
            return query;
        });
        lenient().when(query.bindValue(anyString(), any())).thenAnswer(invocation -> {
            this.boundValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return query;
        });
        // The escaping parameter chain the door uses for Contains binds: names and literal texts are
        // recorded so a contains filter can be pinned end-to-end through the real door.
        QueryParameter parameter = mock(QueryParameter.class);
        lenient().when(parameter.anyChars()).thenReturn(parameter);
        lenient().when(parameter.literal(anyString())).thenAnswer(invocation -> {
            this.escapedLiterals.add(invocation.getArgument(0));
            return parameter;
        });
        lenient().when(parameter.query()).thenReturn(query);
        lenient().when(query.bindValue(anyString())).thenAnswer(invocation -> {
            this.escapedBindNames.add(invocation.getArgument(0));
            return parameter;
        });
        lenient().when(query.execute()).thenAnswer(invocation -> new ArrayList<>(this.resultRows));
        return query;
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    private static <T extends PropertyClass> T field(T property, String name, int number)
    {
        property.setName(name);
        property.setPrettyName(name);
        property.setNumber(number);
        return property;
    }

    private static BaseClass blogClass()
    {
        BaseClass xclass = new BaseClass();
        xclass.addField(TITLE_FIELD, field(new StringClass(), TITLE_FIELD, 1));
        xclass.addField("subtitle", field(new StringClass(), "subtitle", 2));
        DateClass publishDate = field(new DateClass(), "publishDate", 3);
        publishDate.setDateFormat("dd/MM/yyyy");
        xclass.addField("publishDate", publishDate);
        xclass.addField("secret", field(new PasswordClass(), "secret", 4));
        xclass.addField("total", field(new ComputedFieldClass(), "total", 5));
        StaticListClass tags = field(new StaticListClass(), "tags", 6);
        tags.setMultiSelect(true);
        xclass.addField("tags", tags);
        return xclass;
    }

    /**
     * Stubs the loaded class document behind the access door: resolve-and-authorize passes and the bridge
     * loads an {@link XWikiDocument} mock carrying the given class definition.
     */
    private void stubClassDocument(String wiki, BaseClass xclass) throws Exception
    {
        this.classRef = parseRef(BLOG_CLASS, new WikiReference(wiki));
        lenient().when(this.documentAccess.resolveAndAuthorize(BLOG_CLASS, Right.VIEW,
            new WikiReference(wiki))).thenReturn(this.classRef);
        this.classDoc = mock(XWikiDocument.class);
        lenient().when(this.classDoc.isNew()).thenReturn(false);
        lenient().when(this.classDoc.getXClass()).thenReturn(xclass);
        lenient().when(this.documentAccessBridge.getDocumentInstance(this.classRef))
            .thenReturn(this.classDoc);
    }

    /**
     * Stubs one result row: the store row, the loaded document shell (version and title) and a REAL
     * {@link BaseObject} the value rendering reads.
     */
    private BaseObject stubResult(String fullName, int number, String wiki, String title) throws Exception
    {
        this.resultRows.add(new Object[] {fullName, number});
        return stubResultDoc(fullName, number, wiki, title);
    }

    /**
     * Stubs the loaded document shell behind one result reference without adding a store row, so a test
     * can shape the row columns itself (e.g. the 3-column rows of a sorted page).
     */
    private BaseObject stubResultDoc(String fullName, int number, String wiki, String title) throws Exception
    {
        DocumentReference ref = parseRef(fullName, new WikiReference(wiki));
        XWikiDocument resultDoc = mock(XWikiDocument.class);
        lenient().when(resultDoc.getVersion()).thenReturn("3.1");
        lenient().when(resultDoc.getTitle()).thenReturn(title);
        BaseObject object = new BaseObject();
        lenient().when(resultDoc.getXObject(this.classRef, number)).thenReturn(object);
        lenient().when(this.documentAccessBridge.getDocumentInstance(ref)).thenReturn(resultDoc);
        return object;
    }

    @Test
    void statementAndBindsArePinned() throws Exception
    {
        stubClassDocument(WIKI, blogClass());

        callText(Map.of("class", BLOG_CLASS, "filters", List.of("title = Hello World")));

        assertEquals(1, this.statements.size(), this.statements.toString());
        assertEquals("select distinct doc.fullName, obj.number from XWikiDocument doc, BaseObject obj, "
            + "StringProperty as p0 where doc.fullName = obj.name and doc.translation = 0 and "
            + "obj.className = :className and p0.id.id = obj.id and p0.id.name = :p0name and "
            + "p0.value = :v0 order by doc.fullName asc, obj.number asc", this.statements.get(0));
        assertEquals(BLOG_CLASS, this.boundValues.get("className"));
        assertEquals(TITLE_FIELD, this.boundValues.get("p0name"));
        assertEquals("Hello World", this.boundValues.get("v0"));
        assertTrue(this.wikisSet.contains(WIKI), this.wikisSet.toString());
        // The scan always requests the door's full ceiling: paging happens on the authorized rows, never
        // in the store.
        assertEquals(List.of(2000), this.limitsSet);
    }

    @Test
    void resultsRenderHeaderTitleAndSelectedValues() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        BaseObject object = stubResult(POST_ONE, 0, WIKI, "My Post");
        object.setStringValue(TITLE_FIELD, "Hello");
        object.setDateValue("publishDate", PUBLISHED);
        object.setStringValue("secret", SECRET_VALUE);
        object.setStringListValue("tags", List.of("a", "b"));

        String output = callText(Map.of("class", BLOG_CLASS,
            "select", List.of(TITLE_FIELD, "publishDate", "secret", "total", "tags", "subtitle")));

        assertTrue(output.contains("Blog.Post1 (object 0, v3.1) \"My Post\""), output);
        assertTrue(output.contains("\n  title: Hello\n"), output);
        assertTrue(output.contains("\n  publishDate: 2026-01-01T10:00:00Z\n"), output);
        // The Password value is masked without ever being read; the stored secret must not reach the wire.
        assertTrue(output.contains("\n  secret: (masked)\n"), output);
        assertFalse(output.contains(SECRET_VALUE), output);
        assertTrue(output.contains("\n  total: (computed; not shown)\n"), output);
        assertTrue(output.contains("\n  tags: a, b\n"), output);
        assertTrue(output.contains("\n  subtitle: (unset)"), output);
        assertTrue(output.contains("Found 1 matching object. Showing 1 from offset 0."), output);
    }

    @Test
    void withoutSelectOnlyHeadersRender() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        stubResult(POST_ONE, 2, WIKI, null);

        String output = callText(Map.of("class", BLOG_CLASS));

        assertTrue(output.contains("Blog.Post1 (object 2, v3.1)"), output);
        assertFalse(output.contains("(unset)"), output);
    }

    @Test
    void deniedRowIsDroppedEntirelyAndTheTotalCountsOnlyViewableMatches() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        stubResult(POST_ONE, 0, WIKI, null);
        stubResult(POST_SECRET, 0, WIKI, null);
        // The Secret space is walled off: the row fails the door's per-row authorization, so it is dropped
        // before its document is ever read - and before it is counted.
        when(this.spaceFilter.isAllowed(new DocumentReference(WIKI, "Secret", "Post"))).thenReturn(false);

        String output = callText(Map.of("class", BLOG_CLASS));

        assertTrue(output.contains(POST_ONE), output);
        assertFalse(output.contains(POST_SECRET), output);
        assertTrue(output.contains("Found 1 matching object. Showing 1 from offset 0."), output);
    }

    @Test
    void deniedMatchesLeaveNoCountableTrace() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        stubResult(POST_SECRET, 0, WIKI, null);
        stubResult("Secret.Post2", 0, WIKI, null);
        when(this.spaceFilter.isAllowed(any())).thenReturn(false);

        String output = callText(Map.of("class", BLOG_CLASS, "filters", List.of("title = Hello")));

        // The oracle closure: rows matching the agent-chosen predicate exist but are all rights-denied, so
        // the response reports plain zero matches - no count of denied rows appears anywhere, and count
        // differences reveal nothing about values in documents the caller cannot view.
        assertEquals("No objects of class \"Blog.BlogPostClass\" found."
            + " Active filters: filters=[title = Hello].", output);
    }

    @Test
    void zeroResultsEchoTheActiveFilters() throws Exception
    {
        stubClassDocument(WIKI, blogClass());

        String output = callText(Map.of("class", BLOG_CLASS,
            "filters", List.of("title = Nope"), "sort", "title asc", "document", POST_ONE));

        assertEquals("No objects of class \"Blog.BlogPostClass\" found."
            + " Active filters: filters=[title = Nope], document=Blog.Post1, sort=title asc.", output);
    }

    @Test
    void offsetBeyondTheLastAuthorizedMatchIsAPagingError() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        for (int i = 0; i < 5; i++) {
            this.resultRows.add(new Object[] {"Blog.Post" + i, 0});
        }

        McpSchema.CallToolResult result = call(Map.of("class", BLOG_CLASS, "offset", 10));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("offset 10 is beyond the last match (5 total matches). Use an offset below 5.",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void limitIsCappedAndTheFooterSaysSo() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        for (int i = 0; i < 30; i++) {
            stubResult("Blog.Post" + i, 0, WIKI, null);
        }

        String output = callText(Map.of("class", BLOG_CLASS, "limit", 100));

        // The requested limit never reaches the store (the scan always fetches up to the ceiling); the
        // cap applies to the rendered page, and the notice only shows because matches were actually hidden.
        assertEquals(List.of(2000), this.limitsSet);
        assertTrue(output.contains("Found 30 matching objects. Showing 25 from offset 0."), output);
        assertTrue(output.contains("Continue with offset=25."), output);
        assertTrue(output.contains("(The requested limit was capped to the maximum of 25 per page.)"),
            output);
    }

    @Test
    void offsetPagesTheAuthorizedRowsClientSide() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        for (int i = 0; i < 5; i++) {
            stubResult("Blog.Post" + i, 0, WIKI, null);
        }

        String output = callText(Map.of("class", BLOG_CLASS, "offset", 3));

        // Paging happens on the authorized list, never in the store: no offset is ever bound.
        assertTrue(this.offsetsSet.isEmpty(), this.offsetsSet.toString());
        assertTrue(output.contains("Blog.Post3 "), output);
        assertTrue(output.contains("Blog.Post4 "), output);
        assertFalse(output.contains("Blog.Post0 "), output);
        assertTrue(output.contains("Found 5 matching objects. Showing 2 from offset 3."), output);
        assertFalse(output.contains("Continue with offset="), output);
    }

    @Test
    void negativeOffsetIsRejected() throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of("class", BLOG_CLASS, "offset", -1));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("Error: 'offset' must be >= 0.",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void documentRestrictionIsAuthorizedAndBound() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        DocumentReference postRef = parseRef(POST_ONE, new WikiReference(WIKI));
        when(this.documentAccess.resolveAndAuthorize(POST_ONE, Right.VIEW, new WikiReference(WIKI)))
            .thenReturn(postRef);

        callText(Map.of("class", BLOG_CLASS, "document", POST_ONE));

        assertTrue(this.statements.get(0).contains(" and doc.fullName = :docFullName order by"),
            this.statements.get(0));
        assertEquals(POST_ONE, this.boundValues.get("docFullName"));
    }

    /**
     * Stubs the resolved, authorized and loadable document of a document-only inventory call: the access
     * door answers with the parsed reference and the bridge loads a shell reporting the given version.
     */
    private DocumentReference stubInventoryDocument(String fullName, String version) throws Exception
    {
        DocumentReference ref = parseRef(fullName, new WikiReference(WIKI));
        when(this.documentAccess.resolveAndAuthorize(fullName, Right.VIEW, new WikiReference(WIKI)))
            .thenReturn(ref);
        XWikiDocument doc = mock(XWikiDocument.class);
        lenient().when(doc.getVersion()).thenReturn(version);
        lenient().when(this.documentAccessBridge.getDocumentInstance(ref)).thenReturn(doc);
        return ref;
    }

    @Test
    void documentOnlyCallListsEveryObjectGroupedByClass() throws Exception
    {
        stubInventoryDocument(POST_ONE, "3.2");
        this.resultRows.add(new Object[] {POST_ONE, 0, BLOG_CLASS});
        this.resultRows.add(new Object[] {POST_ONE, 2, BLOG_CLASS});
        this.resultRows.add(new Object[] {POST_ONE, 0, "XWiki.XWikiComments"});

        String output = callText(Map.of("document", POST_ONE));

        // The any-class statement is the per-class one minus the class bind, ordered for class grouping.
        assertEquals("select distinct doc.fullName, obj.number, obj.className from XWikiDocument doc, "
            + "BaseObject obj where doc.fullName = obj.name and doc.translation = 0 and "
            + "doc.fullName = :docFullName order by obj.className asc, doc.fullName asc, obj.number asc",
            this.statements.get(0));
        assertEquals(POST_ONE, this.boundValues.get("docFullName"));
        assertFalse(this.boundValues.containsKey("className"), this.boundValues.toString());
        // The grouped inventory: class, its object numbers, the document version - and no field values.
        assertEquals("""
            OBJECTS on document "Blog.Post1" (v3.2) in wiki "xwiki":

            Blog.BlogPostClass
              object 0
              object 2
            XWiki.XWikiComments
              object 0

            Found 3 matching objects. Showing 3 from offset 0.""", output);
    }

    @Test
    void documentOnlyCallWithoutObjectsSaysSo() throws Exception
    {
        stubInventoryDocument(POST_ONE, "1.1");

        String output = callText(Map.of("document", POST_ONE));

        assertEquals("Document \"Blog.Post1\" carries no objects.", output);
    }

    @Test
    void inventoryOfNonexistentDocumentGetsTheNoSuchDocumentError() throws Exception
    {
        DocumentReference ref = parseRef(POST_ONE, new WikiReference(WIKI));
        when(this.documentAccess.resolveAndAuthorize(POST_ONE, Right.VIEW, new WikiReference(WIKI)))
            .thenReturn(ref);
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.isNew()).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(ref)).thenReturn(doc);

        McpSchema.CallToolResult result = call(Map.of("document", POST_ONE));

        // A mistyped reference is a not-found error (the class-scoped mode's exact message shape), never
        // a claim that an existing page carries no objects.
        assertTrue(result.isError(), "expected an error result");
        assertEquals("No such document: \"Blog.Post1\".",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void emptyInventoryEchoIsTheNeutralizedSerializedReference() throws Exception
    {
        String hostile = "Blog.Post\nInjected line";
        DocumentReference ref = parseRef(hostile, new WikiReference(WIKI));
        when(this.documentAccess.resolveAndAuthorize(hostile, Right.VIEW, new WikiReference(WIKI)))
            .thenReturn(ref);
        XWikiDocument doc = mock(XWikiDocument.class);
        lenient().when(doc.isNew()).thenReturn(false);
        when(this.documentAccessBridge.getDocumentInstance(ref)).thenReturn(doc);

        String output = callText(Map.of("document", hostile));

        // The echo is the serialized resolved reference through the fragment guard, not the raw
        // argument: a newline smuggled into the parameter cannot forge an extra output line.
        assertEquals("Document \"Blog.PostInjected line\" carries no objects.", output);
    }

    @Test
    void noSuchClassEchoIsNeutralized() throws Exception
    {
        String hostile = "Blog.BlogPostClass\nInjected line";
        DocumentReference ref = parseRef(hostile, new WikiReference(WIKI));
        when(this.documentAccess.resolveAndAuthorize(hostile, Right.VIEW, new WikiReference(WIKI)))
            .thenReturn(ref);
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.isNew()).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(ref)).thenReturn(doc);

        McpSchema.CallToolResult result = call(Map.of("class", hostile));

        // The raw class argument is echoed through the fragment guard: a newline smuggled into the
        // parameter cannot forge an extra line of the tool's output grammar.
        assertTrue(result.isError(), "expected an error result");
        assertEquals("No such document: \"Blog.BlogPostClassInjected line\".",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void inventoryPageStartingMidClassReEmitsTheClassHeader() throws Exception
    {
        stubInventoryDocument(POST_ONE, "3.2");
        this.resultRows.add(new Object[] {POST_ONE, 0, BLOG_CLASS});
        this.resultRows.add(new Object[] {POST_ONE, 1, BLOG_CLASS});
        this.resultRows.add(new Object[] {POST_ONE, 2, BLOG_CLASS});

        String output = callText(Map.of("document", POST_ONE, "limit", 2, "offset", 1));

        // The grouping state resets per page: a page slice starting mid-class re-emits the class header,
        // so no page ever shows bare object lines without their class.
        assertEquals("""
            OBJECTS on document "Blog.Post1" (v3.2) in wiki "xwiki":

            Blog.BlogPostClass
              object 1
              object 2

            Found 3 matching objects. Showing 2 from offset 1.""", output);
    }

    @Test
    void classScopedParametersAreRefusedWithoutClass() throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of("document", POST_ONE, "sort", "title asc"));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("Error: 'select', 'filters' and 'sort' are class-scoped; pass 'class' to use them. "
            + "A document-only call lists only each object's class and number.",
            ((McpSchema.TextContent) result.content().get(0)).text());
        verify(this.queryManager, never()).createQuery(anyString(), anyString());
    }

    @Test
    void documentDenialSurfacesTheDoorMessageAndInventoriesNothing() throws Exception
    {
        // Same door as the class-scoped document restriction: a denied document surfaces the door's
        // message and the store is never queried, so a protected page's object inventory never leaks.
        when(this.documentAccess.resolveAndAuthorize(POST_SECRET, Right.VIEW, new WikiReference(WIKI)))
            .thenThrow(new MCPAccessDeniedException("Access denied."));

        McpSchema.CallToolResult result = call(Map.of("document", POST_SECRET));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("Access denied.", ((McpSchema.TextContent) result.content().get(0)).text());
        verify(this.queryManager, never()).createQuery(anyString(), anyString());
    }

    @Test
    void filterValidationErrorsSurfaceAsErrorResults() throws Exception
    {
        stubClassDocument(WIKI, blogClass());

        McpSchema.CallToolResult result =
            call(Map.of("class", BLOG_CLASS, "filters", List.of("title ~ x")));

        assertTrue(result.isError(), "expected an error result");
        assertTrue(((McpSchema.TextContent) result.content().get(0)).text().contains("unknown op"),
            ((McpSchema.TextContent) result.content().get(0)).text());
        verify(this.queryManager, never()).createQuery(anyString(), anyString());
    }

    @Test
    void missingClassParameterIsRejected()
    {
        McpSchema.CallToolResult result = call(Map.of());

        assertTrue(result.isError(), "expected an error result");
        // Class stays required when no document is given; the error teaches the document-only mode.
        assertEquals("Error: 'class' parameter is required unless 'document' is given: a document-only "
            + "call lists every object on that document.",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void missingClassDocumentIsNotFound() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        when(this.classDoc.isNew()).thenReturn(true);

        McpSchema.CallToolResult result = call(Map.of("class", BLOG_CLASS));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("No such document: \"Blog.BlogPostClass\".",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void nonClassDocumentPointsAtTheCatalog() throws Exception
    {
        stubClassDocument(WIKI, new BaseClass());

        McpSchema.CallToolResult result = call(Map.of("class", BLOG_CLASS));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("Document \"Blog.BlogPostClass\" exists but defines no class fields. Use get_schema "
            + "with no arguments to list the classes of this wiki.",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void classDenialSurfacesTheDoorMessageAndQueriesNothing() throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(eq(BLOG_CLASS), eq(Right.VIEW),
            any(WikiReference.class))).thenThrow(new MCPAccessDeniedException("Access denied."));

        McpSchema.CallToolResult result = call(Map.of("class", BLOG_CLASS));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("Access denied.", ((McpSchema.TextContent) result.content().get(0)).text());
        verify(this.queryManager, never()).createQuery(anyString(), anyString());
    }

    @Test
    void classLoadFailureReturnsFixedErrorAndLogsRootCauseWithoutStackTrace() throws Exception
    {
        DocumentReference ref = parseRef(BLOG_CLASS, new WikiReference(WIKI));
        when(this.documentAccess.resolveAndAuthorize(BLOG_CLASS, Right.VIEW, new WikiReference(WIKI)))
            .thenReturn(ref);
        when(this.documentAccessBridge.getDocumentInstance(ref)).thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result = call(Map.of("class", BLOG_CLASS));

        assertTrue(result.isError(), "expected an error result");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertEquals("Could not read document \"Blog.BlogPostClass\".", text);
        assertFalse(text.contains("boom"), "Root cause must not leak into the agent-facing message");
        String warn = this.logCapture.getMessage(0);
        assertTrue(warn.contains("MCP query_objects tool failed to load"), warn);
        assertTrue(warn.contains("boom"), warn);
        assertFalse(warn.contains("\tat "), "WARN message must not contain a stack frame");
        assertFalse(warn.contains("at org."), "WARN message must not contain a stack frame");
    }

    @Test
    void queryFailureReturnsFixedErrorAndKeepsRootCauseOffTheWire() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        when(this.queryManager.createQuery(anyString(), eq(Query.HQL)))
            .thenThrow(new QueryException("boom", null, new IllegalStateException("schema detail")));

        McpSchema.CallToolResult result = call(Map.of("class", BLOG_CLASS));

        assertTrue(result.isError(), "expected an error result");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertEquals("Could not run the object query. Try again; if it persists, report it to a wiki "
            + "administrator (details are in the server logs).", text);
        assertFalse(text.contains("schema detail"), text);
        assertTrue(this.logCapture.getMessage(0).contains("MCP query_objects tool failed"),
            this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("schema detail"), this.logCapture.getMessage(0));
    }

    @Test
    void crossWikiCallQueriesAndAuthorizesInTheTargetWiki() throws Exception
    {
        stubClassDocument(SECOND, blogClass());
        stubResult(POST_ONE, 0, SECOND, null);

        String output = callText(Map.of("class", BLOG_CLASS, "wiki", SECOND));

        assertTrue(output.contains("in wiki \"second\""), output);
        assertTrue(this.wikisSet.contains(SECOND), this.wikisSet.toString());
        assertFalse(this.wikisSet.contains(WIKI), this.wikisSet.toString());
        // The regression guard of the cross-wiki row bug: the result document authorizes in the target
        // wiki, not the context wiki (once for the total, once as the page-render guard).
        verify(this.authorization, atLeastOnce())
            .hasAccess(Right.VIEW, new DocumentReference(SECOND, "Blog", "Post1"));
    }

    @Test
    void outputBeyondTheBudgetIsTruncatedWithTheNoticeAndKeepsTheFooter() throws Exception
    {
        // A class of ten wide string fields: one selected page of 25 such objects far exceeds the shared
        // output budget, so the body must be cut at a line boundary while the footer survives the cut.
        BaseClass wide = new BaseClass();
        List<String> select = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            wide.addField("w" + i, field(new StringClass(), "w" + i, i + 1));
            select.add("w" + i);
        }
        stubClassDocument(WIKI, wide);
        for (int i = 0; i < 200; i++) {
            BaseObject object = stubResult("Blog.Post" + i, 0, WIKI, null);
            for (int f = 0; f < 10; f++) {
                object.setStringValue("w" + f, ("value" + i + " ").repeat(40));
            }
        }

        String output = callText(Map.of("class", BLOG_CLASS, "limit", 25, "select", select));

        assertTrue(output.contains("Output truncated at the ~6000-token cap."), output);
        assertTrue(output.contains("Found 200 matching objects. Showing 25 from offset 0."), output);
        assertTrue(output.length() <= MCPSourceText.MAX_OUTPUT_CHARS + 500, "length: " + output.length());
    }

    @Test
    void rawScanAtTheCeilingReportsTheTotalAsAFloor() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        for (int i = 0; i < 10; i++) {
            stubResult("Blog.Post" + i, 0, WIKI, null);
        }
        for (int i = 10; i < 2000; i++) {
            this.resultRows.add(new Object[] {"Blog.Post" + i, 0});
        }

        String output = callText(Map.of("class", BLOG_CLASS));

        // A raw fetch at the door ceiling means matches beyond it were never scanned: the authorized
        // total is a floor, rendered as N+ with the honesty note.
        assertTrue(output.contains("Found 2000+ matching objects. Showing 10 from offset 0."), output);
        assertTrue(output.contains("Continue with offset=10."), output);
        assertTrue(output.contains(
            "The scan hit the 2000-row fetch ceiling: matches beyond it are not counted."), output);
    }

    @Test
    void sortedPageRendersTheThreeColumnRows() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        BaseObject object = stubResultDoc(POST_ONE, 0, WIKI, null);
        object.setStringValue(TITLE_FIELD, "Hello");
        // A sorted statement selects the sort value as a third column (a DISTINCT requirement); the
        // renderer must read only the first two.
        this.resultRows.add(new Object[] {POST_ONE, 0, PUBLISHED});

        String output = callText(Map.of("class", BLOG_CLASS, "sort", "publishDate desc",
            "select", List.of(TITLE_FIELD)));

        assertTrue(this.statements.get(0).contains(
            "select distinct doc.fullName, obj.number, ps.value"), this.statements.get(0));
        assertTrue(this.statements.get(0).contains(
            "order by ps.value desc, doc.fullName asc, obj.number asc"), this.statements.get(0));
        assertEquals("publishDate", this.boundValues.get("psname"));
        assertTrue(output.contains("Blog.Post1 (object 0, v3.1)"), output);
        assertTrue(output.contains("\n  title: Hello"), output);
    }

    @Test
    void containsFilterFlowsThroughTheDoorsEscapingChain() throws Exception
    {
        stubClassDocument(WIKI, blogClass());
        stubResult(POST_ONE, 0, WIKI, null);

        String output = callText(Map.of("class", BLOG_CLASS,
            "filters", List.of("title contains 50% off")));

        // The support wraps the raw text in a Contains and the real door binds it through the escaping
        // parameter API: the raw literal (wildcards included) goes to the platform escaper, and the value
        // is never bound as a plain parameter.
        assertTrue(this.statements.get(0).contains("p0.value like :v0"), this.statements.get(0));
        assertEquals(List.of("v0"), this.escapedBindNames);
        assertEquals(List.of("50% off"), this.escapedLiterals);
        assertFalse(this.boundValues.containsKey("v0"), this.boundValues.toString());
        assertTrue(output.contains(POST_ONE), output);
    }

    @Test
    void reachOnAdvertisesWikiParameter()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);

        Map<?, ?> properties = (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");

        assertTrue(properties.containsKey("wiki"), properties.keySet().toString());
    }

    @Test
    void reachOffDropsWikiParameterFromAdvertisedSchema()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        Map<?, ?> properties = (Map<?, ?>) this.tool.getToolDefinition().inputSchema().get("properties");

        assertFalse(properties.containsKey("wiki"), properties.keySet().toString());
    }

    @Test
    void manPageDocumentsTheWikiParameterOnlyWhenReachIsOn()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);
        String manPage = this.tool.getManPage();
        assertTrue(manPage.contains("The wiki parameter queries another wiki"), manPage);
        assertTrue(manPage.contains("wiki=\"second\""), manPage);

        when(this.wikiReach.isReachEnabled()).thenReturn(false);
        String localPage = this.tool.getManPage();
        // The man prose must match the advertised schema: a reach-off endpoint does not carry the wiki
        // parameter, so its manual must not teach it either.
        assertFalse(localPage.contains("wiki parameter"), localPage);
        assertFalse(localPage.contains("Another wiki"), localPage);
        assertTrue(localPage.contains("EXAMPLES"), localPage);
        assertTrue(localPage.contains("SEE ALSO"), localPage);
    }

    @Test
    void manPageTeachesTheGrammarAndTheCaveats()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        String manPage = this.tool.getManPage();
        assertTrue(manPage.contains("\"<field> <op> <value>\""), manPage);
        assertTrue(manPage.contains("contains    substring match, text fields only"), manPage);
        assertTrue(manPage.contains("EXCLUDED from sorted results"), manPage);
        assertTrue(manPage.contains("Counts are exact over what you may view"), manPage);
        assertTrue(manPage.contains("the count then reads \"N+\""), manPage);
        assertTrue(manPage.contains("Hidden documents are included"), manPage);
        assertTrue(manPage.contains("compares as UTC midnight"), manPage);
        assertTrue(manPage.contains("(object <N>, v<version>)"), manPage);
        assertTrue(manPage.contains("what objects does this page carry?"), manPage);
        assertTrue(manPage.contains("No field values are shown in this mode"), manPage);
        assertTrue(manPage.contains("man get_schema"), manPage);
        assertTrue(manPage.contains("man query_documents"), manPage);
    }

    @Test
    void toolMetadataIsSet()
    {
        assertEquals(MCPQueryObjectsTool.TOOL_ID, this.tool.getToolDefinition().name());
        assertEquals("Structured Data", this.tool.getCategory());
        assertFalse(this.tool.isWrite());
        assertTrue(this.tool.isEnabled());
        assertEquals("Find structured objects (XObjects) by class and field filters.",
            this.tool.getSummary());
    }
}
