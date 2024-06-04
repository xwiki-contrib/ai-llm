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

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.common.SolrDocument;
import org.xwiki.livedata.LiveDataPropertyDescriptor;
import org.xwiki.livedata.LiveDataQuery;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.search.solr.SolrUtils;

/**
 * Abstract helper class to implement an {@link LLMIndexProperty}.
 *
 * @version $Id$
 * @since 0.4
 */
public abstract class AbstractLLMIndexProperty implements LLMIndexProperty
{
    private static final String TEXT = "text";

    private static final String TRANSLATION_PREFIX = "aiLLM.index.livedata.properties.";

    private static final String WILDCARD = "*";

    private String id;

    private String solrProperty;

    private String displayName;

    @Inject
    private ContextualLocalizationManager localizationManager;

    @Inject
    private SolrUtils solrUtils;

    @Override
    public void initialize(String id, String solrProperty)
    {
        this.id = id;
        this.solrProperty = solrProperty;
        this.displayName = this.localizationManager.getTranslationPlain(TRANSLATION_PREFIX + id);
        if (this.displayName == null) {
            this.displayName = id;
        }
    }

    @Override
    public Object getValue(SolrDocument document)
    {
        return Objects.requireNonNullElse(document.getFieldValue(this.solrProperty), "");
    }

    @Override
    public LiveDataPropertyDescriptor getPropertyDescriptor()
    {
        LiveDataPropertyDescriptor propertyDescriptor = new LiveDataPropertyDescriptor();
        propertyDescriptor.setId(getId());
        propertyDescriptor.setName(getDisplayName());
        propertyDescriptor.setDescription("Description");
        propertyDescriptor.setDisplayer(new LiveDataPropertyDescriptor.DisplayerDescriptor(TEXT));
        propertyDescriptor.setVisible(true);
        propertyDescriptor.setSortable(true);
        propertyDescriptor.setFilterable(true);
        propertyDescriptor.setFilter(new LiveDataPropertyDescriptor.FilterDescriptor(TEXT));
        propertyDescriptor.initialize();
        return propertyDescriptor;
    }

    @Override
    public String getFilterQuery(LiveDataQuery.Filter liveDataFilter)
    {
        return liveDataFilter.getConstraints().stream()
            .flatMap(this::getConstraintQuery)
            .collect(Collectors.joining(liveDataFilter.isMatchAll() ? " AND " : " OR "));
    }

    protected Stream<String> getConstraintQuery(LiveDataQuery.Constraint constraint)
    {
        String value = String.valueOf(constraint.getValue());
        boolean isBlank = StringUtils.isBlank(value);
        String queryPrefix = this.solrProperty + ":";
        String partialValue = this.solrUtils.toFilterQueryString(value);
        String completeValue = this.solrUtils.toCompleteFilterQueryString(value);

        return switch (constraint.getOperator()) {
            // Ignore empty filters as they can easily be added but not removed in the UI and this is confusing for
            // users.
            case "contains" -> !isBlank ? Stream.of(queryPrefix + WILDCARD + partialValue + WILDCARD) : Stream.empty();
            case "startsWith" -> !isBlank ? Stream.of(queryPrefix + partialValue + WILDCARD) : Stream.empty();
            case "equals" -> Stream.of(queryPrefix + completeValue);
            case "less" -> Stream.of("%s[* TO %s}".formatted(queryPrefix, completeValue));
            case "greater" -> Stream.of("%s{%s TO *]".formatted(queryPrefix, completeValue));
            case "empty" -> Stream.of("(*:* AND -%s[* TO *])".formatted(queryPrefix));
            default -> Stream.empty();
        };
    }

    @Override
    public String getId()
    {
        return this.id;
    }

    @Override
    public String getDisplayName()
    {
        return this.displayName;
    }

    @Override
    public String getSolrProperty()
    {
        return this.solrProperty;
    }
}
