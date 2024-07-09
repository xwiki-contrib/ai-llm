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
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.stability.Unstable;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Default implementation of {@link GPTAPIConfigProvider}.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Unstable
@Singleton
public class DefaultGPTAPIConfigProvider implements GPTAPIConfigProvider 
{
    private static final List<String> AI_SPACE_NAMES = List.of("AI", "Code");

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private GPTAPIConfigBuilder configBuilder;

    @Override
    public Map<String, GPTAPIConfig> getConfigObjects(String currentWiki)
        throws GPTAPIException
    {
        XWikiContext context = contextProvider.get();
        com.xpn.xwiki.XWiki xwiki = context.getWiki();
        return getConfigFromDoc(xwiki, context, currentWiki);
    }

    /**
     * @param xwiki       The XWiki instance.
     * @param context     The current XWiki context.
     * @param currentWiki The current Wiki id.
     * @return A map object of {@link #GPTAPIConfig}
     */
    private Map<String, GPTAPIConfig> getConfigFromDoc(com.xpn.xwiki.XWiki xwiki, XWikiContext context,
            String currentWiki) throws GPTAPIException
    {
        // Retrieve the LLM Configuration Objects
        try {
            DocumentReference configDocumentReference = new DocumentReference(currentWiki, AI_SPACE_NAMES, "AIConfig");
            DocumentReference configClassReference =
                new DocumentReference(currentWiki, AI_SPACE_NAMES, "AIConfigClass");
            XWikiDocument doc = xwiki.getDocument(configDocumentReference, context);
            List<BaseObject> configObjects = doc.getXObjects(configClassReference);
            return configObjects.stream()
                .filter(Objects::nonNull)
                .map(this.configBuilder::build)
                .collect(Collectors.toMap(GPTAPIConfig::getName, Function.identity()));

        } catch (XWikiException e) {
            throw new GPTAPIException("Error while trying to access the configuration.", e);
        }
    }
}
