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
package org.xwiki.contrib.llm.internal.livedata;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.contrib.llm.internal.AiLLMSolrCoreInitializer;

import static java.util.Map.entry;

/**
 * Provides the properties of the chunks of the LLM index.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Singleton
public class DefaultLLMIndexPropertiesProvider implements Provider<Map<String, LLMIndexProperty>>, Initializable
{
    private static final String ID = "id";

    private Map<String, LLMIndexProperty> properties;

    @Inject
    @Named("number")
    private Provider<LLMIndexProperty> numberPropertyProvider;

    @Inject
    @Named("text")
    private Provider<LLMIndexProperty> textPropertyProvider;

    @Inject
    @Named("longText")
    private Provider<LLMIndexProperty> longTextPropertyProvider;

    @Override
    public Map<String, LLMIndexProperty> get()
    {
        return this.properties;
    }

    @Override
    public void initialize() throws InitializationException
    {
        Stream<LLMIndexProperty> textProperties = Stream.of(
                entry(ID, ID),
                entry("collection", AiLLMSolrCoreInitializer.FIELD_COLLECTION),
                entry("document", AiLLMSolrCoreInitializer.FIELD_DOC_ID),
                entry("url", AiLLMSolrCoreInitializer.FIELD_DOC_URL),
                entry("language", AiLLMSolrCoreInitializer.FIELD_LANGUAGE),
                entry("errorMessage", AiLLMSolrCoreInitializer.FIELD_ERROR_MESSAGE)
            ).map(entry -> {
                LLMIndexProperty property = this.textPropertyProvider.get();
                property.initialize(entry.getKey(), entry.getValue());
                return property;
            });

        LLMIndexProperty contentProperty = this.longTextPropertyProvider.get();
        contentProperty.initialize("content", AiLLMSolrCoreInitializer.FIELD_CONTENT);
        Stream<LLMIndexProperty> longTextProperties = Stream.of(contentProperty);

        Stream<LLMIndexProperty> numberProperties = Stream.of(
                entry("index", AiLLMSolrCoreInitializer.FIELD_INDEX),
                entry("firstChar", AiLLMSolrCoreInitializer.FIELD_POS_FIRST_CHAR),
                entry("lastChar", AiLLMSolrCoreInitializer.FIELD_POS_LAST_CHAR)
            ).map(
                entry -> {
                    LLMIndexProperty property = this.numberPropertyProvider.get();
                    property.initialize(entry.getKey(), entry.getValue());
                    return property;
                }
            );

        this.properties = Stream.of(textProperties, longTextProperties, numberProperties)
            .flatMap(Function.identity())
            .collect(Collectors.toMap(LLMIndexProperty::getId, Function.identity()));
    }
}
