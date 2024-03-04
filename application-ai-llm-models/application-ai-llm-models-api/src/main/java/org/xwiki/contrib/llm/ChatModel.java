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
import org.xwiki.user.UserReference;

/**
 * A chat model that can be used to process a chat request.
 * <p>
 *     Chat models could internally implement complex behavior including access control, rate limiting, or
 *     retrieval-augmented generation.
 * </p>
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@Role
public interface ChatModel
{
    /**
     * @param request the request to process
     * @param consumer the consumer that will be called for every chat response that is received.
     */
    void processStreaming(ChatRequest request, FailableConsumer<ChatResponse, IOException> consumer)
        throws IOException, RequestError;

    /**
     * @param request the request to process
     * @return the response
     */
    ChatResponse process(ChatRequest request) throws IOException, RequestError;

    /**
     * @return {@code true} if the model supports streaming, {@code false} otherwise
     */
    boolean supportsStreaming();

    /**
     * @return the descriptor of the model
     */
    ChatModelDescriptor getDescriptor();

    /**
     * @param user the user to check access for
     * @return {@code true} if the user has access to the model, {@code false} otherwise
     */
    boolean hasAccess(UserReference user);
}
