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
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.rest.CollectionsResource;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;

import com.xpn.xwiki.XWikiContext;

/**
 * Default implementation of {@link CollectionsResource}.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named("org.xwiki.contrib.llm.internal.rest.DefaultCollectionsResource")
@Singleton
public class DefaultCollectionsResource extends XWikiResource implements CollectionsResource
{
    @Inject
    @Named("currentUser")
    private CollectionManager collectionManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public List<String> getCollections(String wikiName) throws XWikiRestException
    {
        XWikiContext context = this.contextProvider.get();

        String currentWiki = context.getWikiId();

        try {
            context.setWikiId(wikiName);

            return this.collectionManager.getCollections();
        } catch (IndexException e) {
            throw new XWikiRestException("Failed to get collections", e);
        } finally {
            context.setWikiId(currentWiki);
        }
    }
}
