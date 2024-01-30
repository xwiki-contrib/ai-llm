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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.EmbeddingModel;
import org.xwiki.contrib.llm.EmbeddingModelDescriptor;
import org.xwiki.contrib.llm.EmbeddingModelManager;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.stability.Unstable;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;

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
    @Inject
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public EmbeddingModel getModel(WikiReference wiki, String id, UserReference userReference) throws GPTAPIException
    {
        XWikiContext context = this.contextProvider.get();
        String currentWiki = context.getWikiId();
        try {
            context.setWikiReference(wiki);
            EmbeddingModel result = this.componentManagerProvider.get().getInstance(EmbeddingModel.class, id);
            if (!result.hasAccess(userReference)) {
                throw new GPTAPIException(
                    String.format("User [%s] does not have access to embedding model [%s] in wiki [%s].",
                        userReference, id, wiki.getName()));
            }
            return result;
        } catch (ComponentLookupException e) {
            throw new GPTAPIException(
                String.format("Failed to get embedding model with name [%s] in wiki [%s].", id, wiki), e);
        } finally {
            context.setWikiId(currentWiki);
        }
    }

    @Override
    public List<EmbeddingModelDescriptor> getModelDescriptors(WikiReference wiki, UserReference userReference)
        throws GPTAPIException
    {
        XWikiContext context = this.contextProvider.get();
        String currentWiki = context.getWikiId();
        try {
            context.setWikiReference(wiki);

            List<EmbeddingModel> models = this.componentManagerProvider.get().getInstanceList(EmbeddingModel.class);
            return models.stream()
                .filter(model -> model.hasAccess(userReference))
                .map(EmbeddingModel::getDescriptor)
                .collect(Collectors.toList());
        } catch (ComponentLookupException e) {
            throw new GPTAPIException(String.format("Failed to get embedding models in wiki [%s].", wiki), e);
        } finally {
            context.setWikiId(currentWiki);
        }
    }
}
