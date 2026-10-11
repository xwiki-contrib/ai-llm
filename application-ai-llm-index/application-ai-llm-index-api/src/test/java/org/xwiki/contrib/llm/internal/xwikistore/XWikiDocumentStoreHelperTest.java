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
package org.xwiki.contrib.llm.internal.xwikistore;

import java.util.List;
import java.util.Locale;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryFilter;
import org.xwiki.query.QueryManager;
import org.xwiki.query.QueryParameter;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentStoreHelper}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class XWikiDocumentStoreHelperTest
{
    private static final WikiReference WIKI = new WikiReference("mywiki");

    private static final List<SpaceReference> SPACES = List.of(new SpaceReference("mywiki", "Space"));

    private static final String EXPECTED_STATEMENT =
        "WHERE (doc.hidden <> true OR doc.hidden IS NULL) AND ((doc.fullName LIKE :space0))";

    @InjectMockComponents
    private XWikiDocumentStoreHelper helper;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    @Named("language")
    private QueryFilter languageFilter;

    @MockComponent
    @Named("count")
    private QueryFilter countFilter;

    @MockComponent
    @Named("hidden/document")
    private QueryFilter hiddenDocumentFilter;

    @MockComponent
    private XWikiContext context;

    @Mock
    private Query query;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.queryManager.createQuery(anyString(), any())).thenReturn(this.query);
        QueryParameter parameter = mock();
        when(parameter.literal(any())).thenReturn(parameter);
        when(parameter.anyChars()).thenReturn(parameter);
        when(this.query.bindValue(anyString())).thenReturn(parameter);
    }

    @Test
    void getDocumentsFromWikiExcludesHiddenDocumentsInTheStatement() throws Exception
    {
        Object[] row = { "Space.Page", "fr" };
        when(this.query.execute()).thenReturn(List.of((Object) row));

        List<DocumentReference> documents = this.helper.getDocumentsFromWiki(WIKI, SPACES, 3, 7);

        assertEquals(List.of(new DocumentReference("mywiki", "Space", "Page", Locale.FRENCH)), documents);
        verify(this.queryManager).createQuery(EXPECTED_STATEMENT, Query.XWQL);
        verify(this.query).setWiki("mywiki");
        verify(this.query).setOffset(3);
        verify(this.query).setLimit(7);
        verify(this.query).addFilter(this.languageFilter);
        verify(this.query, never()).addFilter(this.hiddenDocumentFilter);
        verify(this.query, never()).addFilter(this.countFilter);
    }

    @Test
    void countDocumentsFromWikiExcludesHiddenDocumentsInTheStatement() throws Exception
    {
        // The count filter returns a Long.
        when(this.query.execute()).thenReturn(List.of(3L));

        assertEquals(3, this.helper.countDocumentsFromWiki(WIKI, SPACES));

        verify(this.queryManager).createQuery(EXPECTED_STATEMENT, Query.XWQL);
        verify(this.query).addFilter(this.countFilter);
        verify(this.query).addFilter(this.languageFilter);
        verify(this.query, never()).addFilter(this.hiddenDocumentFilter);
    }

    @Test
    void resolveDocumentIdWithRootLocale()
    {
        assertEquals(new DocumentReference("xwiki", "Space", "Page", Locale.ROOT),
            this.helper.resolveDocumentId("xwiki:Space.Page;"));
    }

    @Test
    void resolveDocumentIdWithLocale()
    {
        assertEquals(new DocumentReference("xwiki", "Space", "Page", Locale.FRENCH),
            this.helper.resolveDocumentId("xwiki:Space.Page;fr"));
        assertEquals(new DocumentReference("xwiki", List.of("A", "B"), "C.D", new Locale("pt", "BR")),
            this.helper.resolveDocumentId("xwiki:A.B.C\\.D;pt_BR"));
    }

    @Test
    void resolveDocumentIdOfAPageNameEndingWithABackslash()
    {
        // The platform resolver reads the escaped backslash as an escape of the locale separator and would return
        // a single page named "Trail\\;" without locale.
        assertEquals(new DocumentReference("xwiki", "Space", "Trail\\", Locale.ROOT),
            this.helper.resolveDocumentId("xwiki:Space.Trail\\\\;"));
    }

    @Test
    void resolveDocumentIdOfAPageNameContainingTheLocaleSeparator()
    {
        assertEquals(new DocumentReference("xwiki", "Space", "Semi;colon", Locale.FRENCH),
            this.helper.resolveDocumentId("xwiki:Space.Semi\\;colon;fr"));
    }

    @Test
    void resolveDocumentIdOfASpaceNameContainingTheLocaleSeparator()
    {
        assertEquals(new DocumentReference("xwiki", "Sp;ace", "Page", Locale.ROOT),
            this.helper.resolveDocumentId("xwiki:Sp\\;ace.Page;"));
    }

    @Test
    void resolveDocumentIdOfAPageNameWithABackslashBeforeADot()
    {
        // The id of the page "Trail\.x": the backslash and the dot of the name are both escaped.
        assertEquals(new DocumentReference("xwiki", "Space", "Trail\\.x", Locale.FRENCH),
            this.helper.resolveDocumentId("xwiki:Space.Trail\\\\\\.x;fr"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "xwiki:Space.Page;;fr",
        "Space.Page;fr",
        "Page;",
        "xwiki:Space.Page",
        "xwiki:Space.Page;fr;de",
        "xwiki:Space.Page;locale=fr",
        "xwiki:Space.Page;not a locale!",
        ""
    })
    void resolveDocumentIdThatIsNotTheExactIdOfAPage(String documentId)
    {
        assertNull(this.helper.resolveDocumentId(documentId));
    }

    @Test
    void resolveDocumentIdOfNull()
    {
        assertNull(this.helper.resolveDocumentId(null));
    }

    @Test
    void getCollectionsKeepsTheCollectionPagesOnly() throws Exception
    {
        DocumentReference documentReference = new DocumentReference("mywiki", List.of("Space", "Sub"), "Page");
        // The query returns every page carrying a collections object that lists one of the spaces of the document,
        // once per listed space: the page of a collection twice, a copy of a collection page in another space and a
        // page of the collections space that is not the page of a collection.
        when(this.query.execute()).thenReturn(List.of("AI.Collections.mine.WebHome", "Copies.mine.WebHome",
            "AI.Collections.other.Copy", "AI.Collections.mine.WebHome"));

        assertEquals(List.of("mine"), this.helper.getCollections(documentReference, WIKI));

        verify(this.query).bindValue("space0", "Space.Sub");
        verify(this.query).bindValue("space1", "Space");
        verify(this.query).setWiki("mywiki");
    }

    @Test
    void getDocumentsFromWikiWrapsQueryFailures() throws Exception
    {
        when(this.query.execute()).thenThrow(new RuntimeException("boom"));
        when(this.query.getStatement()).thenReturn(EXPECTED_STATEMENT);

        IndexException exception = assertThrows(IndexException.class,
            () -> this.helper.getDocumentsFromWiki(WIKI, SPACES, 0, -1));

        assertTrue(exception.getMessage().contains(EXPECTED_STATEMENT));
        verify(this.query, never()).setLimit(-1);
    }
}
