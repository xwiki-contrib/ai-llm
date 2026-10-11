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
package org.xwiki.contrib.llm.internal.authorization;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStore;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStoreHelper;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.query.QueryFilter;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link XWikiAuthorizationManagerBuilder}. The document ids are parsed by the real
 * {@link XWikiDocumentStoreHelper}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
@ComponentList(XWikiDocumentStoreHelper.class)
class XWikiAuthorizationManagerBuilderTest
{
    private static final DocumentReference USER_REFERENCE = new DocumentReference("xwiki", "XWiki", "Alice");

    private static final DocumentReference PAGE_REFERENCE = new DocumentReference("xwiki", "Space", "Page");

    @InjectMockComponents
    private XWikiAuthorizationManagerBuilder builder;

    @MockComponent
    private org.xwiki.security.authorization.AuthorizationManager authorizationManager;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    @Named("language")
    private QueryFilter languageFilter;

    @MockComponent
    @Named("count")
    private QueryFilter countFilter;

    @MockComponent
    private SpaceReferenceResolver<String> spaceReferenceResolver;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @Mock
    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.context.getUserReference()).thenReturn(USER_REFERENCE);
    }

    @ParameterizedTest
    @ValueSource(strings = { "xwiki:Space.Page;", "xwiki:Space.Page;fr", "xwiki:Space.Page;pt_BR" })
    void canViewChecksViewRightOnThePageWithoutLocale(String documentId) throws IndexException
    {
        when(this.authorizationManager.hasAccess(Right.VIEW, USER_REFERENCE, PAGE_REFERENCE)).thenReturn(true);

        assertEquals(Map.of(documentId, true), buildManager().canView(Set.of(documentId)));

        // The reference passed to the rights check must be the page itself: no locale, and the page name must not
        // contain the locale separator.
        verify(this.authorizationManager).hasAccess(Right.VIEW, USER_REFERENCE, PAGE_REFERENCE);
        verifyNoMoreInteractions(this.authorizationManager);
    }

    @Test
    void canViewAcceptsTheIdSerializedFromADocumentReferenceWithLocale() throws Exception
    {
        EntityReferenceSerializer<String> serializer =
            this.componentManager.getInstance(EntityReferenceSerializer.TYPE_STRING, "withparameters");
        String documentId = serializer.serialize(new DocumentReference(PAGE_REFERENCE, Locale.GERMAN));
        assertEquals("xwiki:Space.Page;de", documentId);
        String defaultLocaleDocumentId = serializer.serialize(new DocumentReference(PAGE_REFERENCE, Locale.ROOT));
        assertEquals("xwiki:Space.Page;", defaultLocaleDocumentId);
        when(this.authorizationManager.hasAccess(Right.VIEW, USER_REFERENCE, PAGE_REFERENCE)).thenReturn(true);

        assertEquals(Map.of(documentId, true, defaultLocaleDocumentId, true),
            buildManager().canView(Set.of(documentId, defaultLocaleDocumentId)));

        verify(this.authorizationManager, times(2)).hasAccess(Right.VIEW, USER_REFERENCE, PAGE_REFERENCE);
        verifyNoMoreInteractions(this.authorizationManager);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Page",
        "Space.Page;",
        "",
        "null",
        "xwiki:Space.Page;fr;de",
        "xwiki:Space.Page;foo=bar",
        "xwiki:Space.Page;fr;foo=bar",
        "xwiki:Space.Page;locale=fr",
        "xwiki:Space;foo=bar.Page;fr",
        "xwiki:Space.Page"
    })
    void canViewIsFalseForAnIdThatIsNotTheExactIdOfAPage(String documentId) throws IndexException
    {
        assertEquals(Map.of(documentId, false), buildManager().canView(Set.of(documentId)));

        // The right must not be checked on the page that the resolver makes up from an incomplete or altered id.
        verifyNoInteractions(this.authorizationManager);
    }

    @Test
    void canViewResolvesNestedAndEscapedReferences() throws IndexException
    {
        String documentId = "sub:A.B.C\\.D;";
        DocumentReference expectedReference = new DocumentReference("sub", List.of("A", "B"), "C.D");
        when(this.authorizationManager.hasAccess(Right.VIEW, USER_REFERENCE, expectedReference)).thenReturn(true);

        assertEquals(Map.of(documentId, true), buildManager().canView(Set.of(documentId)));

        verify(this.authorizationManager).hasAccess(Right.VIEW, USER_REFERENCE, expectedReference);
        verifyNoMoreInteractions(this.authorizationManager);
    }

    @Test
    void canViewResolvesAPageNameEndingWithABackslash() throws IndexException
    {
        String documentId = "xwiki:Space.Trail\\\\;fr";
        DocumentReference expectedReference = new DocumentReference("xwiki", "Space", "Trail\\");
        when(this.authorizationManager.hasAccess(Right.VIEW, USER_REFERENCE, expectedReference)).thenReturn(true);

        assertEquals(Map.of(documentId, true), buildManager().canView(Set.of(documentId)));

        verify(this.authorizationManager).hasAccess(Right.VIEW, USER_REFERENCE, expectedReference);
        verifyNoMoreInteractions(this.authorizationManager);
    }

    @Test
    void canViewIsFalseWhenViewRightIsDenied() throws IndexException
    {
        DocumentReference otherReference = new DocumentReference("xwiki", "Space", "Other");
        when(this.authorizationManager.hasAccess(Right.VIEW, USER_REFERENCE, PAGE_REFERENCE)).thenReturn(false);
        when(this.authorizationManager.hasAccess(Right.VIEW, USER_REFERENCE, otherReference)).thenReturn(true);

        assertEquals(Map.of("xwiki:Space.Page;", false, "xwiki:Space.Other;", true),
            buildManager().canView(Set.of("xwiki:Space.Page;", "xwiki:Space.Other;")));
    }

    @Test
    void canViewPassesGuestAsNullUser() throws IndexException
    {
        when(this.context.getUserReference()).thenReturn(null);
        when(this.authorizationManager.hasAccess(Right.VIEW, null, PAGE_REFERENCE)).thenReturn(false);

        assertEquals(Map.of("xwiki:Space.Page;", false), buildManager().canView(Set.of("xwiki:Space.Page;")));

        verify(this.authorizationManager).hasAccess(Right.VIEW, null, PAGE_REFERENCE);
        verifyNoMoreInteractions(this.authorizationManager);
    }

    @Test
    void canViewReadsTheUserAtCallTime() throws IndexException
    {
        AuthorizationManager manager = buildManager();
        DocumentReference laterUser = new DocumentReference("xwiki", "XWiki", "Bob");
        when(this.context.getUserReference()).thenReturn(laterUser);
        when(this.authorizationManager.hasAccess(Right.VIEW, laterUser, PAGE_REFERENCE)).thenReturn(true);

        assertEquals(Map.of("xwiki:Space.Page;", true), manager.canView(Set.of("xwiki:Space.Page;")));
    }

    @Test
    void canViewIsFalseForAnIdThatCannotBeResolved() throws IndexException
    {
        String unresolvableId = "xwiki:Space.Page;not_a_valid_locale_at_all";
        when(this.authorizationManager.hasAccess(Right.VIEW, USER_REFERENCE, PAGE_REFERENCE)).thenReturn(true);

        // One unresolvable id is denied without affecting the other ids of the same call.
        assertEquals(Map.of(unresolvableId, false, "xwiki:Space.Page;", true),
            buildManager().canView(Set.of(unresolvableId, "xwiki:Space.Page;")));

        verify(this.authorizationManager).hasAccess(Right.VIEW, USER_REFERENCE, PAGE_REFERENCE);
        verifyNoMoreInteractions(this.authorizationManager);
    }

    @Test
    void buildFailsWhenTheDocumentStoreIsNotXWiki()
    {
        BaseObject configurationObject = mock();
        when(configurationObject.getStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME)).thenReturn("internal");

        IndexException exception = assertThrows(IndexException.class, () -> this.builder.build(configurationObject));
        assertEquals("The xwiki rights checking method only works with the xwiki document store.",
            exception.getMessage());
        verifyNoInteractions(this.authorizationManager);
    }

    private AuthorizationManager buildManager() throws IndexException
    {
        BaseObject configurationObject = mock();
        when(configurationObject.getStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME))
            .thenReturn(XWikiDocumentStore.NAME);
        return this.builder.build(configurationObject);
    }

    @Test
    void getConfigurationTypeIsNull()
    {
        assertNull(this.builder.getConfigurationType());
    }

    @Test
    void getConfigurationReturnsNull()
    {
        BaseObject baseObject = mock();
        assertNull(this.builder.getConfiguration(baseObject));
        verifyNoInteractions(baseObject);
    }

    @Test
    void setConfigurationDoesNothing()
    {
        BaseObject baseObject = mock();
        this.builder.setConfiguration(baseObject, new Object());
        verifyNoInteractions(baseObject);
    }
}
