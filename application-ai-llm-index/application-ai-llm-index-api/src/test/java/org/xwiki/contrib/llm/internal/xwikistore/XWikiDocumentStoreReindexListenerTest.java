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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentStoreReindexListener}.
 *
 * @version $Id$
 */
@ComponentTest
class XWikiDocumentStoreReindexListenerTest
{
    @InjectMockComponents
    private XWikiDocumentStoreReindexListener listener;

    @MockComponent
    private XWikiDocumentStoreReindexer reindexer;

    @MockComponent
    @Named("readonly")
    private Provider<XWikiContext> readOnlyContextProvider;

    @Test
    void listensToTheApplicationReadyEventOnly()
    {
        assertEquals(XWikiDocumentStoreReindexListener.NAME, this.listener.getName());
        assertEquals(1, this.listener.getEvents().size());
        assertInstanceOf(ApplicationReadyEvent.class, this.listener.getEvents().get(0));
    }

    @Test
    void initializeWithoutContextDoesNothing()
    {
        // The component manager initialized the listener once already, with a null read-only context.
        verifyNoInteractions(this.reindexer);

        this.listener.initialize();

        verifyNoInteractions(this.reindexer);
    }

    @Test
    void initializeWithContextStartsTheReindexerOnADaemonThread() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        doAnswer(invocation -> {
            worker.set(Thread.currentThread());
            latch.countDown();
            return null;
        }).when(this.reindexer).requeueIfMarked();
        when(this.readOnlyContextProvider.get()).thenReturn(mock());

        // A context exists at initialization, as after an install at runtime.
        this.listener.initialize();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("ai-llm-xwiki-store-reindex", worker.get().getName());
        assertTrue(worker.get().isDaemon());
    }

    @Test
    void eventStartsTheReindexerAtMostOnce()
    {
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.reindexer, timeout(5000)).requeueIfMarked();
        verify(this.reindexer, times(1)).requeueIfMarked();
    }
}
