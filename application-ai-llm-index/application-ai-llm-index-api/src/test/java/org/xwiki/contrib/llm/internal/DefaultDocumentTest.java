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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DefaultDocument}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class DefaultDocumentTest
{
    private static final DocumentReference DOCUMENT_REFERENCE = new DocumentReference("wiki", "Space", "Page");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultDocument document;

    private XWikiDocument xWikiDocument;

    @BeforeEach
    void setUp()
    {
        this.xWikiDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        this.document.initialize(this.xWikiDocument);
    }

    @Test
    void initializeTwiceFails()
    {
        assertThrows(IllegalStateException.class, () -> this.document.initialize(this.xWikiDocument));
    }

    @Test
    void getXWikiDocumentNeedsProgrammingRight() throws Exception
    {
        when(this.oldcore.getMockContextualAuthorizationManager().hasAccess(Right.PROGRAM)).thenReturn(false);
        assertNull(this.document.getXWikiDocument());

        when(this.oldcore.getMockContextualAuthorizationManager().hasAccess(Right.PROGRAM)).thenReturn(true);
        XWikiDocument clone = this.document.getXWikiDocument();
        assertNotSame(this.xWikiDocument, clone);
        assertEquals(DOCUMENT_REFERENCE, clone.getDocumentReference());
    }

    @Test
    void getClonedXWikiDocumentIsAvailableToTheStore() throws Exception
    {
        XWikiDocument clone = this.document.getClonedXWikiDocument();

        assertNotSame(this.xWikiDocument, clone);
        assertEquals(DOCUMENT_REFERENCE, clone.getDocumentReference());
        assertSame(clone, this.document.getClonedXWikiDocument());
    }
}
