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

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatRequestFilter;
import org.xwiki.contrib.llm.ChatRequestFilterBuilder;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.objects.BaseObject;

/**
 * Builds a {@link ChatRequestFilter} from an object that contains its configuration.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Singleton
// The RAG filter should be applied towards the end of the filter chain, so give it a high priority (low values are
// first).
@Priority(10000)
@Named("rag")
public class RAGChatRequestFilterBuilder implements ChatRequestFilterBuilder
{
    private static final List<String> AI_CODE_SPACE = List.of("AI", "Code");

    private static final LocalDocumentReference CLASS_REFERENCE =
        new LocalDocumentReference(AI_CODE_SPACE, "RAGChatRequestFilterClass");

    private static final LocalDocumentReference SHEET_REFERENCE =
        new LocalDocumentReference(AI_CODE_SPACE, "RAGChatRequestFilterSheet");

    private static final String COLLECTIONS_FIELD = "collections";

    @Inject
    private SolrConnector solrConnector;

    @Override
    public List<ChatRequestFilter> build(BaseObject object)
    {
        List<?> rawCollections = object.getListValue(COLLECTIONS_FIELD);
        // Safe cast since the field is defined as a list of strings.
        List<String> collections = rawCollections.stream()
            .map(o -> (String) o)
            .collect(Collectors.toList());

        // Only return a filter if there are collections to filter on.
        return collections.isEmpty() ? List.of() : List.of(new RAGChatRequestFilter(collections, solrConnector));
    }

    @Override
    public EntityReference getClassReference()
    {
        return CLASS_REFERENCE;
    }

    @Override
    public EntityReference getSheetReference()
    {
        return SHEET_REFERENCE;
    }
}
