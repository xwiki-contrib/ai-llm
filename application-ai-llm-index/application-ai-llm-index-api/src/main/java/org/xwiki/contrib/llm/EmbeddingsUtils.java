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
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Utility class used in chunking the documents.
 * 
 * @version $Id$
 */
@Component(roles = EmbeddingsUtils.class)
@Singleton
public class EmbeddingsUtils implements Initializable
{
    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject 
    private EmbeddingModelManager embeddingModelManager;

    private RetryRegistry retryRegistry;

    /**
     * Compute embeddings for given text.
     *
     * @param text the text to compute embeddings for
     * @param modelId the model id
     * @param userReference the user reference
     * @param purpose the purpose of the embeddings
     * @return the embeddings as double array
     */
    public double[] computeEmbeddings(String text,
                                     String modelId,
                                     UserReference userReference,
                                     EmbeddingModel.EmbeddingPurpose purpose) throws IndexException
    {
        return computeEmbeddings(List.of(text), modelId, userReference, purpose).get(0);
    }

    /**
     * Compute embeddings for given texts.
     *
     * @param texts the texts to compute embeddings for
     * @param modelId the model id
     * @param userReference the user reference
     * @param purpose the purpose of the embeddings
     * @return the embeddings as list of double arrays
     * @throws IndexException if an error occurs while computing the embeddings
     */
    public List<double[]> computeEmbeddings(List<String> texts,
                                         String modelId,
                                         UserReference userReference,
                                         EmbeddingModel.EmbeddingPurpose purpose)
        throws IndexException
    {
        try {
            XWikiContext context = this.contextProvider.get();
            WikiReference wikiReference = context.getWikiReference();
            EmbeddingModel embeddingModel = this.embeddingModelManager.getModel(wikiReference, modelId, userReference);

            // Make sure that the same model on different wikis has different retry objects.
            String retryId = wikiReference.getName() + ":" + modelId;
            Retry retry = this.retryRegistry.retry(retryId);
            List<double[]> embeddingsFull;
            if (texts.size() == 1) {
                embeddingsFull = retry.executeCallable(() -> List.of(embeddingModel.embed(texts.get(0), purpose)));
            } else {
                embeddingsFull = retry.executeCallable(() -> embeddingModel.embed(texts, purpose));
            }
            return embeddingsFull.stream()
                .map(embeddings -> Arrays.copyOf(embeddings, AiLLMSolrCoreInitializer.NUMBER_OF_DIMENSIONS))
                .toList();
        } catch (Exception e) {
            throw new IndexException("Failed to compute embeddings for texts [" + texts + "]", e);
        }
    }

    /**
     * Get the maximum number of texts that can be processed in parallel by the model.
     *
     * @param modelId the model id
     * @param userReference the user reference for which the model shall be loaded
     * @return the maximum number of texts that can be processed in parallel
     * @throws IndexException if an error occurs while loading the model
     */
    public int getMaximumNumberOfTexts(String modelId, UserReference userReference) throws IndexException
    {
        XWikiContext context = this.contextProvider.get();
        WikiReference wikiReference = context.getWikiReference();
        try {
            EmbeddingModel embeddingModel = this.embeddingModelManager.getModel(wikiReference, modelId, userReference);
            return embeddingModel.getMaximumParallelism();
        } catch (GPTAPIException e) {
            throw new IndexException("Failed to get the model [" + modelId + "]", e);
        }
    }

    @Override
    public void initialize()
    {
        // Retry on rate-limited requests with exponential backoff
        IntervalFunction intervalFunction = IntervalFunction.ofExponentialBackoff(1000, 2);
        RetryConfig config = RetryConfig.custom()
            .maxAttempts(3)
            .intervalFunction(intervalFunction)
            .retryOnException(e -> e instanceof RequestError requestError && requestError.getCode() == 429)
            .build();

        this.retryRegistry = RetryRegistry.of(config);
    }
}
