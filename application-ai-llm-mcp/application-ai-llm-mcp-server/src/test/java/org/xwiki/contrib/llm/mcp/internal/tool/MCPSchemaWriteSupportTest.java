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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.QueryException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPSchemaWriteSupport}: the authorized-only orphan-count disclosure assembly the tool
 * test cannot exercise against the real store (MockitoOldcore does not model Hibernate row persistence, so
 * the cross-document count is driven here through a mocked {@link MCPRowQuery} door). The field
 * create/modify/remove paths are covered end to end by {@link MCPWriteSchemaToolTest}.
 *
 * @version $Id$
 */
class MCPSchemaWriteSupportTest
{
    private static final String CLASS_NAME = "MyApp.MyClass";

    private static final String FIELD = "title";

    private static final WikiReference WIKI = new WikiReference("xwiki");

    @Test
    void acceptedTypesListsTheGetSchemaTokens()
    {
        String tokens = MCPSchemaWriteSupport.acceptedTypes();

        assertTrue(tokens.contains("String"), tokens);
        assertTrue(tokens.contains("StaticList"), tokens);
        assertTrue(tokens.contains("ComputedField"), tokens);
    }

    @Test
    void orphanDisclosureCountsOnlyAuthorizedDocuments() throws Exception
    {
        MCPRowQuery rowQuery = mock(MCPRowQuery.class);
        Logger logger = mock(Logger.class);
        when(rowQuery.rows(anyString(), anyString(), anyMap(), anyInt()))
            .thenReturn(rows("MyApp.A", "MyApp.B", "MyApp.C"));
        // Only two of the three owning documents are viewable; the denied one leaves no trace in the count.
        when(rowQuery.authorizedDocument(eq("MyApp.A"), any(WikiReference.class)))
            .thenReturn(new DocumentReference("xwiki", "MyApp", "A"));
        when(rowQuery.authorizedDocument(eq("MyApp.B"), any(WikiReference.class)))
            .thenReturn(new DocumentReference("xwiki", "MyApp", "B"));
        when(rowQuery.authorizedDocument(eq("MyApp.C"), any(WikiReference.class))).thenReturn(null);

        String disclosure =
            MCPSchemaWriteSupport.orphanDisclosure(rowQuery, WIKI, CLASS_NAME, FIELD, logger);

        assertTrue(disclosure.startsWith("2 object(s) held values"), disclosure);
        assertTrue(disclosure.contains("hidden but not deleted"), disclosure);
    }

    @Test
    void orphanDisclosureReportsNoneWhenNoStoredValues() throws Exception
    {
        MCPRowQuery rowQuery = mock(MCPRowQuery.class);
        when(rowQuery.rows(anyString(), anyString(), anyMap(), anyInt())).thenReturn(List.of());

        String disclosure =
            MCPSchemaWriteSupport.orphanDisclosure(rowQuery, WIKI, CLASS_NAME, FIELD, mock(Logger.class));

        assertTrue(disclosure.contains("No object held a value"), disclosure);
    }

    @Test
    void orphanDisclosureMarksTheFetchCeilingWithAPlus() throws Exception
    {
        MCPRowQuery rowQuery = mock(MCPRowQuery.class);
        List<String> ceiling = new ArrayList<>();
        for (int i = 0; i < MCPRowQuery.MAX_FETCH_PER_QUERY; i++) {
            ceiling.add("MyApp.Doc" + i);
        }
        when(rowQuery.rows(anyString(), anyString(), anyMap(), anyInt())).thenReturn(castRows(ceiling));
        when(rowQuery.authorizedDocument(anyString(), any(WikiReference.class)))
            .thenReturn(new DocumentReference("xwiki", "MyApp", "Doc"));

        String disclosure =
            MCPSchemaWriteSupport.orphanDisclosure(rowQuery, WIKI, CLASS_NAME, FIELD, mock(Logger.class));

        assertTrue(disclosure.contains(MCPRowQuery.MAX_FETCH_PER_QUERY + "+ object(s)"), disclosure);
    }

    @Test
    void orphanDisclosureSwallowsAQueryFailureAndReportsUncounted() throws Exception
    {
        MCPRowQuery rowQuery = mock(MCPRowQuery.class);
        Logger logger = mock(Logger.class);
        when(rowQuery.rows(anyString(), anyString(), anyMap(), anyInt()))
            .thenThrow(new QueryException("boom", null, null));

        String disclosure =
            MCPSchemaWriteSupport.orphanDisclosure(rowQuery, WIKI, CLASS_NAME, FIELD, logger);

        // The removal has already been applied, so a count failure only downgrades the disclosure, and the
        // failure detail stays in the debug log rather than reaching the agent.
        assertTrue(disclosure.contains("hidden but not deleted"), disclosure);
        assertFalse(disclosure.contains("boom"), disclosure);
        verify(logger).debug(anyString(), eq(CLASS_NAME), eq(FIELD), any(QueryException.class));
    }

    private static List<Object[]> rows(String... fullNames)
    {
        return castRows(List.of(fullNames));
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> castRows(List<?> scalars)
    {
        // A single-column HQL select yields the scalar itself as each row element at runtime, despite the
        // door's declared Object[] element type (erasure); casting the list reference (never each element)
        // reproduces that shape without a per-element ClassCastException.
        return (List<Object[]>) scalars;
    }
}
