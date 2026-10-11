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
package org.xwiki.contrib.llm.internal;

import java.util.Optional;
import java.util.Set;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.model.EntityType;
import org.xwiki.model.document.DocumentAuthors;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.DocumentAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.security.authorization.requiredrights.DocumentRequiredRight;
import org.xwiki.security.authorization.requiredrights.DocumentRequiredRights;
import org.xwiki.security.authorization.requiredrights.DocumentRequiredRightsManager;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.GuestUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link WriteAttribution}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class WriteAttributionTest
{
    private static final String CHECK_AUTHOR_PROPERTY = "security.script.save.checkAuthor";

    private static final DocumentReference PAGE = new DocumentReference("wiki", "Space", "Page");

    private static final DocumentReference SECURE_PAGE = new DocumentReference("wiki", "Space", "Script");

    private static final DocumentReference ACTOR = new DocumentReference("wiki", "XWiki", "User");

    private static final DocumentReference SCRIPT_AUTHOR = new DocumentReference("wiki", "XWiki", "ScriptAuthor");

    @InjectMockComponents
    private WriteAttribution writeAttribution;

    @MockComponent
    private AuthorizationManager authorizationManager;

    @MockComponent
    private ContextualAuthorizationManager contextualAuthorizationManager;

    @MockComponent
    private DocumentAuthorizationManager documentAuthorizationManager;

    @MockComponent
    private DocumentRequiredRightsManager documentRequiredRightsManager;

    @MockComponent
    private ConfigurationSource configuration;

    @MockComponent
    @Named("document")
    private UserReferenceResolver<DocumentReference> userReferenceResolver;

    @MockComponent
    private XWikiContext context;

    @Mock
    private UserReference actorUser;

    @Mock
    private UserReference effectiveUser;

    @Mock
    private XWikiDocument secureDocument;

    @BeforeEach
    void setUp()
    {
        when(this.configuration.getProperty(CHECK_AUTHOR_PROPERTY, true)).thenReturn(true);
        when(this.context.getAuthorReference()).thenReturn(SCRIPT_AUTHOR);
        when(this.userReferenceResolver.resolve(ACTOR)).thenReturn(this.actorUser);
        when(this.userReferenceResolver.resolve(SCRIPT_AUTHOR)).thenReturn(this.effectiveUser);
        when(this.secureDocument.getDocumentReference()).thenReturn(SECURE_PAGE);
    }

    private DocumentReference checkAccessForCurrentUser() throws AccessDeniedException
    {
        return this.writeAttribution.checkAccess(Right.EDIT, PAGE, CurrentUserReference.INSTANCE, ACTOR);
    }

    /**
     * Make the running script enforce required rights with an author that has no programming right on it.
     */
    private void setUpEnforcingScript() throws Exception
    {
        when(this.context.getSecureDocument()).thenReturn(this.secureDocument);
        when(this.documentRequiredRightsManager.getRequiredRights(SECURE_PAGE)).thenReturn(Optional.of(
            new DocumentRequiredRights(true, Set.of(new DocumentRequiredRight(Right.SCRIPT, EntityType.DOCUMENT)))));
        when(this.documentAuthorizationManager.hasAccess(Right.PROGRAM, null, SCRIPT_AUTHOR, SECURE_PAGE))
            .thenReturn(false);
    }

    @Test
    void checkAccessDeniedToTheActorFails() throws Exception
    {
        AccessDeniedException exception = new AccessDeniedException(Right.EDIT, ACTOR, PAGE);
        doThrow(exception).when(this.authorizationManager).checkAccess(Right.EDIT, ACTOR, PAGE);

        assertSame(exception, assertThrows(AccessDeniedException.class, this::checkAccessForCurrentUser));

        verifyNoInteractions(this.configuration, this.contextualAuthorizationManager, this.context);
    }

    @Test
    void checkAccessForAnotherUserReturnsTheActor() throws Exception
    {
        assertEquals(ACTOR, this.writeAttribution.checkAccess(Right.EDIT, PAGE, mock(UserReference.class), ACTOR));

        verify(this.authorizationManager).checkAccess(Right.EDIT, ACTOR, PAGE);
        verifyNoMoreInteractions(this.authorizationManager);
        verifyNoInteractions(this.configuration, this.contextualAuthorizationManager, this.context);
    }

    @Test
    void checkAccessWithTheAuthorCheckDisabledReturnsTheActor() throws Exception
    {
        when(this.configuration.getProperty(CHECK_AUTHOR_PROPERTY, true)).thenReturn(false);

        assertEquals(ACTOR, checkAccessForCurrentUser());

        verify(this.authorizationManager).checkAccess(Right.EDIT, ACTOR, PAGE);
        verifyNoMoreInteractions(this.authorizationManager);
        verifyNoInteractions(this.contextualAuthorizationManager, this.context);
    }

    @Test
    void checkAccessWithProgrammingRightReturnsTheActor() throws Exception
    {
        when(this.contextualAuthorizationManager.hasAccess(Right.PROGRAM)).thenReturn(true);

        assertEquals(ACTOR, checkAccessForCurrentUser());

        verify(this.authorizationManager).checkAccess(Right.EDIT, ACTOR, PAGE);
        verifyNoMoreInteractions(this.authorizationManager);
        verifyNoInteractions(this.context);
    }

    @Test
    void checkAccessWithTheActorAsScriptAuthorReturnsTheActor() throws Exception
    {
        when(this.context.getAuthorReference()).thenReturn(ACTOR);

        assertEquals(ACTOR, checkAccessForCurrentUser());

        verify(this.authorizationManager).checkAccess(Right.EDIT, ACTOR, PAGE);
        verifyNoMoreInteractions(this.authorizationManager);
    }

    @Test
    void checkAccessFromAScriptChecksAndReturnsItsAuthor() throws Exception
    {
        assertEquals(SCRIPT_AUTHOR, checkAccessForCurrentUser());

        verify(this.authorizationManager).checkAccess(Right.EDIT, ACTOR, PAGE);
        verify(this.authorizationManager).checkAccess(Right.EDIT, SCRIPT_AUTHOR, PAGE);
        verify(this.contextualAuthorizationManager).hasAccess(Right.PROGRAM);
        verify(this.configuration).getProperty(CHECK_AUTHOR_PROPERTY, true);
    }

    @Test
    void checkAccessDeniedToTheScriptAuthorFails() throws Exception
    {
        AccessDeniedException exception = new AccessDeniedException(Right.EDIT, SCRIPT_AUTHOR, PAGE);
        doThrow(exception).when(this.authorizationManager).checkAccess(Right.EDIT, SCRIPT_AUTHOR, PAGE);

        assertSame(exception, assertThrows(AccessDeniedException.class, this::checkAccessForCurrentUser));
    }

    @Test
    void checkAccessWithDroppedPermissionsChecksGuest() throws Exception
    {
        when(this.context.hasDroppedPermissions()).thenReturn(true);

        assertNull(checkAccessForCurrentUser());

        verify(this.authorizationManager).checkAccess(Right.EDIT, ACTOR, PAGE);
        verify(this.authorizationManager).checkAccess(Right.EDIT, null, PAGE);
        verify(this.context, never()).getAuthorReference();
    }

    @Test
    void checkAccessWithoutAScriptAuthorChecksGuest() throws Exception
    {
        when(this.context.getAuthorReference()).thenReturn(null);

        assertNull(checkAccessForCurrentUser());

        verify(this.authorizationManager).checkAccess(Right.EDIT, ACTOR, PAGE);
        verify(this.authorizationManager).checkAccess(Right.EDIT, null, PAGE);
    }

    @Test
    void checkAccessDeniedToGuestFails() throws Exception
    {
        when(this.context.hasDroppedPermissions()).thenReturn(true);
        AccessDeniedException exception = new AccessDeniedException(Right.EDIT, null, PAGE);
        doThrow(exception).when(this.authorizationManager).checkAccess(Right.EDIT, null, PAGE);

        assertSame(exception, assertThrows(AccessDeniedException.class, this::checkAccessForCurrentUser));
    }

    @Test
    void prepareSaveOnANewPageSetsTheCreator() throws Exception
    {
        XWikiDocument document = new XWikiDocument(PAGE);

        this.writeAttribution.prepareSave(document, ACTOR, ACTOR);

        DocumentAuthors authors = document.getAuthors();
        assertSame(this.actorUser, authors.getOriginalMetadataAuthor());
        assertSame(this.actorUser, authors.getEffectiveMetadataAuthor());
        assertSame(this.actorUser, authors.getContentAuthor());
        assertSame(this.actorUser, authors.getCreator());
        assertFalse(document.isEnforceRequiredRights());
        verifyNoInteractions(this.documentRequiredRightsManager, this.documentAuthorizationManager, this.context);
    }

    @Test
    void prepareSaveOnAnExistingPageKeepsTheCreator() throws Exception
    {
        XWikiDocument document = new XWikiDocument(PAGE);
        document.setNew(false);

        this.writeAttribution.prepareSave(document, ACTOR, ACTOR);

        DocumentAuthors authors = document.getAuthors();
        assertSame(this.actorUser, authors.getOriginalMetadataAuthor());
        assertSame(this.actorUser, authors.getEffectiveMetadataAuthor());
        assertSame(this.actorUser, authors.getContentAuthor());
        assertSame(GuestUserReference.INSTANCE, authors.getCreator());
    }

    @Test
    void prepareSaveForTheScriptAuthorAttributesToIt() throws Exception
    {
        XWikiDocument document = new XWikiDocument(PAGE);

        this.writeAttribution.prepareSave(document, ACTOR, SCRIPT_AUTHOR);

        DocumentAuthors authors = document.getAuthors();
        assertSame(this.actorUser, authors.getOriginalMetadataAuthor());
        assertSame(this.effectiveUser, authors.getEffectiveMetadataAuthor());
        assertSame(this.effectiveUser, authors.getContentAuthor());
        assertSame(this.effectiveUser, authors.getCreator());
    }

    @Test
    void prepareSaveWithoutASecureDocumentLeavesThePage() throws Exception
    {
        XWikiDocument document = new XWikiDocument(PAGE);

        this.writeAttribution.prepareSave(document, ACTOR, SCRIPT_AUTHOR);

        assertFalse(document.isEnforceRequiredRights());
        verifyNoInteractions(this.documentRequiredRightsManager, this.documentAuthorizationManager);
    }

    @Test
    void prepareSaveWithAScriptNotEnforcingRequiredRightsLeavesThePage() throws Exception
    {
        when(this.context.getSecureDocument()).thenReturn(this.secureDocument);
        XWikiDocument document = new XWikiDocument(PAGE);

        this.writeAttribution.prepareSave(document, ACTOR, SCRIPT_AUTHOR);

        assertFalse(document.isEnforceRequiredRights());
        verify(this.documentRequiredRightsManager).getRequiredRights(SECURE_PAGE);
        verifyNoMoreInteractions(this.documentRequiredRightsManager);
        verifyNoInteractions(this.documentAuthorizationManager);
    }

    @Test
    void prepareSaveForAScriptAuthorWithProgrammingRightLeavesThePage() throws Exception
    {
        setUpEnforcingScript();
        when(this.documentAuthorizationManager.hasAccess(Right.PROGRAM, null, SCRIPT_AUTHOR, SECURE_PAGE))
            .thenReturn(true);
        XWikiDocument document = new XWikiDocument(PAGE);

        this.writeAttribution.prepareSave(document, ACTOR, SCRIPT_AUTHOR);

        assertFalse(document.isEnforceRequiredRights());
        verify(this.documentRequiredRightsManager, never()).getRequiredRights(PAGE);
        verify(this.documentAuthorizationManager, never()).checkAccess(any(), any(), any(), any());
    }

    @Test
    void prepareSaveForAScriptAuthorWithoutProgrammingRightCapsThePage() throws Exception
    {
        setUpEnforcingScript();
        when(this.documentRequiredRightsManager.getRequiredRights(PAGE)).thenReturn(Optional.of(
            new DocumentRequiredRights(true, Set.of(new DocumentRequiredRight(Right.SCRIPT, EntityType.DOCUMENT),
                new DocumentRequiredRight(Right.ADMIN, EntityType.WIKI)))));
        XWikiDocument document = new XWikiDocument(PAGE);

        this.writeAttribution.prepareSave(document, ACTOR, SCRIPT_AUTHOR);

        assertTrue(document.isEnforceRequiredRights());
        verify(this.documentAuthorizationManager)
            .checkAccess(Right.SCRIPT, EntityType.DOCUMENT, SCRIPT_AUTHOR, SECURE_PAGE);
        verify(this.documentAuthorizationManager).checkAccess(Right.ADMIN, EntityType.WIKI, SCRIPT_AUTHOR, SECURE_PAGE);
    }

    @Test
    void prepareSaveForAScriptAuthorDeniedARequiredRightFails() throws Exception
    {
        setUpEnforcingScript();
        when(this.documentRequiredRightsManager.getRequiredRights(PAGE)).thenReturn(Optional.of(
            new DocumentRequiredRights(true, Set.of(new DocumentRequiredRight(Right.ADMIN, EntityType.WIKI)))));
        AccessDeniedException exception = new AccessDeniedException(Right.ADMIN, SCRIPT_AUTHOR, SECURE_PAGE);
        doThrow(exception).when(this.documentAuthorizationManager)
            .checkAccess(Right.ADMIN, EntityType.WIKI, SCRIPT_AUTHOR, SECURE_PAGE);
        XWikiDocument document = new XWikiDocument(PAGE);

        assertSame(exception, assertThrows(AccessDeniedException.class,
            () -> this.writeAttribution.prepareSave(document, ACTOR, SCRIPT_AUTHOR)));

        assertTrue(document.isEnforceRequiredRights());
    }
}
