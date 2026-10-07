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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.index.TaskManager;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrCoreInitializer;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiContext;

/**
 * Re-queues every collection using the XWiki document store for indexing when the
 * {@link XWikiDocumentStoreReindexMarker} is set. The work runs on the caller's thread; the thread hand-off lives in
 * {@link XWikiDocumentStoreReindexListener}.
 *
 * @version $Id$
 * @since 0.10.1
 */
@Component(roles = XWikiDocumentStoreReindexer.class)
@Singleton
public class XWikiDocumentStoreReindexer
{
    @Inject
    private ExecutionContextManager executionContextManager;

    @Inject
    private Execution execution;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Solr solr;

    @Inject
    @Named(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE)
    private SolrCoreInitializer coreInitializer;

    @Inject
    private XWikiDocumentStoreReindexMarker marker;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    private TaskManager taskManager;

    @Inject
    private Logger logger;

    /**
     * Initialize the Solr core and, when the marker is set, re-queue every collection using the XWiki document
     * store in every wiki.
     *
     * <p>The core is initialized explicitly rather than only loaded: {@link Solr#getCore(String)} serves a cached
     * core once it has been created, so after an install or an upgrade through the Extension Manager, without a
     * restart, the schema migration of the new version would otherwise not run until the next restart and the
     * marker would not be set. Running {@link SolrCoreInitializer#initialize(org.xwiki.search.solr.XWikiSolrCore)}
     * here is the single code path for both the startup and the runtime-install case: every step of the initializer
     * is gated on the stored schema version, so on a current core it only checks the versions and commits.</p>
     *
     * <p>The marker is cleared after the wikis have been visited, whether or not every wiki could be re-queued: a
     * wiki whose re-queue failed is logged as an error with the manual recovery. It is kept when the wikis cannot be
     * listed or the core cannot be initialized at all, so that the next start retries. The re-queue is idempotent:
     * the collection indexing task removes stray chunks and queues only the documents that have no chunks.</p>
     */
    public void requeueIfMarked()
    {
        try {
            this.executionContextManager.initialize(new ExecutionContext());

            this.coreInitializer.initialize(this.solr.getCore(AiLLMSolrCoreInitializer.DEFAULT_AILLM_SOLR_CORE));

            if (this.marker.isSet()) {
                requeueAllWikis();
            }
        } catch (Exception e) {
            this.logger.error("Failed to re-queue the collections using the XWiki document store.", e);
        } finally {
            this.execution.removeContext();
        }
    }

    private void requeueAllWikis() throws WikiManagerException
    {
        int requeued = 0;
        for (String wiki : this.wikiDescriptorManager.getAllIds()) {
            try {
                requeued += requeueWiki(wiki);
            } catch (Exception e) {
                this.logger.error("Failed to re-queue the collections of wiki [{}] (root cause: [{}]). Reindex "
                    + "the collections of that wiki that use the XWiki document store manually from the "
                    + "collections administration.", wiki, ExceptionUtils.getRootCauseMessage(e), e);
            }
        }

        // The marker is cleared whether or not every wiki succeeded: the failed ones are reported above.
        this.marker.clear();

        this.logger.info("Re-queued [{}] collections using the XWiki document store for indexing.", requeued);
    }

    /**
     * @return the number of re-queued collections
     */
    private int requeueWiki(String wiki) throws IndexException
    {
        XWikiContext context = this.contextProvider.get();
        String previousWiki = context.getWikiId();
        context.setWikiId(wiki);
        try {
            int requeued = 0;
            for (String collectionId : this.collectionManager.getCollections()) {
                Collection collection = this.collectionManager.getCollection(collectionId);
                if (collection != null && XWikiDocumentStore.NAME.equals(collection.getDocumentStoreHint())) {
                    this.taskManager.addTask(wiki, collection.getDocumentId(),
                        XWikiDocumentCollectionIndexingTaskConsumer.NAME);
                    requeued++;
                }
            }
            return requeued;
        } finally {
            context.setWikiId(previousWiki);
        }
    }
}
