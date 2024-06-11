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
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Chunk;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;

/**
 * Indexes a document by chunking it and storing the chunks in Solr.
 *
 * @version $Id$
 * @since 0.4
 */
@Component(roles = DocumentIndexer.class)
@Singleton
public class DocumentIndexer
{
    @Inject
    private CollectionManager collectionManager;

    @Inject
    private SolrConnector solrConnector;

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * Index a document by chunking it and storing the chunks in Solr.
     *
     * @param wiki the wiki where the document is
     * @param collection the collection where the document is
     * @param document the document to index
     * @throws IndexException if an error occurs while indexing the document
     */
    public void indexDocument(String wiki, String collection, String document) throws IndexException
    {
        XWikiContext context = this.contextProvider.get();

        String previousWiki = context.getWikiId();

        try {
            Collection collectionObj = this.collectionManager.getCollection(collection);
            Document documentObj = collectionObj.getDocumentStore().getDocument(document);
            this.solrConnector.deleteChunksByDocId(wiki, collection, document);
            List<Chunk> chunks = documentObj.chunkDocument();
            for (Chunk chunk : chunks) {
                tryStoringChunk(chunk, collectionObj, collectionObj.getAuthor(), document);
            }
        } catch (SolrServerException e) {
            throw new IndexException("Error while storing chunks", e);
        } catch (AccessDeniedException e) {
            throw new IndexException("Access denied while getting document for chunking", e);
        } finally {
            context.setWikiId(previousWiki);
        }
    }

    private void tryStoringChunk(Chunk chunk, Collection collection, UserReference author, String docID)
        throws SolrServerException
    {
        try {
            chunk.computeEmbeddings(collection.getEmbeddingModel(), author);
        } catch (IndexException e) {
            this.logger.warn("Error while embedding chunk [{}] of document [{}]: [{}]", chunk.getChunkIndex(),
                docID, ExceptionUtils.getRootCauseMessage(e));

            chunk.setErrorMessage("Error computing the embedding: %s".formatted(ExceptionUtils.getRootCauseMessage(e)));
        }
        this.solrConnector.storeChunk(chunk, generateChunkID(chunk));
    }

    private String generateChunkID(Chunk chunk)
    {
        String separator = "_";
        List<String> parts = List.of(chunk.getWiki(), chunk.getCollection(), chunk.getDocumentID(),
            String.valueOf(chunk.getChunkIndex()));
        // Use URL encoding escaping to avoid having the separator in any of the parts
        return parts.stream()
            .map(part -> StringUtils.replaceEach(part, new String[] { separator, "%" }, new String[] { "%5F", "%25" }))
            .collect(Collectors.joining(separator));
    }
}
