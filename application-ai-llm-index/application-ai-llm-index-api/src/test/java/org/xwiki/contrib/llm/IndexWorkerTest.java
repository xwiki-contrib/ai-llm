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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.contrib.llm.internal.DefaultDocument;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.reference.ReferenceComponentList;
import com.xpn.xwiki.web.Utils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit test for {@link IndexWorker}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class IndexWorkerTest
{
    private static final String WIKI_NAME = "wiki";

    private static final DocumentReference DOCUMENT_REFERENCE = new DocumentReference(WIKI_NAME, "Space", "Page");

    private static final String COLLECTION = "testCollection";

    private static final String DOCUMENT_ID = "testDocument";

    @InjectMockComponents
    private IndexWorker indexWorker;

    @MockComponent
    private TaskManager taskManager;

    @MockComponent
    private SolrConnector solrConnector;

    @BeforeEach
    void setUp(MockitoComponentManager componentManager)
    {
        Utils.setComponentManager(componentManager);
    }

    @Test
    void documentCreated()
    {
        XWikiDocument createdDocument = getDocumentWithObject();
        XWikiDocument previousDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        createdDocument.setOriginalDocument(previousDocument);

        this.indexWorker.processLocalEvent(new DocumentCreatedEvent(DOCUMENT_REFERENCE), createdDocument,
            mock(XWikiContext.class));

        verify(this.taskManager).addTask(WIKI_NAME, createdDocument.getId(), IndexTaskConsumer.NAME);
    }

    @Test
    void documentUpdated()
    {
        XWikiDocument updatedDocument = getDocumentWithObject();
        XWikiDocument previousDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        updatedDocument.setOriginalDocument(previousDocument);

        this.indexWorker.processLocalEvent(new DocumentUpdatedEvent(DOCUMENT_REFERENCE), updatedDocument,
            mock(XWikiContext.class));

        verify(this.taskManager).addTask(WIKI_NAME, updatedDocument.getId(), IndexTaskConsumer.NAME);
    }

    @Test
    void documentDeleted()
    {
        XWikiDocument deletedDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        XWikiDocument previousDocument = getDocumentWithObject();
        deletedDocument.setOriginalDocument(previousDocument);

        this.indexWorker.processLocalEvent(new DocumentDeletedEvent(DOCUMENT_REFERENCE), deletedDocument,
            mock(XWikiContext.class));

        verifyNoInteractions(this.taskManager);

        verify(this.solrConnector).deleteChunksByDocId(WIKI_NAME, COLLECTION, DOCUMENT_ID);
    }

    private static XWikiDocument getDocumentWithObject()
    {
        XWikiDocument resultDocument = new XWikiDocument(DOCUMENT_REFERENCE);
        BaseObject object = new BaseObject();
        object.setXClassReference(Document.XCLASS_REFERENCE);
        object.setStringValue(DefaultDocument.ID_KEY, DOCUMENT_ID);
        object.setStringValue(DefaultDocument.PARENT_COLLECTION, COLLECTION);
        resultDocument.addXObject(object);
        return resultDocument;
    }
}
