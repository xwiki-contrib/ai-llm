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
package org.xwiki.contrib.llm.mcp.internal;

import java.util.List;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultMCPSpaceFilter}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultMCPSpaceFilterTest
{
    private static final String WIKI = "xwiki";

    private static final String SECOND = "second";

    private static final String SPACE_AB = "A.B";

    private static final String SPACE_CD = "C.D";

    private static final String DOC_FAQ = "A.B.FAQ";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private DefaultMCPSpaceFilter filter;

    @MockComponent
    private MCPServerConfiguration configuration;

    @MockComponent
    @Named("current")
    private SpaceReferenceResolver<String> spaceResolver;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> documentResolver;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @MockComponent
    private SolrUtils solrUtils;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @Mock
    private XWikiContext context;

    private final WikiReference wikiReference = new WikiReference(WIKI);

    @BeforeEach
    void setUp()
    {
        // The source (context) wiki is the endpoint's own wiki; its configuration governs the filter.
        lenient().when(this.contextProvider.get()).thenReturn(this.context);
        lenient().when(this.context.getWikiId()).thenReturn(WIKI);
        // Make escaping passthrough so the expected filter-query strings are predictable.
        lenient().when(this.solrUtils.toCompleteFilterQueryString(anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        // The configured entries resolve with the current resolver WITHOUT a wiki hint, so an unqualified entry
        // resolves against the source wiki.
        lenient().when(this.spaceResolver.resolve(SPACE_AB)).thenReturn(spaceAB());
        lenient().when(this.spaceResolver.resolve(SPACE_CD))
            .thenReturn(new SpaceReference("D", new SpaceReference("C", this.wikiReference)));
        lenient().when(this.localSerializer.serialize(spaceAB())).thenReturn(SPACE_AB);
        lenient().when(this.localSerializer.serialize(
            new SpaceReference("D", new SpaceReference("C", this.wikiReference)))).thenReturn(SPACE_CD);
    }

    private DocumentReference docInSpace(SpaceReference space, String name)
    {
        return new DocumentReference(name, space);
    }

    private SpaceReference spaceAB()
    {
        return new SpaceReference("B", new SpaceReference("A", this.wikiReference));
    }

    private void mode(String value)
    {
        when(this.configuration.getSpaceFilterMode(WIKI)).thenReturn(value);
    }

    private void spaces(List<String> value)
    {
        when(this.configuration.getSpaceFilterSpaces(WIKI)).thenReturn(value);
    }

    private void documents(List<String> value)
    {
        when(this.configuration.getSpaceFilterDocuments(WIKI)).thenReturn(value);
    }

    @Test
    void noneModeAllowsEverythingAndAddsNoFilterQueries()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_NONE);

        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));
        assertTrue(this.filter.filterQueries().isEmpty());
    }

    @Test
    void whitelistAllowsDescendantSpaceAndRejectsOther()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB));
        documents(List.of());

        // A document in A.B.C is nested under the configured space A.B.
        SpaceReference abc = new SpaceReference("C", spaceAB());
        assertTrue(this.filter.isAllowed(docInSpace(abc, "Page")));

        SpaceReference xy = new SpaceReference("Y", new SpaceReference("X", this.wikiReference));
        assertFalse(this.filter.isAllowed(docInSpace(xy, "Page")));
    }

    @Test
    void whitelistFilterQueryIsSingleWikiScopedSpaceClause()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB));
        documents(List.of());

        assertEquals(List.of("(wiki:xwiki AND space_prefix:A.B)"), this.filter.filterQueries());
    }

    @Test
    void blacklistInvertsMembershipAndNegatesFilterQuery()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_BLACKLIST);
        spaces(List.of(SPACE_AB));
        documents(List.of());

        assertFalse(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));
        SpaceReference xy = new SpaceReference("Y", new SpaceReference("X", this.wikiReference));
        assertTrue(this.filter.isAllowed(docInSpace(xy, "Page")));

        assertEquals(List.of("-((wiki:xwiki AND space_prefix:A.B))"), this.filter.filterQueries());
    }

    @Test
    void documentEntryMatchesOnlyTheExactReference()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of());
        documents(List.of(DOC_FAQ));

        DocumentReference faq = docInSpace(spaceAB(), "FAQ");
        when(this.documentResolver.resolve(DOC_FAQ)).thenReturn(faq);

        assertTrue(this.filter.isAllowed(faq));
        assertFalse(this.filter.isAllowed(docInSpace(spaceAB(), "Other")));
    }

    @Test
    void bothListsEmptyMeansNoRestrictionEvenInWhitelist()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of());
        documents(List.of());

        SpaceReference xy = new SpaceReference("Y", new SpaceReference("X", this.wikiReference));
        assertTrue(this.filter.isAllowed(docInSpace(xy, "Page")));
        assertTrue(this.filter.filterQueries().isEmpty());
    }

    @Test
    void combinedSpacesAndDocumentsProduceOneClausePerEntry()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB));
        documents(List.of(DOC_FAQ));

        DocumentReference faq = docInSpace(spaceAB(), "FAQ");
        when(this.documentResolver.resolve(DOC_FAQ)).thenReturn(faq);
        when(this.localSerializer.serialize(faq)).thenReturn(DOC_FAQ);

        assertEquals(List.of("(wiki:xwiki AND space_prefix:A.B) OR (wiki:xwiki AND fullname:A.B.FAQ)"),
            this.filter.filterQueries());
    }

    @Test
    void multipleSpaceEntriesProduceOneClauseEach()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB, SPACE_CD));
        documents(List.of());

        assertEquals(List.of("(wiki:xwiki AND space_prefix:A.B) OR (wiki:xwiki AND space_prefix:C.D)"),
            this.filter.filterQueries());
    }

    @Test
    void wikiQualifiedSpaceEntryTargetsOnlyItsOwnWiki()
    {
        // A wiki-qualified entry "second:Sandbox" resolves (via the current resolver) to a space in wiki "second".
        WikiReference secondWiki = new WikiReference(SECOND);
        SpaceReference sandboxInSecond = new SpaceReference("Sandbox", secondWiki);
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of("second:Sandbox"));
        documents(List.of());
        when(this.spaceResolver.resolve("second:Sandbox")).thenReturn(sandboxInSecond);
        when(this.localSerializer.serialize(sandboxInSecond)).thenReturn("Sandbox");

        // A target in second's Sandbox is allowed; the same-named space in the source wiki is not.
        assertTrue(this.filter.isAllowed(new DocumentReference("Page", sandboxInSecond)));
        assertFalse(this.filter.isAllowed(
            new DocumentReference("Page", new SpaceReference("Sandbox", this.wikiReference))));

        assertEquals(List.of("(wiki:second AND space_prefix:Sandbox)"), this.filter.filterQueries());
    }

    @Test
    void allEntriesMalformedDeniesEverythingInWhitelist()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB));
        documents(List.of());
        when(this.spaceResolver.resolve(SPACE_AB)).thenThrow(new IllegalArgumentException("bad ref"));

        assertFalse(this.filter.isAllowed(docInSpace(spaceAB(), "Page")));
        assertEquals(List.of("-*:*"), this.filter.filterQueries());
    }

    @Test
    void repeatedChecksReadTheConfigurationOnlyOnce()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB));
        documents(List.of());

        // Several document checks plus a search filter build for the same source wiki: the configuration
        // document is read and its entries resolved exactly once, then the cached parsed state is reused.
        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));
        SpaceReference xy = new SpaceReference("Y", new SpaceReference("X", this.wikiReference));
        assertFalse(this.filter.isAllowed(docInSpace(xy, "Page")));
        assertEquals(List.of("(wiki:xwiki AND space_prefix:A.B)"), this.filter.filterQueries());

        verify(this.configuration, times(1)).getSpaceFilterMode(WIKI);
        verify(this.configuration, times(1)).getSpaceFilterSpaces(WIKI);
        verify(this.configuration, times(1)).getSpaceFilterDocuments(WIKI);
        verify(this.spaceResolver, times(1)).resolve(SPACE_AB);
    }

    @Test
    void invalidateForcesTheNextCheckToReRead()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB));
        documents(List.of());

        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));
        this.filter.invalidate(WIKI);
        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));

        verify(this.configuration, times(2)).getSpaceFilterMode(WIKI);
    }

    @Test
    void invalidatingAnotherWikiKeepsTheCachedState()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB));
        documents(List.of());

        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));
        this.filter.invalidate(SECOND);
        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));

        verify(this.configuration, times(1)).getSpaceFilterMode(WIKI);
    }

    @Test
    void invalidateAllForcesTheNextCheckToReRead()
    {
        mode(MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST);
        spaces(List.of(SPACE_AB));
        documents(List.of());

        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));
        this.filter.invalidateAll();
        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));

        verify(this.configuration, times(2)).getSpaceFilterMode(WIKI);
    }

    @Test
    void failedConfigurationReadIsNotCached()
    {
        when(this.configuration.getSpaceFilterMode(WIKI))
            .thenThrow(new IllegalStateException("Store down"))
            .thenReturn(MCPServerConfiguration.SPACE_FILTER_MODE_NONE);

        // The first check fails closed; the failure leaves no cache entry, so the next check re-reads the
        // configuration and sees the now-healthy store.
        assertFalse(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));
        assertTrue(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));

        verify(this.configuration, times(2)).getSpaceFilterMode(WIKI);
        assertEquals("Could not read the MCP space filter for wiki [xwiki]; denying access: "
            + "[IllegalStateException: Store down]", this.logCapture.getMessage(0));
    }

    @Test
    void isAllowedFailsClosedWhenConfigReadThrows()
    {
        when(this.configuration.getSpaceFilterMode(WIKI)).thenThrow(new IllegalStateException("Store down"));

        assertFalse(this.filter.isAllowed(docInSpace(spaceAB(), "WebHome")));
        assertEquals("Could not read the MCP space filter for wiki [xwiki]; denying access: "
            + "[IllegalStateException: Store down]", this.logCapture.getMessage(0));
    }

    @Test
    void filterQueriesFailClosedWithMatchNothingWhenConfigReadThrows()
    {
        when(this.configuration.getSpaceFilterMode(WIKI)).thenThrow(new IllegalStateException("Store down"));

        assertEquals(List.of("-*:*"), this.filter.filterQueries());
        assertEquals("Could not build the MCP space filter queries for wiki [xwiki]; returning no results: "
            + "[IllegalStateException: Store down]", this.logCapture.getMessage(0));
    }
}
