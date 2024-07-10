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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.RequestError;
import org.xwiki.environment.Environment;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;

/**
 * An internal GPT API server that uses DJL to compute embeddings.
 *
 * @version $Id$
 * @since 0.5
 */
@Component(roles = GPTAPIServerWikiComponent.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
@Named("internal")
public class InternalGPTAPIServer extends AbstractGPTAPIServer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalGPTAPIServer.class);

    private static final String CACHE_DIR_PROPERTY = "DJL_CACHE_DIR";

    @Inject
    private Environment environment;

    @Override
    public List<double[]> embed(String modelName, List<String> texts) throws RequestError
    {
        try {
            if (StringUtils.isBlank(System.getProperty(CACHE_DIR_PROPERTY))) {
                System.setProperty(CACHE_DIR_PROPERTY,
                    this.environment.getPermanentDirectory().toPath().resolve("cache/djl.ai").toAbsolutePath()
                        .toString());
            }

            Criteria<String, float[]> criteria =
                Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/" + modelName)
                    .optEngine("PyTorch")
                    .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                    .build();

            try (ZooModel<String, float[]> model = criteria.loadModel();
                 Predictor<String, float[]> predictor = model.newPredictor()) {

                return computeEmbeddingsWithPredictor(texts, predictor);
            }
        } catch (Exception e) {
            throw new RequestError(500, "Failed to compute embedding using DJL with model [%s].".formatted(modelName),
                e);
        }
    }

    private List<double[]> computeEmbeddingsWithPredictor(List<String> texts, Predictor<String, float[]> predictor)
    {
        return texts.stream()
            .map(text -> predictAndConvert(text, predictor))
            .toList();
    }

    private double[] predictAndConvert(String text, Predictor<String, float[]> predictor)
    {
        try {
            float[] embeddings = predictor.predict(text);
            return convertFloatToDouble(embeddings);
        } catch (Exception e) {
            LOGGER.error("Failed to compute embeddings for text: {}", text, e);
            return new double[0];
        }
    }

    /**
     * Convert a float array to a double array.
     *
     * @param floatArray the float array to convert
     * @return the converted double array
     */
    private double[] convertFloatToDouble(float[] floatArray)
    {
        double[] doubleArray = new double[floatArray.length];
        for (int i = 0; i < floatArray.length; i++) {
            doubleArray[i] = floatArray[i];
        }
        return doubleArray;
    }
}
