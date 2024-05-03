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
package org.xwiki.contrib.llm.openai;

import java.util.List;

import org.xwiki.stability.Unstable;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The context used for completing the chat.
 *
 * @param collectionId the unique identifier of the collection containing the context document
 * @param documentId the unique identifier of the context document
 * @param url the URL of the context document
 * @param content the content of the context document
 * @param similarityScore the similarity score between the conversation and the context document
 * @param vector (optional) the vector embedding of the context document
 *
 * @version $Id$
 * @since 0.3
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonAutoDetect(creatorVisibility = JsonAutoDetect.Visibility.ANY)
@Unstable
public record Context(
    String collectionId,
    String documentId,
    String url,
    String content,
    Double similarityScore,
    List<Float> vector
)
{
}
