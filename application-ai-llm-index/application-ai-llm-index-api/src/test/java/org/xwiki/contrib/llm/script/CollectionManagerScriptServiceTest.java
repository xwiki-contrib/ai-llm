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
package org.xwiki.contrib.llm.script;

import java.util.List;

import javax.inject.Named;

import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexTaskConsumer;
import org.xwiki.contrib.llm.internal.CollectionIndexingTaskConsumer;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentCollectionIndexingTaskConsumer;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentDocumentIndexingTaskConsumer;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.index.TaskManager;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link CollectionManagerScriptService}.
 *
 * @version $Id$
 */
@ComponentTest
class CollectionManagerScriptServiceTest
{
    private static final String SOLR_QUERY = "*:*";

    @InjectMockComponents
    private CollectionManagerScriptService scriptService;

    @MockComponent
    @Named("currentUser")
    private CollectionManager collectionManager;

    @MockComponent
    private ContextualAuthorizationManager authorizationManager;

    @MockComponent
    private TaskManager taskManager;

    @Test
    void documentIndexingQueueSizeSumsDocumentAndCollectionTasks()
    {
        when(this.taskManager.getQueueSize(IndexTaskConsumer.NAME)).thenReturn(1L);
        when(this.taskManager.getQueueSize(XWikiDocumentDocumentIndexingTaskConsumer.NAME)).thenReturn(10L);
        when(this.taskManager.getQueueSize(CollectionIndexingTaskConsumer.NAME)).thenReturn(100L);
        when(this.taskManager.getQueueSize(XWikiDocumentCollectionIndexingTaskConsumer.NAME)).thenReturn(1000L);

        assertEquals(1111L, this.scriptService.getDocumentIndexingQueueSize());
    }

    @Test
    void searchWithoutProgrammingRightIsRefused() throws Exception
    {
        AccessDeniedException denied = new AccessDeniedException(Right.PROGRAM, null, null);
        doThrow(denied).when(this.authorizationManager).checkAccess(Right.PROGRAM);

        AccessDeniedException thrown = assertThrows(AccessDeniedException.class,
            () -> this.scriptService.search(SOLR_QUERY, 10, false));

        assertSame(denied, thrown);
        verify(this.collectionManager, never()).search(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void searchWithProgrammingRightDelegates() throws Exception
    {
        List<Context> results =
            List.of(new Context("collection", "document", null, "url", "content", 0.5, List.of(0.1f)));
        when(this.collectionManager.search(SOLR_QUERY, 10, true)).thenReturn(results);

        assertSame(results, this.scriptService.search(SOLR_QUERY, 10, true));

        var order = inOrder(this.authorizationManager, this.collectionManager);
        order.verify(this.authorizationManager).checkAccess(Right.PROGRAM);
        order.verify(this.collectionManager).search(SOLR_QUERY, 10, true);
    }
}
