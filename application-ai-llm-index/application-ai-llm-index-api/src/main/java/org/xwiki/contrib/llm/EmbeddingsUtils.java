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

import java.util.Arrays;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.user.UserReference;

import com.robrua.nlp.bert.Bert;
import com.xpn.xwiki.XWikiContext;

/**
 * Utility class used in chunking the documents.
 * 
 * @version $Id$
 */
@Component(roles = EmbeddingsUtils.class)
@Singleton
public class EmbeddingsUtils
{

    private static final int EMBEDDINGS_SIZE_FROM_SOLR_SCHEMA = 384;

    @Inject 
    private Provider<XWikiContext> contextProvider;

    @Inject 
    private EmbeddingModelManager embeddingModelManager;

    @Inject
    private Logger logger;

    /**
     * Compute embeddings for given text.
     *
     * @param text the text to compute embeddings for
     * @param modelId the model id
     * @param userReference the user reference
     * @return the embeddings as double array
     */
    public double[] computeEmbeddings(String text, String modelId, UserReference userReference)
    {
        try {
            XWikiContext context = this.contextProvider.get();
            WikiReference wikiReference = context.getWikiReference();
            EmbeddingModel embeddingModel = embeddingModelManager.getModel(wikiReference, modelId, userReference);
    
            double[] embeddingsFull = embeddingModel.embed(text);
            return Arrays.copyOf(embeddingsFull, EMBEDDINGS_SIZE_FROM_SOLR_SCHEMA);
        } catch (Exception e) {
            logger.error("Failed to compute embeddings using the specified model: [{}] Using fallback model.",
                         e.getMessage());
        }
    
        try (Bert bert = Bert.load("com/robrua/nlp/easy-bert/bert-multi-cased-L-12-H-768-A-12")) {
            float[] embeddingsFull = bert.embedSequence(text);
            return Arrays.copyOf(convertToDoubleArray(embeddingsFull), EMBEDDINGS_SIZE_FROM_SOLR_SCHEMA);
        } catch (Exception e) {
            logger.error("Failed to compute embeddings using the fallback model: [{}]", e.getMessage());
            return new double[0];
        }
    }
    
    private double[] convertToDoubleArray(float[] floatArray)
    {
        int length = Math.min(floatArray.length, EMBEDDINGS_SIZE_FROM_SOLR_SCHEMA);
        return IntStream.range(0, length)
                        .mapToDouble(i -> floatArray[i])
                        .toArray();
    }
    
    
}
