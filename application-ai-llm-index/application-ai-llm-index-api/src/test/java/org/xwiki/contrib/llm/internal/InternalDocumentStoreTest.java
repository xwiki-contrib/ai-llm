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

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link InternalDocumentStore}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class InternalDocumentStoreTest
{
    private static final String WIKI_NAME = "wiki";

    private static final String COLLECTION_ID = "testCollection";

    private static final String DOCUMENT_ID = "testDocument";

    private static final DocumentReference COLLECTION_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_ID), "WebHome");

    @InjectMockComponents
    private InternalDocumentStore documentStore;

    @MockComponent
    private QueryManager queryManager;

    @Mock
    private Collection collection;

    @Mock
    private Query query;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.collection.getDocumentReference()).thenReturn(COLLECTION_REFERENCE);
        when(this.collection.getID()).thenReturn(COLLECTION_ID);
        when(this.queryManager.createQuery(anyString(), eq(Query.HQL))).thenReturn(this.query);
        when(this.query.execute()).thenReturn(List.of(DOCUMENT_ID));

        this.documentStore.initialize(this.collection, null);
    }

    @Test
    void getDocumentReference()
    {
        DocumentReference reference =
            InternalDocumentStore.getDocumentReference(WIKI_NAME, COLLECTION_ID, DOCUMENT_ID);

        assertEquals(new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_ID, "Documents"),
            DigestUtils.sha256Hex(DOCUMENT_ID)), reference);
    }

    @Test
    void getDocumentReferencesMatchesStaticReference() throws Exception
    {
        assertEquals(List.of(InternalDocumentStore.getDocumentReference(WIKI_NAME, COLLECTION_ID, DOCUMENT_ID)),
            this.documentStore.getDocumentReferences(0, 10));
    }

    @Test
    void getDocumentNamesIsBoundToTheDocumentsSpace() throws Exception
    {
        assertEquals(List.of(DOCUMENT_ID), this.documentStore.getDocumentNames(0, 10));

        verify(this.queryManager).createQuery(argThat(hql -> hql.contains("doc.space = :space")), eq(Query.HQL));
        verify(this.query).setWiki(WIKI_NAME);
        verify(this.query).bindValue("space", "AI.Collections." + COLLECTION_ID + ".Documents");
        verify(this.query).bindValue("collection", COLLECTION_ID);
        verify(this.query).setLimit(10);
        verify(this.query).setOffset(0);
    }

    @Test
    void getDocumentNamesEscapesTheCollectionId() throws Exception
    {
        String dottedCollectionId = "test.collection";
        DocumentReference dottedCollectionReference =
            new DocumentReference(WIKI_NAME, List.of("AI", "Collections", dottedCollectionId), "WebHome");
        Collection dottedCollection = mock(Collection.class);
        when(dottedCollection.getDocumentReference()).thenReturn(dottedCollectionReference);
        when(dottedCollection.getID()).thenReturn(dottedCollectionId);
        this.documentStore.initialize(dottedCollection, null);

        this.documentStore.getDocumentNames(0, -1);

        verify(this.query).bindValue("space", "AI.Collections.test\\.collection.Documents");
        assertEquals(dottedCollectionId, InternalDocumentStore.getDocumentReference(WIKI_NAME, dottedCollectionId,
            DOCUMENT_ID).getLastSpaceReference().getParent().getName());
    }
}
