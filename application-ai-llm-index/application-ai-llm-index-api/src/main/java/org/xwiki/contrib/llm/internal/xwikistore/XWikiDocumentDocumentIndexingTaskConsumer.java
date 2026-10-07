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
package org.xwiki.contrib.llm.internal.xwikistore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.DocumentIndexer;
import org.xwiki.index.IndexException;
import org.xwiki.index.TaskConsumer;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * A {@link TaskConsumer} that indexes documents that are regular XWiki documents.
 *
 * <p>Hidden documents are never part of a collection, matching the bulk listing of
 * {@link XWikiDocumentStoreHelper}: a task for a hidden document that belongs to a collection removes the chunks
 * the document may have from an earlier indexing and indexes nothing. A document that belongs to no collection is
 * neither loaded nor touched in the index.</p>
 *
 * @version $Id$
 * @since 0.5
 */
@Component
@Singleton
@Named(XWikiDocumentDocumentIndexingTaskConsumer.NAME)
public class XWikiDocumentDocumentIndexingTaskConsumer implements TaskConsumer
{
    /**
     * The name of this task consumer.
     */
    public static final String NAME = "llm_xwiki_document";

    @Inject
    private XWikiDocumentStoreHelper xWikiDocumentStoreHelper;

    @Inject
    private DocumentIndexer documentIndexer;

    @Inject
    private SolrConnector solrConnector;

    @Inject
    @Named("withparameters")
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Override
    public void consume(DocumentReference documentReference, String version) throws IndexException
    {
        String serializedDocumentName = this.entityReferenceSerializer.serialize(documentReference);
        XWikiContext context = this.contextProvider.get();

        Map<String, List<String>> collectionsPerWiki = getCollectionsPerWiki(documentReference, context);
        if (collectionsPerWiki.isEmpty()) {
            return;
        }

        if (isHidden(documentReference, context)) {
            // The chunk ids are the "withparameters" serialization of the reference with locale, the same id
            // XWikiDocumentDocument#getID() writes.
            this.logger.debug("Removing the chunks of the hidden document [{}] instead of indexing it.",
                serializedDocumentName);
            this.solrConnector.deleteChunksByStoreHintAndDocId(XWikiDocumentStore.NAME, serializedDocumentName);
            return;
        }

        try {
            for (Map.Entry<String, List<String>> entry : collectionsPerWiki.entrySet()) {
                for (String collection : entry.getValue()) {
                    this.documentIndexer.indexDocument(entry.getKey(), collection, serializedDocumentName);
                }
            }
        } catch (org.xwiki.contrib.llm.IndexException e) {
            throw new IndexException("Failed to index document [" + serializedDocumentName + "]", e);
        }
    }

    /**
     * @return the collections the document belongs to, per wiki: the document's own wiki and, for a document of a
     *     subwiki, the main wiki whose collections may span wikis; wikis without a matching collection are absent
     */
    private Map<String, List<String>> getCollectionsPerWiki(DocumentReference documentReference,
        XWikiContext context) throws IndexException
    {
        String documentWiki = documentReference.getWikiReference().getName();
        String mainXWiki = context.getMainXWiki();
        Map<String, List<String>> result = new LinkedHashMap<>();
        try {
            addCollections(result, documentReference, documentWiki);
            if (!Objects.equals(mainXWiki, documentWiki)) {
                addCollections(result, documentReference, mainXWiki);
            }
        } catch (org.xwiki.contrib.llm.IndexException e) {
            throw new IndexException("Failed to find the collections of document [" + documentReference + "]", e);
        }
        return result;
    }

    private void addCollections(Map<String, List<String>> result, DocumentReference documentReference, String wiki)
        throws org.xwiki.contrib.llm.IndexException
    {
        List<String> collections =
            this.xWikiDocumentStoreHelper.getCollections(documentReference, new WikiReference(wiki));
        if (!collections.isEmpty()) {
            result.put(wiki, collections);
        }
    }

    private boolean isHidden(DocumentReference documentReference, XWikiContext context) throws IndexException
    {
        try {
            XWikiDocument document = context.getWiki().getDocument(documentReference, context);
            return Boolean.TRUE.equals(document.isHidden());
        } catch (XWikiException e) {
            throw new IndexException("Failed to load document [" + documentReference + "]", e);
        }
    }
}
