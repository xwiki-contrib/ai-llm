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

import java.lang.reflect.Type;
import java.util.List;

import javax.inject.Provider;

import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.contrib.llm.EmbeddingModel;
import org.xwiki.contrib.llm.EmbeddingModelDescriptor;
import org.xwiki.contrib.llm.RequestError;

/**
 * Implementation of {@link EmbeddingModel} that uses the OpenAI API.
 *
 * @version $Id$
 * @since 0.3
 */
public class DefaultEmbeddingModel extends AbstractModel implements EmbeddingModel
{
    private final Provider<ComponentManager> componentManagerProvider;

    /**
     * Constructor.
     *
     * @param config the model configuration
     * @param componentManager the component manager
     * @throws ComponentLookupException if a component cannot be found
     */
    public DefaultEmbeddingModel(ModelConfiguration config, ComponentManager componentManager)
        throws ComponentLookupException
    {
        super(config, componentManager);
        this.componentManagerProvider = componentManager.getInstance(
            new DefaultParameterizedType(null, Provider.class, ComponentManager.class), "context");
    }

    @Override
    public double[] embed(String text) throws RequestError
    {
        return embed(List.of(text)).get(0);
    }

    @Override
    public List<double[]> embed(List<String> texts) throws RequestError
    {
        try {
            GPTAPIServer server = this.componentManagerProvider.get()
                .getInstance(GPTAPIServer.class, this.modelConfiguration.getServerName());
            return server.embed(this.modelConfiguration.getModel(), texts);
        } catch (ComponentLookupException e) {
            throw new RequestError(500, "Could not find the GPT API server");
        }
    }

    @Override
    public EmbeddingModelDescriptor getDescriptor()
    {
        return new EmbeddingModelDescriptor(getRoleHint(), this.modelConfiguration.getName(),
            this.modelConfiguration.getDimensions());
    }

    @Override
    public Type getRoleType()
    {
        return EmbeddingModel.class;
    }

    @Override
    public int getMaximumParallelism()
    {
        return this.modelConfiguration.getMaximumParallelism();
    }
}
