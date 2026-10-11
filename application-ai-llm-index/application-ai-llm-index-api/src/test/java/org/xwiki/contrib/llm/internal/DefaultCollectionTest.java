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
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit test for {@link DefaultCollection}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class DefaultCollectionTest
{
    private static final DocumentReference COLLECTION_REFERENCE =
        new DocumentReference("wiki", List.of("AI", "Collections", "testCollection"), "WebHome");

    private static final DocumentReference USER_REFERENCE = new DocumentReference("wiki", "XWiki", "User");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultCollection collection;

    @MockComponent
    private WriteAttribution writeAttribution;

    private XWikiContext context;

    private XWikiDocument collectionDocument;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context = this.oldcore.getXWikiContext();
        this.collectionDocument = this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, this.context);
        this.collection.initialize(this.collectionDocument);
    }

    @Test
    void initializeTwiceFails()
    {
        assertThrows(IllegalStateException.class, () -> this.collection.initialize(this.collectionDocument));
    }

    @Test
    void saveAttributesToTheContextUser() throws Exception
    {
        this.context.setUserReference(USER_REFERENCE);

        this.collection.save();

        ArgumentCaptor<XWikiDocument> savedDocument = ArgumentCaptor.forClass(XWikiDocument.class);
        verify(this.writeAttribution).prepareSave(savedDocument.capture(), eq(USER_REFERENCE), eq(USER_REFERENCE));
        assertEquals(COLLECTION_REFERENCE, savedDocument.getValue().getDocumentReference());
        verify(this.oldcore.getSpyXWiki()).saveDocument(savedDocument.getValue(), this.context);
    }

    @Test
    void saveWithoutAContextUserKeepsTheAuthors() throws Exception
    {
        this.context.setUserReference(null);

        this.collection.save();

        verifyNoInteractions(this.writeAttribution);
        verify(this.oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class), eq(this.context));
    }
}
