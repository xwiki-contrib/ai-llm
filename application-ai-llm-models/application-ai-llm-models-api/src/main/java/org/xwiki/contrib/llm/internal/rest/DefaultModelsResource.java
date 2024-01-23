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
package org.xwiki.contrib.llm.internal.rest;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChatModelDescriptor;
import org.xwiki.contrib.llm.ChatModelManager;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.contrib.llm.rest.ModelsResource;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.user.CurrentUserReference;

import com.theokanning.openai.OpenAiResponse;

/**
 * Default implementation of {@link ModelsResource}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named("org.xwiki.contrib.llm.internal.rest.DefaultModelsResource")
@Singleton
public class DefaultModelsResource extends XWikiResource implements ModelsResource
{
    @Inject
    private ChatModelManager chatModelManager;

    @Override
    public OpenAiResponse<ChatModelDescriptor> getModels(String wikiName) throws XWikiRestException
    {
        try {
            List<ChatModelDescriptor> models = this.chatModelManager.getModels(CurrentUserReference.INSTANCE, wikiName);
            OpenAiResponse<ChatModelDescriptor> response = new OpenAiResponse<>();
            response.setData(models);
            response.setObject("list");
            return response;
        } catch (GPTAPIException e) {
            throw new XWikiRestException("Error loading the list of chat models.", e);
        }
    }
}
