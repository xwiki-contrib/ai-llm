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
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.EmbeddingModel;
import org.xwiki.contrib.llm.EmbeddingModelDescriptor;
import org.xwiki.contrib.llm.EmbeddingModelManager;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.stability.Unstable;
import org.xwiki.user.UserReference;

/**
 * Default implementation of {@link EmbeddingModelManager}.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@Component
@Singleton
public class DefaultEmbeddingModelManager implements EmbeddingModelManager
{
    private static final String MODEL_SEPARATOR = "/";

    @Inject
    private GPTAPIConfigProvider configProvider;

    @Inject
    private Provider<OpenAIEmbeddingModel> openAIEmbeddingModelProvider;

    @Override
    public EmbeddingModel getModel(WikiReference wiki, String id, UserReference userReference) throws GPTAPIException
    {
        Map<String, GPTAPIConfig> configObjects = this.configProvider.getConfigObjects(wiki.getName(), userReference);

        String[] parts = StringUtils.split(id, MODEL_SEPARATOR, 2);
        if (parts.length != 2 || StringUtils.isBlank(parts[0])) {
            throw new GPTAPIException(String.format("Invalid model name [%s]", id));
        }

        GPTAPIConfig config = configObjects.get(parts[0]);

        if (config == null) {
            throw new GPTAPIException(String.format("No configuration with name [%s] found", parts[0]));
        }

        if (config.getEmbeddingModels().stream().map(EmbeddingModelDescriptor::getId).anyMatch(parts[1]::equals)) {
            OpenAIEmbeddingModel model = this.openAIEmbeddingModelProvider.get();
            model.initialize(parts[1], config);
            return model;
        } else {
            throw new GPTAPIException("No model with name [" + parts[1] + "] found");
        }
    }

    @Override
    public List<EmbeddingModelDescriptor> getModelDescriptors(WikiReference wiki, UserReference userReference)
        throws GPTAPIException
    {
        return this.configProvider.getConfigObjects(wiki.getName(), userReference).values().stream()
            .flatMap(config -> config.getEmbeddingModels().stream().map(descriptor -> {
                String id = config.getName() + MODEL_SEPARATOR + descriptor.getId();
                return new EmbeddingModelDescriptor(id, descriptor.getName(), descriptor.getDimensions());
            }))
            .collect(Collectors.toList());
    }
}
