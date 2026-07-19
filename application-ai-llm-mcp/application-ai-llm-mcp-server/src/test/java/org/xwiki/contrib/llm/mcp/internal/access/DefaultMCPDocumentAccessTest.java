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

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultMCPDocumentAccess}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultMCPDocumentAccessTest
{
    private static final String REFERENCE = "Help.GettingStarted";

    @InjectMockComponents
    private DefaultMCPDocumentAccess access;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @MockComponent
    private ContextualAuthorizationManager authorization;

    @MockComponent
    private MCPSpaceFilter spaceFilter;

    @MockComponent
    private MCPWikiReach wikiReach;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    private final DocumentReference target =
        new DocumentReference("xwiki", "Help", "GettingStarted");

    @BeforeEach
    void setUp()
    {
        when(this.referenceResolver.resolve(REFERENCE)).thenReturn(this.target);
        XWikiContext context = mock(XWikiContext.class);
        lenient().when(context.getWikiId()).thenReturn("xwiki");
        when(this.contextProvider.get()).thenReturn(context);
    }

    @Test
    void returnsReferenceWhenRightsAndSpaceFilterAllow() throws Exception
    {
        when(this.authorization.hasAccess(Right.VIEW, this.target)).thenReturn(true);
        when(this.spaceFilter.isAllowed(this.target)).thenReturn(true);

        assertSame(this.target, this.access.resolveAndAuthorize(REFERENCE, Right.VIEW));
        verify(this.authorization).hasAccess(Right.VIEW, this.target);
    }

    @Test
    void throwsWhenRightsDenied()
    {
        when(this.authorization.hasAccess(Right.EDIT, this.target)).thenReturn(false);
        // The space filter must never be consulted once rights deny access.
        lenient().when(this.spaceFilter.isAllowed(this.target)).thenReturn(true);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.access.resolveAndAuthorize(REFERENCE, Right.EDIT));
        assertEquals("You do not have permission to edit \"Help.GettingStarted\".", exception.getMessage());
        verify(this.authorization).hasAccess(Right.EDIT, this.target);
        verify(this.spaceFilter, never()).isAllowed(any());
    }

    @Test
    void throwsWithDeleteVerbWhenDeleteRightDenied()
    {
        when(this.authorization.hasAccess(Right.DELETE, this.target)).thenReturn(false);
        // The space filter must never be consulted once rights deny access.
        lenient().when(this.spaceFilter.isAllowed(this.target)).thenReturn(true);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.access.resolveAndAuthorize(REFERENCE, Right.DELETE));
        assertEquals("You do not have permission to delete \"Help.GettingStarted\".", exception.getMessage());
        verify(this.authorization).hasAccess(Right.DELETE, this.target);
        verify(this.spaceFilter, never()).isAllowed(any());
    }

    @Test
    void rightsDeniedEchoIsNeutralized()
    {
        String hostile = "Help.GettingStarted\nInjected line";
        when(this.referenceResolver.resolve(hostile)).thenReturn(this.target);
        when(this.authorization.hasAccess(Right.EDIT, this.target)).thenReturn(false);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.access.resolveAndAuthorize(hostile, Right.EDIT));
        // The raw reference is echoed with line breaks stripped: a newline smuggled into the parameter
        // cannot forge an extra line of the denial message.
        assertEquals("You do not have permission to edit \"Help.GettingStartedInjected line\".",
            exception.getMessage());
    }

    @Test
    void throwsWhenSpaceFilterDenies()
    {
        when(this.authorization.hasAccess(Right.VIEW, this.target)).thenReturn(true);
        when(this.spaceFilter.isAllowed(this.target)).thenReturn(false);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.access.resolveAndAuthorize(REFERENCE, Right.VIEW));
        assertEquals("\"Help.GettingStarted\" is outside the content this MCP endpoint is configured to expose.",
            exception.getMessage());
    }

    @Test
    void throwsWhenReferenceIsInAnUnreachableWiki()
    {
        String foreignReference = "other:Secret.Page";
        DocumentReference foreignTarget = new DocumentReference("other", "Secret", "Page");
        when(this.referenceResolver.resolve(foreignReference)).thenReturn(foreignTarget);
        when(this.wikiReach.canReachWiki("other")).thenReturn(false);
        // Neither the rights check nor the space filter must be consulted for an unreachable-wiki reference.
        lenient().when(this.authorization.hasAccess(Right.VIEW, foreignTarget)).thenReturn(true);
        lenient().when(this.spaceFilter.isAllowed(foreignTarget)).thenReturn(true);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.access.resolveAndAuthorize(foreignReference, Right.VIEW));
        assertEquals("\"other:Secret.Page\" is in another wiki (\"other\"); cross-wiki reach is not enabled for "
            + "this endpoint.", exception.getMessage());
        verify(this.authorization, never()).hasAccess(Right.VIEW, foreignTarget);
        verify(this.spaceFilter, never()).isAllowed(foreignTarget);
    }

    @Test
    void returnsReferenceWhenCrossWikiReachAllowsAndRightsAndSpaceFilterAllow() throws Exception
    {
        String foreignReference = "other:Public.Page";
        DocumentReference foreignTarget = new DocumentReference("other", "Public", "Page");
        when(this.referenceResolver.resolve(foreignReference)).thenReturn(foreignTarget);
        when(this.wikiReach.canReachWiki("other")).thenReturn(true);
        when(this.authorization.hasAccess(Right.VIEW, foreignTarget)).thenReturn(true);
        when(this.spaceFilter.isAllowed(foreignTarget)).thenReturn(true);

        assertSame(foreignTarget, this.access.resolveAndAuthorize(foreignReference, Right.VIEW));
        verify(this.authorization).hasAccess(Right.VIEW, foreignTarget);
        verify(this.spaceFilter).isAllowed(foreignTarget);
    }

    @Test
    void wikiContextOverloadResolvesUnqualifiedReferenceIntoGivenWiki() throws Exception
    {
        WikiReference secondWiki = new WikiReference("second");
        DocumentReference secondTarget = new DocumentReference("second", "Help", "GettingStarted");
        when(this.referenceResolver.resolve(REFERENCE, secondWiki)).thenReturn(secondTarget);
        when(this.wikiReach.canReachWiki("second")).thenReturn(true);
        when(this.authorization.hasAccess(Right.VIEW, secondTarget)).thenReturn(true);
        when(this.spaceFilter.isAllowed(secondTarget)).thenReturn(true);

        assertSame(secondTarget, this.access.resolveAndAuthorize(REFERENCE, Right.VIEW, secondWiki));
        // The resolver received the wiki context parameter, and the downstream pipeline still ran fully.
        verify(this.referenceResolver).resolve(REFERENCE, secondWiki);
        verify(this.authorization).hasAccess(Right.VIEW, secondTarget);
        verify(this.spaceFilter).isAllowed(secondTarget);
    }

    @Test
    void wikiContextOverloadKeepsAMatchingExplicitPrefix() throws Exception
    {
        WikiReference secondWiki = new WikiReference("second");
        String prefixedReference = "second:Help.GettingStarted";
        DocumentReference secondTarget = new DocumentReference("second", "Help", "GettingStarted");
        when(this.referenceResolver.resolve(prefixedReference, secondWiki)).thenReturn(secondTarget);
        when(this.wikiReach.canReachWiki("second")).thenReturn(true);
        when(this.authorization.hasAccess(Right.VIEW, secondTarget)).thenReturn(true);
        when(this.spaceFilter.isAllowed(secondTarget)).thenReturn(true);

        assertSame(secondTarget, this.access.resolveAndAuthorize(prefixedReference, Right.VIEW, secondWiki));
    }

    @Test
    void wikiContextOverloadRejectsAContradictingPrefix()
    {
        WikiReference secondWiki = new WikiReference("second");
        String contradictingReference = "third:Help.GettingStarted";
        DocumentReference thirdTarget = new DocumentReference("third", "Help", "GettingStarted");
        when(this.referenceResolver.resolve(contradictingReference, secondWiki)).thenReturn(thirdTarget);
        // Nothing downstream may run once the wikis contradict.
        lenient().when(this.authorization.hasAccess(Right.VIEW, thirdTarget)).thenReturn(true);
        lenient().when(this.spaceFilter.isAllowed(thirdTarget)).thenReturn(true);

        MCPAccessDeniedException exception = assertThrows(MCPAccessDeniedException.class,
            () -> this.access.resolveAndAuthorize(contradictingReference, Right.VIEW, secondWiki));
        assertEquals("Reference \"third:Help.GettingStarted\" is in wiki \"third\" but the call targets wiki "
            + "\"second\"; drop the wiki prefix or make them agree.", exception.getMessage());
        verify(this.wikiReach, never()).canReachWiki("third");
        verify(this.authorization, never()).hasAccess(Right.VIEW, thirdTarget);
        verify(this.spaceFilter, never()).isAllowed(thirdTarget);
    }
}
