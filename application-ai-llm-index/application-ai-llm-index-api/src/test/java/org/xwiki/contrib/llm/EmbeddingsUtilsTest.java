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

import java.util.List;

import javax.inject.Provider;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmbeddingsUtils}.
 *
 * @version $Id$
 */
@ComponentTest
class EmbeddingsUtilsTest
{
    private static final String MODEL = "embedding-model";

    private static final String SECRET_TEXT = "confidential chunk content";

    @InjectMockComponents
    private EmbeddingsUtils embeddingsUtils;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private EmbeddingModelManager embeddingModelManager;

    @Mock
    private XWikiContext context;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private UserReference userReference;

    @Test
    void computeEmbeddingsFailureNamesTheCountAndTheModelNotTheTexts() throws Exception
    {
        WikiReference wikiReference = new WikiReference("xwiki");
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.context.getWikiReference()).thenReturn(wikiReference);
        when(this.embeddingModelManager.getModel(wikiReference, MODEL, this.userReference))
            .thenReturn(this.embeddingModel);
        List<String> texts = List.of(SECRET_TEXT, "second " + SECRET_TEXT);
        // Not a rate-limit error, so the call is not retried.
        RuntimeException failure = new RuntimeException("provider down");
        when(this.embeddingModel.embed(texts, EmbeddingModel.EmbeddingPurpose.INDEX)).thenThrow(failure);

        IndexException exception = assertThrows(IndexException.class, () -> this.embeddingsUtils
            .computeEmbeddings(texts, MODEL, this.userReference, EmbeddingModel.EmbeddingPurpose.INDEX));

        assertEquals("Failed to compute embeddings for [2] texts with model [embedding-model]",
            exception.getMessage());
        assertFalse(exception.getMessage().contains(SECRET_TEXT));
        assertSame(failure, exception.getCause());
    }
}
