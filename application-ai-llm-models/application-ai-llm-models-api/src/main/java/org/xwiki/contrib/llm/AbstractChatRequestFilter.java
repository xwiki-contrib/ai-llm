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

import java.io.IOException;

import org.apache.commons.lang3.function.FailableConsumer;

/**
 * Abstract implementation of {@link ChatRequestFilter} that just forwards the request to the next filter in the chain.
 * Inheritors should override the methods they want to implement and call the methods in this class to forward the
 * request to the filter.
 *
 * @version $Id$
 * @since 0.3
 */
public abstract class AbstractChatRequestFilter implements ChatRequestFilter
{
    private ChatRequestFilter next;

    @Override
    public void setNext(ChatRequestFilter next)
    {
        this.next = next;
    }

    @Override
    public void processStreaming(ChatRequest request, FailableConsumer<ChatResponse, IOException> consumer)
        throws IOException, RequestError
    {
        if (this.next != null) {
            this.next.processStreaming(request, consumer);
        }
    }

    @Override
    public ChatResponse process(ChatRequest request) throws RequestError
    {
        return this.next != null ? this.next.process(request) : null;
    }
}
