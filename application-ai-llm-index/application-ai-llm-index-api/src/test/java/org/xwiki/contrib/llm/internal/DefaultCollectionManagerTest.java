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

import org.junit.jupiter.api.Test;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link DefaultCollectionManager}.
 *
 * @version $Id$
 */
@OldcoreTest
@ComponentList({ DefaultCollection.class })
@ReferenceComponentList
class DefaultCollectionManagerTest
{
    public static final String WIKI_NAME = "testwiki";

    public static final String COLLECTION_NAME = "testcollection";

    public static final DocumentReference COLLECTION_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_NAME), "WebHome");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultCollectionManager collectionManager;

    @Test
    void createCollection() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);

        mockCollectionsQuery(List.of());

        DefaultCollection collection = this.collectionManager.createCollection(COLLECTION_NAME);

        assertEquals(COLLECTION_REFERENCE, collection.getCollectionDocument().getDocumentReference());
        assertEquals(COLLECTION_NAME, collection.getName());

        collection.save();

        XWikiDocument document = this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context);
        assertNotNull(document.getXObject(Collection.XCLASS_REFERENCE));
        assertEquals(COLLECTION_NAME, document.getTitle());
    }

    @Test
    void getCollections() throws Exception
    {
        List<Object> collections = List.of("collection1", "collection2");
        mockCollectionsQuery(collections);

        assertEquals(collections, this.collectionManager.getCollections());
    }

    private void mockCollectionsQuery(List<Object> collections) throws QueryException, ComponentLookupException
    {
        Query mockQuery = mock(Query.class);
        when(this.oldcore.getQueryManager().createQuery(anyString(), eq(Query.HQL)))
            .thenReturn(mockQuery);
        when(mockQuery.execute()).thenReturn(collections);
    }

    @Test
    void getCollection() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        XWikiDocument doc = new XWikiDocument(COLLECTION_REFERENCE);
        String title = "Test Collection";
        doc.setTitle(title);
        this.oldcore.getSpyXWiki().saveDocument(doc, context);

        DefaultCollection collection = this.collectionManager.getCollection(COLLECTION_NAME);
        assertEquals(title, collection.getName());
    }

    @Test
    void deleteCollection() throws Exception
    {
        // Create a collection
        XWikiContext context = this.oldcore.getXWikiContext();
        context.setWikiId(WIKI_NAME);
        mockCollectionsQuery(List.of());

        this.collectionManager.createCollection(COLLECTION_NAME).save();
        mockCollectionsQuery(List.of(COLLECTION_NAME));

        assertFalse(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).isNew());

        // Delete the collection
        this.collectionManager.deleteCollection(COLLECTION_NAME, false);

        // Check that the collection has been deleted
        assertTrue(this.oldcore.getSpyXWiki().getDocument(COLLECTION_REFERENCE, context).isNew());
    }

    @Test
    void getDocumentReference()
    {
        this.oldcore.getXWikiContext().setWikiId(WIKI_NAME);

        assertEquals(COLLECTION_REFERENCE, this.collectionManager.getDocumentReference(COLLECTION_NAME));
    }
}
