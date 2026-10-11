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
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link CurrentUserCollectionManager}.
 *
 * @version $Id$
 */
@OldcoreTest
@ComponentList({
    DefaultCollection.class,
    CurrentUserCollection.class
})
@ReferenceComponentList
class CurrentUserCollectionManagerTest
{
    private static final String WIKI_NAME = "testwiki";

    private static final String COLLECTION_ID = "testcollection";

    private static final DocumentReference COLLECTION_REFERENCE =
        DefaultCollection.getDocumentReference(WIKI_NAME, COLLECTION_ID);

    private static final DocumentReference USER_REFERENCE = new DocumentReference(WIKI_NAME, "XWiki", "User");

    private static final DocumentReference SCRIPT_AUTHOR_REFERENCE =
        new DocumentReference(WIKI_NAME, "XWiki", "ScriptAuthor");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private CurrentUserCollectionManager collectionManager;

    @MockComponent
    private WriteAttribution writeAttribution;

    @MockComponent
    private GroupManager groupManager;

    @MockComponent
    private SolrConnector solrConnector;

    private XWikiContext context;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context = this.oldcore.getXWikiContext();
        this.context.setWikiId(WIKI_NAME);
        this.context.setUserReference(USER_REFERENCE);
        doNothing().when(this.oldcore.getSpyXWiki()).checkDeletingDocument(any(), any(), any());
    }

    private void saveCollectionPage() throws Exception
    {
        XWikiDocument document = new XWikiDocument(COLLECTION_REFERENCE);
        document.setTitle(COLLECTION_ID);
        this.oldcore.getSpyXWiki().saveDocument(document, this.context);
        assertFalse(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, this.context).isNew());
    }

    @Test
    void deleteCollectionChecksTheScriptAuthorAndDeletesThePage() throws Exception
    {
        saveCollectionPage();
        when(this.writeAttribution.checkAccess(Right.DELETE, COLLECTION_REFERENCE, CurrentUserReference.INSTANCE,
            USER_REFERENCE)).thenReturn(SCRIPT_AUTHOR_REFERENCE);

        this.collectionManager.deleteCollection(COLLECTION_ID, false);

        // The deletion is checked for the effective author, the script's author here, like in the document store.
        ArgumentCaptor<XWikiDocument> checkedDocument = ArgumentCaptor.forClass(XWikiDocument.class);
        verify(this.oldcore.getSpyXWiki())
            .checkDeletingDocument(eq(SCRIPT_AUTHOR_REFERENCE), checkedDocument.capture(), eq(this.context));
        assertEquals(COLLECTION_REFERENCE, checkedDocument.getValue().getDocumentReference());
        assertTrue(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, this.context).isNew());
    }

    @Test
    void deleteCollectionDeniedKeepsThePage() throws Exception
    {
        saveCollectionPage();
        AccessDeniedException exception =
            new AccessDeniedException(Right.DELETE, SCRIPT_AUTHOR_REFERENCE, COLLECTION_REFERENCE);
        doThrow(exception).when(this.writeAttribution)
            .checkAccess(Right.DELETE, COLLECTION_REFERENCE, CurrentUserReference.INSTANCE, USER_REFERENCE);

        IndexException thrown =
            assertThrows(IndexException.class, () -> this.collectionManager.deleteCollection(COLLECTION_ID, false));

        assertEquals("You do not have the right to delete this collection", thrown.getMessage());
        assertSame(exception, thrown.getCause());
        verify(this.oldcore.getSpyXWiki(), never()).checkDeletingDocument(any(), any(), any());
        verify(this.oldcore.getSpyXWiki(), never())
            .deleteDocument(any(XWikiDocument.class), any(XWikiContext.class));
        assertFalse(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, this.context).isNew());
    }

    @Test
    void deleteCollectionReportsARefusedDeletionAsAFailureAndNotAsADeniedAccess() throws Exception
    {
        saveCollectionPage();
        when(this.writeAttribution.checkAccess(Right.DELETE, COLLECTION_REFERENCE, CurrentUserReference.INSTANCE,
            USER_REFERENCE)).thenReturn(USER_REFERENCE);
        XWikiException cause = new XWikiException();
        doThrow(cause).when(this.oldcore.getSpyXWiki()).checkDeletingDocument(any(), any(), any());

        IndexException thrown =
            assertThrows(IndexException.class, () -> this.collectionManager.deleteCollection(COLLECTION_ID, false));

        assertEquals("Failed to delete collection [testcollection]", thrown.getMessage());
        assertSame(cause, thrown.getCause());
        assertFalse(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, this.context).isNew());
    }

    @Test
    void deleteCollectionOfAMissingPageDoesNothing() throws Exception
    {
        when(this.writeAttribution.checkAccess(Right.DELETE, COLLECTION_REFERENCE, CurrentUserReference.INSTANCE,
            USER_REFERENCE)).thenReturn(USER_REFERENCE);

        this.collectionManager.deleteCollection(COLLECTION_ID, false);

        verify(this.oldcore.getSpyXWiki(), never()).checkDeletingDocument(any(), any(), any());
        verify(this.oldcore.getSpyXWiki(), never())
            .deleteDocument(any(XWikiDocument.class), any(XWikiContext.class));
    }
}
