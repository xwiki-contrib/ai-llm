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

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.UserReference;

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
     * @return the embeddings as double array
     */
    public double[] computeEmbeddings(String text)
    {
        try {
            XWikiContext context = this.contextProvider.get();
            WikiReference wikiReference = context.getWikiReference();
            UserReference userReference = CurrentUserReference.INSTANCE;
            List<EmbeddingModelDescriptor> embeddingModelDescriptors = embeddingModelManager
                    .getModelDescriptors(wikiReference, userReference);
            EmbeddingModel embeddingModel = embeddingModelManager
                    .getModel(wikiReference, embeddingModelDescriptors.get(0).getId(), userReference);
            
            double[] embeddingsFull = embeddingModel.embed(text);
    
            // Truncate the embeddings array to 1024 dimensions if necessary
            if (embeddingsFull.length > 1024) {
                return Arrays.copyOf(embeddingsFull, 1024);
            } else {
                return embeddingsFull;
            }
        } catch (Exception e) {
            logger.error("Failure to compute embeddings for the given text: [{}]", e.getMessage());
            return new double[0];
        }
    }
}
