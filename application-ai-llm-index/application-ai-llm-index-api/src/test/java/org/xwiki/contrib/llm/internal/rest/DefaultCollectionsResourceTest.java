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
package org.xwiki.contrib.llm.internal.rest;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.contrib.llm.internal.CurrentUserCollectionManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.UserReferenceSerializer;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link DefaultCollectionsResource}.
 *
 * @version $Id$
 */
@OldcoreTest
@ComponentList({ CurrentUserCollectionManager.class })
class DefaultCollectionsResourceTest
{
    private static final String COLLECTION_1 = "collection1";

    private static final String WIKI_NAME = "wiki";

    private static final DocumentReference COLLECTION_1_REFERENCE =
        new DocumentReference(WIKI_NAME, List.of("AI", "Collections", COLLECTION_1), "WebHome");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultCollectionsResource collectionsResource;

    @MockComponent
    private SolrConnector solrConnector;

    @MockComponent
    private AiLLMSolrCoreInitializer aillmSolrCoreInitializer;

    @MockComponent
    private GroupManager groupManager;

    @Test
    void getCollections() throws Exception
    {
        Query mockQuery = mock(Query.class);
        when(this.oldcore.getQueryManager().createQuery(anyString(), eq(Query.HQL)))
            .thenReturn(mockQuery);
        List<Object> collections = List.of(COLLECTION_1, "collection2");
        when(mockQuery.execute()).thenReturn(collections);

        when(this.oldcore.getMockContextualAuthorizationManager().hasAccess(Right.VIEW, COLLECTION_1_REFERENCE))
            .thenReturn(true);

        assertEquals(List.of(COLLECTION_1), this.collectionsResource.getCollections(WIKI_NAME));
    }
}
