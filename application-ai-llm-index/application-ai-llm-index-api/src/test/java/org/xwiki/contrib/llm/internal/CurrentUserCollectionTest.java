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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.CurrentUserReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link CurrentUserCollection}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class CurrentUserCollectionTest
{
    private static final String WIKI_NAME = "wiki";

    private static final DocumentReference COLLECTION_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of("AI", "Collections", "testCollection"), "WebHome");

    private static final DocumentReference USER_REFERENCE = new DocumentReference(WIKI_NAME, "XWiki", "User");

    private static final DocumentReference SCRIPT_AUTHOR_REFERENCE =
        new DocumentReference(WIKI_NAME, "XWiki", "ScriptAuthor");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private CurrentUserCollection collection;

    @MockComponent
    private WriteAttribution writeAttribution;

    private XWikiContext context;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context = this.oldcore.getXWikiContext();
        this.context.setUserReference(USER_REFERENCE);
        doNothing().when(this.oldcore.getSpyXWiki()).checkSavingDocument(any(), any(), any());

        XWikiDocument collectionDocument = this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, this.context);
        this.collection.initialize(collectionDocument);
    }

    private void assertSavedAs(DocumentReference effectiveAuthor) throws Exception
    {
        ArgumentCaptor<XWikiDocument> savedDocument = ArgumentCaptor.forClass(XWikiDocument.class);
        verify(this.writeAttribution).prepareSave(savedDocument.capture(), eq(USER_REFERENCE), eq(effectiveAuthor));
        assertEquals(COLLECTION_REFERENCE, savedDocument.getValue().getDocumentReference());
        verify(this.oldcore.getSpyXWiki()).checkSavingDocument(effectiveAuthor, savedDocument.getValue(), this.context);
        verify(this.oldcore.getSpyXWiki()).saveDocument(savedDocument.getValue(), this.context);
    }

    @Test
    void saveWithoutAScriptAuthorAttributesToTheCurrentUser() throws Exception
    {
        when(this.writeAttribution.checkAccess(Right.EDIT, COLLECTION_REFERENCE, CurrentUserReference.INSTANCE,
            USER_REFERENCE)).thenReturn(USER_REFERENCE);

        this.collection.save();

        assertSavedAs(USER_REFERENCE);
    }

    @Test
    void saveFromAScriptWithoutProgrammingRightChecksAndAttributesTheAuthor() throws Exception
    {
        when(this.writeAttribution.checkAccess(Right.EDIT, COLLECTION_REFERENCE, CurrentUserReference.INSTANCE,
            USER_REFERENCE)).thenReturn(SCRIPT_AUTHOR_REFERENCE);

        this.collection.save();

        assertSavedAs(SCRIPT_AUTHOR_REFERENCE);
    }

    @Test
    void saveChecksEditForTheCurrentUser() throws Exception
    {
        AccessDeniedException exception = new AccessDeniedException(Right.EDIT, USER_REFERENCE, COLLECTION_REFERENCE);
        doThrow(exception).when(this.writeAttribution)
            .checkAccess(Right.EDIT, COLLECTION_REFERENCE, CurrentUserReference.INSTANCE, USER_REFERENCE);

        IndexException thrown = assertThrows(IndexException.class, () -> this.collection.save());

        assertSame(exception, thrown.getCause());
        verify(this.writeAttribution, never()).prepareSave(any(), any(), any());
        verify(this.oldcore.getSpyXWiki(), never()).saveDocument(any(XWikiDocument.class), any(XWikiContext.class));
    }
}
