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
package org.xwiki.contrib.llm.internal.rest;

import java.util.List;
import java.util.Locale;

import javax.inject.Named;
import javax.inject.Provider;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.SecurityConfiguration;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultSearchResource}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultSearchResourceTest
{
    private static final String WIKI = "testwiki";

    private static final String MAIN_WIKI = "xwiki";

    private static final String QUERY = "test query";

    private static final List<String> COLLECTIONS = List.of("col1");

    @InjectMockComponents
    private DefaultSearchResource searchResource;

    @MockComponent
    @Named("currentUser")
    private CollectionManager collectionManager;

    @MockComponent
    private SecurityConfiguration securityConfiguration;

    private XWikiContext context;

    @BeforeEach
    void setUp(MockitoComponentManager componentManager) throws Exception
    {
        // XWikiResource injects Provider<XWikiContext>; the mocked provider already returns a mock context.
        Provider<XWikiContext> contextProvider = componentManager.getInstance(XWikiContext.TYPE_PROVIDER);
        this.context = contextProvider.get();
        when(this.context.getWikiId()).thenReturn(MAIN_WIKI);
        when(this.securityConfiguration.getQueryItemsLimit()).thenReturn(1000);
    }

    @Test
    void searchWithoutLocaleForwardsNullLocaleAndRestoresWiki() throws XWikiRestException, IndexException
    {
        List<Context> results = List.of(new Context("col1", "doc1", null, null, "Some content", 0.9, null));
        when(this.collectionManager.hybridSearch(eq(QUERY), eq(COLLECTIONS), anyInt(), anyInt(), isNull()))
            .thenReturn(results);

        List<Context> actual = this.searchResource.search(WIKI, QUERY, COLLECTIONS, 5, 3, null);

        assertEquals(results, actual);
        verify(this.collectionManager).hybridSearch(eq(QUERY), eq(COLLECTIONS), anyInt(), anyInt(), isNull());
        // The search runs in the requested wiki and the previous wiki is restored afterwards.
        verify(this.context).setWikiId(WIKI);
        verify(this.context).setWikiId(MAIN_WIKI);
    }

    @Test
    void searchWithBlankLocaleForwardsNullLocale() throws XWikiRestException, IndexException
    {
        this.searchResource.search(WIKI, QUERY, COLLECTIONS, 5, 3, "   ");

        verify(this.collectionManager).hybridSearch(eq(QUERY), eq(COLLECTIONS), anyInt(), anyInt(), isNull());
    }

    @Test
    void searchWithValidLocaleForwardsParsedLocale() throws XWikiRestException, IndexException
    {
        this.searchResource.search(WIKI, QUERY, COLLECTIONS, 5, 3, "fr");

        verify(this.collectionManager).hybridSearch(eq(QUERY), eq(COLLECTIONS), anyInt(), anyInt(),
            eq(Locale.FRENCH));
    }

    @Test
    void searchWithInvalidLocaleIsRejectedWithStatic400()
    {
        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.searchResource.search(WIKI, QUERY, COLLECTIONS, 5, 3, "french"));

        assertEquals(400, exception.getResponse().getStatus());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, exception.getResponse().getMediaType());
        // The body is static: the invalid value is not reflected back.
        assertEquals("The locale parameter is not a valid locale, use forms like \"fr\" or \"pt_BR\".",
            exception.getResponse().getEntity());
        verifyNoInteractions(this.collectionManager);
    }

    @Test
    void searchWithOverlongLocaleIsRejectedWithStatic400()
    {
        // "ca_ES_VALENCIA" parses as a valid locale but serializes to more than the 5 characters the wiki
        // ever stores, so it can only produce empty results and is rejected up front.
        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.searchResource.search(WIKI, QUERY, COLLECTIONS, 5, 3, "ca_ES_VALENCIA"));

        assertEquals(400, exception.getResponse().getStatus());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, exception.getResponse().getMediaType());
        assertEquals("The locale parameter is longer than any stored locale (max 5 characters), use forms like"
            + " \"fr\" or \"pt_BR\".", exception.getResponse().getEntity());
        verifyNoInteractions(this.collectionManager);
    }
}
