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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Provider;

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
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPGetSchemaTool}.
 *
 * <p>The real {@link DefaultMCPRowQuery} door is registered between the tool and the mocked query manager,
 * resolver, space filter and authorization manager, so the catalog and count HQL statements flow textually
 * into the in-memory query fake and the per-row class-document authorization stays meaningful.</p>
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList(DefaultMCPRowQuery.class)
class MCPGetSchemaToolTest extends AbstractMCPToolTest
{
    private static final String WIKI = "xwiki";

    private static final String SECOND = "second";

    private static final String BLOG_CLASS = "Blog.BlogPostClass";

    private static final String USERS_CLASS = "XWiki.XWikiUsers";

    private static final String HIDDEN_CLASS = "Secret.HiddenClass";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPGetSchemaTool tool;

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

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    private final List<Object[]> catalogRows = new ArrayList<>();

    private final List<Object> countRows = new ArrayList<>();

    private final List<String> wikisSet = new ArrayList<>();

    private final Map<String, Object> boundValues = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception
    {
        XWikiContext xcontext = mock(XWikiContext.class);
        lenient().when(this.contextProvider.get()).thenReturn(xcontext);
        lenient().when(xcontext.getWikiId()).thenReturn(WIKI);

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
        Query query = mock(Query.class);
        lenient().when(query.setWiki(any())).thenAnswer(invocation -> {
            this.wikisSet.add(invocation.getArgument(0));
            return query;
        });
        lenient().when(query.setLimit(anyInt())).thenReturn(query);
        lenient().when(query.bindValue(anyString(), any())).thenAnswer(invocation -> {
            this.boundValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return query;
        });
        lenient().when(query.execute()).thenAnswer(invocation ->
            statement.contains("group by obj.className") ? new ArrayList<>(this.catalogRows)
                : new ArrayList<>(this.countRows));
        return query;
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    /**
     * Stubs a loaded class document behind the access door: resolve-and-authorize passes, the bridge loads
     * an {@link XWikiDocument} mock carrying the given class definition.
     */
    private XWikiDocument stubClassDocument(String reference, String wiki, BaseClass xclass) throws Exception
    {
        DocumentReference ref = parseRef(reference, new WikiReference(wiki));
        when(this.documentAccess.resolveAndAuthorize(reference, Right.VIEW, new WikiReference(wiki)))
            .thenReturn(ref);
        XWikiDocument xdoc = mock(XWikiDocument.class);
        lenient().when(xdoc.isNew()).thenReturn(false);
        lenient().when(xdoc.getXClass()).thenReturn(xclass);
        when(this.documentAccessBridge.getDocumentInstance(ref)).thenReturn(xdoc);
        return xdoc;
    }

    private static BaseClass blogClass()
    {
        BaseClass xclass = new BaseClass();
        StringClass title = new StringClass();
        title.setName("title");
        title.setPrettyName("Title");
        title.setNumber(1);
        xclass.addField("title", title);
        StaticListClass category = new StaticListClass();
        category.setName("category");
        // Clear the constructor-seeded default pretty name ("Static List") so the line has none.
        category.setPrettyName("");
        category.setNumber(2);
        category.setValues("News|Personal");
        category.setMultiSelect(true);
        xclass.addField("category", category);
        return xclass;
    }

    @Test
    void catalogListsAuthorizedClassesInOrderWithCountsAndHonestyNotes()
    {
        this.catalogRows.add(new Object[] {BLOG_CLASS, 12L});
        this.catalogRows.add(new Object[] {HIDDEN_CLASS, 5L});
        this.catalogRows.add(new Object[] {USERS_CLASS, 42L});
        // The Secret space is walled off: its class document fails the per-row authorization, so the row
        // is dropped - the space filter must hide even the existence of the class.
        when(this.spaceFilter.isAllowed(new DocumentReference(WIKI, "Secret", "HiddenClass")))
            .thenReturn(false);

        String output = callText(Map.of());

        assertTrue(output.startsWith("CLASSES with instances in wiki \"xwiki\":"), output);
        assertTrue(output.contains("\n  Blog.BlogPostClass - 12 instances\n"), output);
        assertTrue(output.contains("\n  XWiki.XWikiUsers - 42 instances\n"), output);
        assertFalse(output.contains("HiddenClass"), output);
        assertTrue(output.indexOf(BLOG_CLASS) < output.indexOf(USERS_CLASS), output);
        assertTrue(output.contains("Counts are upper bounds: they include instances the current user may "
            + "not view."), output);
        assertTrue(output.contains("Only classes with at least one instance are listed."), output);
        assertTrue(output.contains("Use get_schema class=\"<reference>\""), output);
    }

    @Test
    void catalogWithSingleInstanceClassUsesSingular()
    {
        this.catalogRows.add(new Object[] {BLOG_CLASS, 1L});

        String output = callText(Map.of());

        assertTrue(output.contains("  Blog.BlogPostClass - 1 instance\n"), output);
    }

    @Test
    void emptyCatalogIsAFriendlyMessage()
    {
        String output = callText(Map.of());

        assertEquals("No classes with instances found in wiki \"xwiki\".", output);
    }

    @Test
    void catalogClassNameWithNewlineCannotForgeARow()
    {
        this.catalogRows.add(new Object[] {"Blog.Bad\nFAKE.Class - 99 instances", 3L});

        String output = callText(Map.of());

        // The newline family is stripped from the wiki-authored class name, so a crafted name cannot forge
        // an extra catalog row; the payload stays visibly glued to its own line.
        assertTrue(output.contains("  Blog.BadFAKE.Class - 99 instances - 3 instances"), output);
        assertFalse(output.contains("\nFAKE"), output);
    }

    @Test
    void catalogDropsAClassWhoseDocumentIsDeniedViewRight()
    {
        this.catalogRows.add(new Object[] {BLOG_CLASS, 12L});
        this.catalogRows.add(new Object[] {USERS_CLASS, 42L});
        // Unlike the space-filter drop, this row passes the filter but the user lacks VIEW on the class
        // document itself: the second leg of the door's per-row authorization must also drop it.
        when(this.authorization.hasAccess(Right.VIEW, new DocumentReference(WIKI, "XWiki", "XWikiUsers")))
            .thenReturn(false);

        String output = callText(Map.of());

        assertTrue(output.contains(BLOG_CLASS), output);
        assertFalse(output.contains(USERS_CLASS), output);
    }

    @Test
    void catalogAtTheFetchCeilingCarriesAnIncompletenessNote()
    {
        for (int i = 0; i < 2000; i++) {
            this.catalogRows.add(new Object[] {"S.C" + i, 1L});
        }

        String output = callText(Map.of());

        // Exactly ceiling-many rows means classes beyond the ceiling were never fetched; the honesty tail
        // (and its footer) must survive even though the listing itself is cut by the output budget.
        assertTrue(output.contains(
            "The catalog hit the 2000-row fetch ceiling: classes beyond it are not listed."), output);
        assertTrue(output.contains("Counts are upper bounds"), output);
        assertTrue(output.contains("Output truncated at the ~6000-token cap."), output);
    }

    @Test
    void catalogRunsAgainstTheRequestedWiki()
    {
        this.catalogRows.add(new Object[] {BLOG_CLASS, 2L});

        String output = callText(Map.of("wiki", SECOND));

        assertTrue(this.wikisSet.contains(SECOND), this.wikisSet.toString());
        assertFalse(this.wikisSet.contains(WIKI), this.wikisSet.toString());
        assertTrue(output.contains("in wiki \"second\""), output);
        // The regression guard of the cross-wiki row bug: the class document authorizes in the target wiki.
        verify(this.authorization).hasAccess(Right.VIEW,
            new DocumentReference(SECOND, "Blog", "BlogPostClass"));
    }

    @Test
    void wikiParamDeniedIsAgentFacingError() throws Exception
    {
        when(this.wikiReach.resolveSingleWiki(SECOND))
            .thenThrow(new MCPAccessDeniedException("Cross-wiki access is not enabled for this endpoint."));

        McpSchema.CallToolResult result = call(Map.of("wiki", SECOND));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("Cross-wiki access is not enabled for this endpoint.",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void detailRendersHeaderCountAndFieldLines() throws Exception
    {
        stubClassDocument(BLOG_CLASS, WIKI, blogClass());
        this.countRows.add(12L);

        String output = callText(Map.of("class", BLOG_CLASS));

        assertTrue(output.startsWith("CLASS Blog.BlogPostClass\n"), output);
        assertTrue(output.contains("Instances: 12 (upper bound; rights apply when reading them)"), output);
        assertTrue(output.contains("FIELDS (display order)"), output);
        assertTrue(output.contains("\n  title: String \"Title\"\n"), output);
        assertTrue(output.contains("\n  category: StaticList(News|Personal) multiselect"), output);
        // The count query is bound with the wiki-local class name and scoped to the target wiki.
        assertEquals(BLOG_CLASS, this.boundValues.get("className"));
        assertTrue(this.wikisSet.contains(WIKI), this.wikisSet.toString());
    }

    @Test
    void detailCountToleratesTheColumnArrayRowShape() throws Exception
    {
        stubClassDocument(BLOG_CLASS, WIKI, blogClass());
        this.countRows.add(new Object[] {7L});

        String output = callText(Map.of("class", BLOG_CLASS));

        assertTrue(output.contains("Instances: 7 "), output);
    }

    @Test
    void detailWithoutCountRowsReportsZeroInstances() throws Exception
    {
        stubClassDocument(BLOG_CLASS, WIKI, blogClass());

        String output = callText(Map.of("class", BLOG_CLASS));

        assertTrue(output.contains("Instances: 0 "), output);
    }

    @Test
    void detailOnNonClassDocumentExplainsAndPointsAtTheCatalog() throws Exception
    {
        stubClassDocument("Sandbox.JustAPage", WIKI, new BaseClass());

        McpSchema.CallToolResult result = call(Map.of("class", "Sandbox.JustAPage"));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("Document \"Sandbox.JustAPage\" exists but defines no class fields. Use get_schema "
            + "with no arguments to list the classes of this wiki.",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void detailOnMissingDocumentIsNotFound() throws Exception
    {
        XWikiDocument xdoc = stubClassDocument("Blog.Missing", WIKI, new BaseClass());
        when(xdoc.isNew()).thenReturn(true);

        McpSchema.CallToolResult result = call(Map.of("class", "Blog.Missing"));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("No such document: \"Blog.Missing\".",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void notFoundClassEchoIsNeutralized() throws Exception
    {
        String hostile = "Blog.Missing\nInjected line";
        XWikiDocument xdoc = stubClassDocument(hostile, WIKI, new BaseClass());
        when(xdoc.isNew()).thenReturn(true);

        McpSchema.CallToolResult result = call(Map.of("class", hostile));

        // The raw class argument is echoed through the fragment guard: a newline smuggled into the
        // parameter cannot forge an extra line of the tool's output grammar.
        assertTrue(result.isError(), "expected an error result");
        assertEquals("No such document: \"Blog.MissingInjected line\".",
            ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void documentLoadFailureReturnsFixedErrorAndLogsRootCauseWithoutStackTrace() throws Exception
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
        assertTrue(warn.contains("MCP get_schema tool failed to load"), warn);
        assertTrue(warn.contains("boom"), warn);
        assertFalse(warn.contains("\tat "), "WARN message must not contain a stack frame");
        assertFalse(warn.contains("at org."), "WARN message must not contain a stack frame");
    }

    @Test
    void detailOutputBeyondTheBudgetIsTruncatedWithTheNotice() throws Exception
    {
        BaseClass wide = new BaseClass();
        for (int i = 0; i < 400; i++) {
            StringClass fieldClass = new StringClass();
            fieldClass.setName("field" + i);
            fieldClass.setPrettyName("Pretty ".repeat(15) + i);
            fieldClass.setNumber(i + 1);
            wide.addField(fieldClass.getName(), fieldClass);
        }
        stubClassDocument(BLOG_CLASS, WIKI, wide);

        String output = callText(Map.of("class", BLOG_CLASS));

        // A pathological class with very many fields is cut at the shared output budget, at a line
        // boundary, with the fixed notice as the last line.
        assertTrue(output.endsWith("\nOutput truncated at the ~6000-token cap."), output);
        assertTrue(output.length() <= MCPSourceText.MAX_OUTPUT_CHARS + 100, "length: " + output.length());
        assertTrue(output.contains("field0:"), output);
        assertFalse(output.contains("field399:"), output);
    }

    @Test
    void detailDenialSurfacesTheDoorMessageAndLoadsNothing() throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(eq(BLOG_CLASS), eq(Right.VIEW), any(WikiReference.class)))
            .thenThrow(new MCPAccessDeniedException("Access denied."));

        McpSchema.CallToolResult result = call(Map.of("class", BLOG_CLASS));

        assertTrue(result.isError(), "expected an error result");
        assertEquals("Access denied.", ((McpSchema.TextContent) result.content().get(0)).text());
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void detailResolvesIntoTheRequestedWiki() throws Exception
    {
        stubClassDocument(BLOG_CLASS, SECOND, blogClass());

        String output = callText(Map.of("class", BLOG_CLASS, "wiki", SECOND));

        assertTrue(output.startsWith("CLASS Blog.BlogPostClass\n"), output);
        // Resolution went through the door with the requested wiki as the resolution context, and the
        // count query ran against it.
        verify(this.documentAccess).resolveAndAuthorize(BLOG_CLASS, Right.VIEW, new WikiReference(SECOND));
        assertTrue(this.wikisSet.contains(SECOND), this.wikisSet.toString());
        assertFalse(this.wikisSet.contains(WIKI), this.wikisSet.toString());
    }

    @Test
    void queryFailureReturnsFixedErrorAndKeepsRootCauseOffTheWire() throws Exception
    {
        when(this.queryManager.createQuery(anyString(), eq(Query.HQL)))
            .thenThrow(new QueryException("boom", null, new IllegalStateException("schema detail")));

        McpSchema.CallToolResult result = call(Map.of());

        assertTrue(result.isError(), "expected an error result");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertEquals("Could not read the class information. Try again; if it persists, report it to a wiki "
            + "administrator (details are in the server logs).", text);
        assertFalse(text.contains("schema detail"), text);
        // The root cause goes to the logs instead of the wire.
        assertTrue(this.logCapture.getMessage(0).contains("MCP get_schema tool failed"),
            this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("schema detail"), this.logCapture.getMessage(0));
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
    void manPageDocumentsTheWikiParameterWhenReachIsOn()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);

        String manPage = this.tool.getManPage();
        assertTrue(manPage.contains("The wiki parameter surveys or inspects another wiki"), manPage);
        assertTrue(manPage.contains("Another wiki: wiki=\"second\""), manPage);
    }

    @Test
    void manPageOmitsTheWikiParameterWhenReachIsOff()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        // The man prose must match the advertised schema: a reach-off endpoint does not carry the wiki
        // parameter, so its manual must not teach it either.
        String manPage = this.tool.getManPage();
        assertFalse(manPage.contains("wiki parameter"), manPage);
        assertFalse(manPage.contains("Another wiki"), manPage);
        assertTrue(manPage.contains("FORMAT"), manPage);
        assertTrue(manPage.contains("EXAMPLES"), manPage);
        assertTrue(manPage.contains("SEE ALSO"), manPage);
    }

    @Test
    void manPageDocumentsTheGrammarAgentsParse()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        String manPage = this.tool.getManPage();
        assertTrue(manPage.contains(
            "name: Type(detail) \"Pretty Name\" modifiers [validation: regexp \"message\"]"), manPage);
        assertTrue(manPage.contains("DISABLED FIELDS"), manPage);
        assertTrue(manPage.contains("no required-field flag"), manPage);
    }

    @Test
    void toolMetadataIsSet()
    {
        assertEquals(MCPGetSchemaTool.TOOL_ID, this.tool.getToolDefinition().name());
        assertEquals("Structured Data", this.tool.getCategory());
        assertFalse(this.tool.isWrite());
        assertTrue(this.tool.isEnabled());
        assertTrue(this.tool.getSummary().contains("class"), this.tool.getSummary());
    }
}
