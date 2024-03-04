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
import org.xwiki.component.annotation.Role;
import org.xwiki.stability.Unstable;

/**
 * A filter that can modify the chat completion request before it is sent to the model. It can also modify the
 * response, or even return a response without calling the model.
 *
 * @version $Id$
 * @since 0.3
 */
@Role
@Unstable
public interface ChatRequestFilter
{
    /**
     * Sets the next filter in the chain.
     *
     * @param next the next filter
     */
    void setNext(ChatRequestFilter next);

    /**
     * Processes the given request and calls the given consumer for every chunk of the response.
     *
     * @param request the request to process
     * @param consumer the consumer that will be called for every chunk that is received.
     */
    void processStreaming(ChatRequest request, FailableConsumer<ChatResponse, IOException> consumer)
        throws IOException, RequestError;

    /**
     * Processes the given request and returns the response.
     *
     * @param request the request to process
     * @return the response
     */
    ChatResponse process(ChatRequest request) throws RequestError;
}
