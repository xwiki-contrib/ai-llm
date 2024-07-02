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

import java.util.List;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.DocumentIndexer;
import org.xwiki.index.IndexException;
import org.xwiki.index.TaskConsumer;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;

import com.xpn.xwiki.XWikiContext;

/**
 * A {@link TaskConsumer} that indexes documents that are regular XWiki documents.
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
    @Named("withparameters")
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public void consume(DocumentReference documentReference, String version) throws IndexException
    {
        String serializedDocumentName = this.entityReferenceSerializer.serialize(documentReference);
        String documentWiki = documentReference.getWikiReference().getName();

        try {
            List<String> collections =
                this.xWikiDocumentStoreHelper.getCollections(documentReference, documentReference.getWikiReference());
            for (String collection : collections) {
                this.documentIndexer.indexDocument(documentWiki, collection, serializedDocumentName);
            }


            String mainXWiki = this.contextProvider.get().getMainXWiki();
            // If the document isn't from the main wiki, also find collections in the main wiki.
            if (!Objects.equals(mainXWiki, documentWiki)) {
                List<String> mainCollections = this.xWikiDocumentStoreHelper.getCollections(documentReference,
                    new WikiReference(mainXWiki));
                for (String collection : mainCollections) {
                    this.documentIndexer.indexDocument(mainXWiki, collection, serializedDocumentName);
                }
            }
        } catch (org.xwiki.contrib.llm.IndexException e) {
            throw new IndexException("Failed to index document [" + serializedDocumentName + "]", e);
        }
    }
}
