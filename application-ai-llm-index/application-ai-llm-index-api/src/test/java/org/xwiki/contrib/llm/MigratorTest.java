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
package org.xwiki.contrib.llm;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.refactoring.event.DocumentRenamedEvent;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link Migrator}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class MigratorTest
{
    private static final String WIKI = "testwiki";

    private static final String OLD_ID = "Old";

    private static final String NEW_ID = "New";

    private static final DocumentReference OLD_REFERENCE =
        new DocumentReference(WIKI, List.of("AI", "Collections", OLD_ID), "WebHome");

    private static final DocumentReference NEW_REFERENCE =
        new DocumentReference(WIKI, List.of("AI", "Collections", NEW_ID), "WebHome");

    private static final DocumentReference DOCUMENT_REFERENCE =
        new DocumentReference(WIKI, List.of("AI", "Collections", NEW_ID, "Documents"), "doc1");

    private static final String EXPECTED_QUERY = "select doc.fullName from XWikiDocument doc, BaseObject obj, "
        + "StringProperty prop where doc.fullName = obj.name and obj.className = :documentClass "
        + "and doc.space = :space and obj.id = prop.id.id and prop.id.name = 'collection' "
        + "and prop.value = :oldId";

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private Migrator migrator;

    @Mock
    private Query query;

    private XWikiContext context;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context = this.oldcore.getXWikiContext();
        this.context.setWikiId(WIKI);
        when(this.oldcore.getQueryManager().createQuery(anyString(), eq(Query.HQL))).thenReturn(this.query);
    }

    @Test
    void renamedCollectionPageUpdatesTheCollectionIdAndItsDocuments() throws Exception
    {
        XWikiDocument collectionDocument = new XWikiDocument(NEW_REFERENCE);
        collectionDocument.newXObject(Collection.XCLASS_REFERENCE, this.context).setStringValue("id", OLD_ID);
        this.oldcore.getSpyXWiki().saveDocument(collectionDocument, this.context);

        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.newXObject(Document.XCLASS_REFERENCE, this.context).setStringValue("collection", OLD_ID);
        this.oldcore.getSpyXWiki().saveDocument(document, this.context);

        when(this.query.execute()).thenReturn(List.of("AI.Collections.New.Documents.doc1"));

        this.migrator.processLocalEvent(new DocumentRenamedEvent(OLD_REFERENCE, NEW_REFERENCE), null, null);

        BaseObject collectionObject = this.oldcore.getSpyXWiki().getDocument(NEW_REFERENCE, this.context)
            .getXObject(Collection.XCLASS_REFERENCE);
        assertEquals(NEW_ID, collectionObject.getStringValue("id"));

        verify(this.oldcore.getQueryManager()).createQuery(EXPECTED_QUERY, Query.HQL);
        verify(this.query).bindValue("documentClass", "AI.Documents.Code.DocumentsClass");
        verify(this.query).bindValue("space", "AI.Collections.New.Documents");
        verify(this.query).bindValue("oldId", OLD_ID);
        verify(this.query).setWiki(WIKI);

        BaseObject documentObject = this.oldcore.getSpyXWiki().getDocument(DOCUMENT_REFERENCE, this.context)
            .getXObject(Document.XCLASS_REFERENCE);
        assertEquals(NEW_ID, documentObject.getStringValue("collection"));
    }

    @Test
    void listedPageWithoutDocumentsObjectIsSkippedWithoutSaving() throws Exception
    {
        XWikiDocument collectionDocument = new XWikiDocument(NEW_REFERENCE);
        collectionDocument.newXObject(Collection.XCLASS_REFERENCE, this.context).setStringValue("id", OLD_ID);
        this.oldcore.getSpyXWiki().saveDocument(collectionDocument, this.context);

        this.oldcore.getSpyXWiki().saveDocument(new XWikiDocument(DOCUMENT_REFERENCE), this.context);

        when(this.query.execute()).thenReturn(List.of("AI.Collections.New.Documents.doc1"));
        // Only the saves made by the listener are checked below.
        clearInvocations(this.oldcore.getSpyXWiki());

        this.migrator.processLocalEvent(new DocumentRenamedEvent(OLD_REFERENCE, NEW_REFERENCE), null, null);

        XWikiDocument document = this.oldcore.getSpyXWiki().getDocument(DOCUMENT_REFERENCE, this.context);
        assertNull(document.getXObject(Document.XCLASS_REFERENCE));
        verify(this.oldcore.getSpyXWiki(), never()).saveDocument(
            argThat(doc -> DOCUMENT_REFERENCE.equals(doc.getDocumentReference())), anyString(), anyBoolean(),
            any());
    }

    @Test
    void renamedPageThatIsNotACollectionPageIsIgnored() throws Exception
    {
        DocumentReference otherReference =
            new DocumentReference(WIKI, List.of("AI", "Collections", NEW_ID), "Other");
        XWikiDocument otherDocument = new XWikiDocument(otherReference);
        otherDocument.newXObject(Collection.XCLASS_REFERENCE, this.context).setStringValue("id", OLD_ID);
        this.oldcore.getSpyXWiki().saveDocument(otherDocument, this.context);

        this.migrator.processLocalEvent(new DocumentRenamedEvent(OLD_REFERENCE, otherReference), null, null);

        BaseObject collectionObject = this.oldcore.getSpyXWiki().getDocument(otherReference, this.context)
            .getXObject(Collection.XCLASS_REFERENCE);
        assertEquals(OLD_ID, collectionObject.getStringValue("id"));
        verifyNoInteractions(this.oldcore.getQueryManager());
    }

    @Test
    void renamedCollectionPageWithoutCollectionObjectIsIgnored() throws Exception
    {
        this.oldcore.getSpyXWiki().saveDocument(new XWikiDocument(NEW_REFERENCE), this.context);

        this.migrator.processLocalEvent(new DocumentRenamedEvent(OLD_REFERENCE, NEW_REFERENCE), null, null);

        assertNull(this.oldcore.getSpyXWiki().getDocument(NEW_REFERENCE, this.context)
            .getXObject(Collection.XCLASS_REFERENCE));
        verifyNoInteractions(this.oldcore.getQueryManager());
    }
}
