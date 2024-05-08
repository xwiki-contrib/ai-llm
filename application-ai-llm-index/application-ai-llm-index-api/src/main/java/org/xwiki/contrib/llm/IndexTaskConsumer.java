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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.index.TaskConsumer;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * This class is responsible for consuming the indexing tasks and indexing the documents.
 * 
 * @version $Id$
 */
@Component
@Singleton
@Named("indexing")
public class IndexTaskConsumer implements TaskConsumer
{
    @Inject
    private CollectionManager collectionManager;

    @Inject
    private SolrConnector solrConnector;

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public void consume(DocumentReference documentReference, String version)
    {
        try {
            XWikiDocument xdocument = contextProvider.get().getWiki()
                                        .getDocument(documentReference, contextProvider.get());
            BaseObject documentObject = xdocument.getXObject(Document.XCLASS_REFERENCE);

            String docID = documentObject.getStringValue("id");
            String docCollection = documentObject.getStringValue("collection");

            this.logger.info("Processing document: {}", docID);
            Collection collection = collectionManager.getCollection(docCollection);
            Document document = collection.getDocument(docID);
            solrConnector.deleteChunksByDocId(docID);
            List<Chunk> chunks = document.chunkDocument();
            logger.info("Chunks: {}", chunks);
            for (Chunk chunk : chunks) {
                logger.info("Chunks: docID {}, chunk index {}", chunk.getDocumentID(), chunk.getChunkIndex());
                tryStoringChunk(chunk, collection, xdocument, docID);
            }
        } catch (Exception e) {
            logger.error("Error while processing document [{}]: [{}]", documentReference, e.getMessage());
        }
    }

    private void tryStoringChunk(Chunk chunk, Collection collection, XWikiDocument xdocument, String docID)
        throws SolrServerException
    {
        try {
            chunk.computeEmbeddings(collection.getEmbeddingModel(), xdocument.getAuthors().getContentAuthor());
            solrConnector.storeChunk(chunk, generateChunkID(chunk.getDocumentID(), chunk.getChunkIndex()));
        } catch (IndexException e) {
            this.logger.warn("Error while processing chunk [{}] of document [{}]: [{}]", chunk.getChunkIndex(),
                docID, ExceptionUtils.getRootCauseMessage(e));
        }
    }

    //generate unique id for chunks
    private String generateChunkID(String docID, int chunkIndex)
    {
        return docID + "_" + chunkIndex;
    }

}

