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
package org.xwiki.contrib.llm.mcp.internal.access;

import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultMCPRowQuery}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultMCPRowQueryTest
{
    private static final String BASE_WITH_SPACE_ALIAS =
        "select doc.fullName, space.parent, doc.title from XWikiDocument doc, XWikiSpace space"
            + " where doc.space = space.reference and space.parent in (:parents)";

    private static final String BASE_WITHOUT_SPACE_ALIAS =
        "select doc.fullName from XWikiDocument doc where doc.space in (:parents)";

    private static final String COMPLETE_STATEMENT =
        "select obj.name, obj.className from BaseObject as obj where obj.name in (:names)";

    private static final String HIDDEN_DOC_CLAUSE = " and (doc.hidden <> true or doc.hidden is null)";

    private static final String HIDDEN_SPACE_CLAUSE = " and space.hidden <> true";

    private static final String ORDER_BY_FULLNAME = " order by doc.fullName";

    private static final String WIKI = "xwiki";

    private static final String BIND_NAME = "parents";

    private static final List<String> BIND_VALUE = List.of("Sales");

    private static final String FULL_NAME = "Sales.Contact";

    @InjectMockComponents
    private DefaultMCPRowQuery rowQuery;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> currentResolver;

    @MockComponent
    private ContextualAuthorizationManager authorization;

    @MockComponent
    private MCPSpaceFilter spaceFilter;

    @Mock
    private Query query;

    private final DocumentReference reference = new DocumentReference(WIKI, "Sales", "Contact");

    @BeforeEach
    void setUp() throws Exception
    {
        lenient().when(this.queryManager.createQuery(anyString(), eq(Query.HQL))).thenReturn(this.query);
        lenient().when(this.query.execute()).thenReturn(List.of());
        lenient().when(this.currentResolver.resolve(eq(FULL_NAME), any(WikiReference.class)))
            .thenReturn(this.reference);
    }

    @Test
    void hierarchyRowsWithHiddenExcludedAndSpaceAliasAppendsBothClausesThenOrderBy() throws Exception
    {
        this.rowQuery.hierarchyRows(BASE_WITH_SPACE_ALIAS, false, true, WIKI, BIND_NAME, BIND_VALUE, 150);

        // Both hidden clauses are appended after the base, and the ordering clause always comes last.
        verify(this.queryManager).createQuery(
            BASE_WITH_SPACE_ALIAS + HIDDEN_DOC_CLAUSE + HIDDEN_SPACE_CLAUSE + ORDER_BY_FULLNAME, Query.HQL);
        verify(this.query).setWiki(WIKI);
        verify(this.query).setLimit(150);
        verify(this.query).bindValue(BIND_NAME, BIND_VALUE);
        verify(this.query).execute();
    }

    @Test
    void hierarchyRowsWithHiddenExcludedWithoutSpaceAliasAppendsDocClauseOnly() throws Exception
    {
        this.rowQuery.hierarchyRows(BASE_WITHOUT_SPACE_ALIAS, false, false, WIKI, BIND_NAME, BIND_VALUE, 150);

        // Without a space alias only the hidden-document clause applies; the ordering clause still ends it.
        verify(this.queryManager).createQuery(BASE_WITHOUT_SPACE_ALIAS + HIDDEN_DOC_CLAUSE + ORDER_BY_FULLNAME,
            Query.HQL);
    }

    @Test
    void hierarchyRowsWithHiddenIncludedAppendsOnlyOrderBy() throws Exception
    {
        this.rowQuery.hierarchyRows(BASE_WITH_SPACE_ALIAS, true, true, WIKI, BIND_NAME, BIND_VALUE, 150);

        // With hidden pages included, no hidden clause is composed even where a space alias exists.
        verify(this.queryManager).createQuery(BASE_WITH_SPACE_ALIAS + ORDER_BY_FULLNAME, Query.HQL);
    }

    @Test
    void rowsRunsACompleteStatementVerbatimAndReturnsTheRows() throws Exception
    {
        List<Object[]> stored = List.<Object[]>of(new Object[] {FULL_NAME, "Blog.BlogPostClass"});
        when(this.query.<Object[]>execute()).thenReturn(stored);

        List<Object[]> result = this.rowQuery.rows(COMPLETE_STATEMENT, WIKI, "names", List.of(FULL_NAME), 2000);

        // A complete statement reaches the query manager untouched: no hidden clause, no ordering.
        verify(this.queryManager).createQuery(COMPLETE_STATEMENT, Query.HQL);
        verify(this.query).setWiki(WIKI);
        verify(this.query).setLimit(2000);
        verify(this.query).bindValue("names", List.of(FULL_NAME));
        assertSame(stored, result);
    }

    @Test
    void rowsWithNullBindNameBindsNothing() throws Exception
    {
        this.rowQuery.rows(COMPLETE_STATEMENT, WIKI, null, null, 100);

        verify(this.query, never()).bindValue(anyString(), any());
        verify(this.query).execute();
    }

    @Test
    void rowsWithSingleBindAndNullValueStillBindsIt() throws Exception
    {
        this.rowQuery.rows(COMPLETE_STATEMENT, WIKI, BIND_NAME, null, 100);

        // A non-null bind name with a null value is bound as-is, exactly as before the multi-bind overload
        // (which the single-bind method delegates to) was introduced.
        verify(this.query).bindValue(BIND_NAME, null);
    }

    @Test
    void rowsWithBindMapBindsEveryEntryAndReturnsTheRows() throws Exception
    {
        List<Object[]> stored = List.<Object[]>of(new Object[] {"Blog.BlogPostClass", 12L});
        when(this.query.<Object[]>execute()).thenReturn(stored);

        List<Object[]> result = this.rowQuery.rows(COMPLETE_STATEMENT, WIKI,
            Map.of(BIND_NAME, BIND_VALUE, "names", FULL_NAME), 500);

        verify(this.queryManager).createQuery(COMPLETE_STATEMENT, Query.HQL);
        verify(this.query).setWiki(WIKI);
        verify(this.query).setLimit(500);
        verify(this.query).bindValue(BIND_NAME, BIND_VALUE);
        verify(this.query).bindValue("names", FULL_NAME);
        assertSame(stored, result);
    }

    @Test
    void rowsWithEmptyBindMapBindsNothing() throws Exception
    {
        this.rowQuery.rows(COMPLETE_STATEMENT, WIKI, Map.of(), 100);

        verify(this.query, never()).bindValue(anyString(), any());
        verify(this.query).execute();
    }

    @Test
    void bindMapLimitIsClampedLikeTheOtherEntryPoints() throws Exception
    {
        this.rowQuery.rows(COMPLETE_STATEMENT, WIKI, Map.of(), 5000);
        this.rowQuery.rows(COMPLETE_STATEMENT, WIKI, Map.of(), 0);

        verify(this.query).setLimit(MCPRowQuery.MAX_FETCH_PER_QUERY);
        verify(this.query).setLimit(1);
    }

    @Test
    void limitIsClampedToTheCeiling() throws Exception
    {
        this.rowQuery.rows(COMPLETE_STATEMENT, WIKI, null, null, 5000);
        this.rowQuery.hierarchyRows(BASE_WITHOUT_SPACE_ALIAS, true, false, WIKI, BIND_NAME, BIND_VALUE, 9999);

        // A limit above the ceiling never reaches the store; both entry points clamp it.
        verify(this.query, never()).setLimit(5000);
        verify(this.query, never()).setLimit(9999);
        verify(this.query, times(2)).setLimit(MCPRowQuery.MAX_FETCH_PER_QUERY);
    }

    @Test
    void nonPositiveLimitIsClampedToOneNotUnbounded() throws Exception
    {
        this.rowQuery.rows(COMPLETE_STATEMENT, WIKI, null, null, 0);
        this.rowQuery.hierarchyRows(BASE_WITHOUT_SPACE_ALIAS, true, false, WIKI, BIND_NAME, BIND_VALUE, -5);

        // The store applies a limit only when it is strictly positive, so letting 0 or a negative value
        // through would mean an UNBOUNDED fetch - the opposite of what a degenerate limit intends.
        verify(this.query, never()).setLimit(0);
        verify(this.query, never()).setLimit(-5);
        verify(this.query, times(2)).setLimit(1);
    }

    @Test
    void authorizedDocumentReturnsTheReferenceWhenAllowed()
    {
        when(this.spaceFilter.isAllowed(this.reference)).thenReturn(true);
        when(this.authorization.hasAccess(Right.VIEW, this.reference)).thenReturn(true);

        DocumentReference result = this.rowQuery.authorizedDocument(FULL_NAME, new WikiReference(WIKI));

        assertSame(this.reference, result);
        verify(this.authorization).hasAccess(Right.VIEW, this.reference);
    }

    @Test
    void authorizedDocumentDeniedBySpaceFilterReturnsNullWithoutARightsCheck()
    {
        when(this.spaceFilter.isAllowed(this.reference)).thenReturn(false);

        DocumentReference result = this.rowQuery.authorizedDocument(FULL_NAME, new WikiReference(WIKI));

        // The space filter short-circuits: a filtered-out document never reaches the rights check.
        assertNull(result);
        verify(this.authorization, never()).hasAccess(any(), any());
    }

    @Test
    void authorizedDocumentDeniedByRightsReturnsNull()
    {
        when(this.spaceFilter.isAllowed(this.reference)).thenReturn(true);
        when(this.authorization.hasAccess(Right.VIEW, this.reference)).thenReturn(false);

        assertNull(this.rowQuery.authorizedDocument(FULL_NAME, new WikiReference(WIKI)));
    }

    @Test
    void resolveIntoResolvesWithTheTargetWiki()
    {
        WikiReference second = new WikiReference("second");
        DocumentReference inSecond = new DocumentReference("second", "Sales", "Contact");
        when(this.currentResolver.resolve(FULL_NAME, second)).thenReturn(inSecond);

        assertSame(inSecond, this.rowQuery.resolveInto(FULL_NAME, second));
    }

    @Test
    void isAuthorizedChecksTheSpaceFilterThenTheViewRight()
    {
        when(this.spaceFilter.isAllowed(this.reference)).thenReturn(true);
        when(this.authorization.hasAccess(Right.VIEW, this.reference)).thenReturn(true);
        assertTrue(this.rowQuery.isAuthorized(this.reference));

        when(this.authorization.hasAccess(Right.VIEW, this.reference)).thenReturn(false);
        assertFalse(this.rowQuery.isAuthorized(this.reference));
    }

    @Test
    void ceilingMatchesTheDocumentedValue()
    {
        assertEquals(2000, MCPRowQuery.MAX_FETCH_PER_QUERY);
    }
}
