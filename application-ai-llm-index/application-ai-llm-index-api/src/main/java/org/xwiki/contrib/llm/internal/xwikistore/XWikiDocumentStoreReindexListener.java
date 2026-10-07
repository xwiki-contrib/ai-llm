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

import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWikiContext;

/**
 * Starts the {@link XWikiDocumentStoreReindexer} once the application is ready, on its own daemon thread so that the
 * application startup never waits for it. The reindexer runs at most once per instance of this listener: an install
 * or upgrade through the Extension Manager creates a new instance, which starts it again, and that run is cheap and
 * idempotent.
 *
 * <p>When this component is initialized while the application is already running, which is the case for an install
 * or an upgrade through the Extension Manager, the reindexer is started from {@link #initialize()} because the
 * {@link ApplicationReadyEvent} has already been fired. The reindexer initializes the Solr core itself, so the chunk
 * cleanup of a new version and the re-queue both happen on that run, without waiting for a restart.</p>
 *
 * @version $Id$
 * @since 0.10.1
 */
@Component
@Named(XWikiDocumentStoreReindexListener.NAME)
@Singleton
public class XWikiDocumentStoreReindexListener extends AbstractLocalEventListener implements Initializable
{
    /**
     * The name of the event listener.
     */
    public static final String NAME = "org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStoreReindexListener";

    private static final String THREAD_NAME = "ai-llm-xwiki-store-reindex";

    private final AtomicBoolean started = new AtomicBoolean();

    @Inject
    private XWikiDocumentStoreReindexer reindexer;

    @Inject
    @Named("readonly")
    private Provider<XWikiContext> readOnlyContextProvider;

    /**
     * Default constructor.
     */
    public XWikiDocumentStoreReindexListener()
    {
        super(NAME, new ApplicationReadyEvent());
    }

    @Override
    public void initialize()
    {
        // A context exists only when the application is already running, i.e., the listener is registered after the
        // ApplicationReadyEvent has been fired.
        if (this.readOnlyContextProvider.get() != null) {
            start();
        }
    }

    @Override
    public void processLocalEvent(Event event, Object source, Object data)
    {
        start();
    }

    /**
     * Start the reindexer on a daemon thread, unless this listener has already started it.
     */
    private void start()
    {
        if (this.started.compareAndSet(false, true)) {
            Thread thread = new Thread(this.reindexer::requeueIfMarked, THREAD_NAME);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }
    }
}
