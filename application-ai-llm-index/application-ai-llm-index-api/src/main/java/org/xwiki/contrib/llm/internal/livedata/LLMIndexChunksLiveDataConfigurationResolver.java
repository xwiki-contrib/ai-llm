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

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.livedata.LiveDataConfiguration;
import org.xwiki.livedata.LiveDataConfigurationResolver;
import org.xwiki.livedata.LiveDataException;
import org.xwiki.livedata.LiveDataMeta;
import org.xwiki.livedata.LiveDataPaginationConfiguration;
import org.xwiki.livedata.LiveDataPropertyDescriptor;
import org.xwiki.livedata.LiveDataPropertyDescriptorStore;
import org.xwiki.livedata.internal.JSONMerge;

/**
 * Configuration resolver to set the properties of the live data columns.
 *
 * @version $Id$
 */
@Component
@Singleton
@Named("llmIndexChunks")
public class LLMIndexChunksLiveDataConfigurationResolver implements LiveDataConfigurationResolver<LiveDataConfiguration>
{
    private static final String DATE_FILTER = "date";

    private static final String DATE_FORMAT = "dateFormat";

    @Inject
    @Named("llmIndexChunks")
    private LiveDataPropertyDescriptorStore propertyStoreProvider;

    @Inject
    @Named("wiki")
    private ConfigurationSource wikiConfig;

    private final JSONMerge jsonMerge = new JSONMerge();

    @Override
    public LiveDataConfiguration resolve(LiveDataConfiguration input) throws LiveDataException
    {
        if (input.getMeta() == null) {
            input.setMeta(new LiveDataMeta());
        }

        input.getMeta().initialize();
        // Work around regression introduced in XWiki 14.10.17
        input.getMeta().setLayouts(null);
        input.getMeta().setPropertyDescriptors(this.jsonMerge.merge(this.propertyStoreProvider.get(),
            input.getMeta().getPropertyDescriptors()));

        LiveDataPaginationConfiguration paginationConfiguration = new LiveDataPaginationConfiguration();
        paginationConfiguration.initialize();
        paginationConfiguration.setShowFirstLast(true);
        paginationConfiguration.setShowPageSizeDropdown(true);
        input.getMeta().setPagination(paginationConfiguration);

        maybeSetDateFormat(input.getMeta());

        return input;
    }

    private void maybeSetDateFormat(LiveDataMeta meta)
    {
        String dateFormat = this.wikiConfig.getProperty("dateformat");
        if (StringUtils.isNotEmpty(dateFormat)) {
            Optional<LiveDataPropertyDescriptor.FilterDescriptor> dateFilter =
                meta.getFilters().stream().filter(filter -> DATE_FILTER.equals(filter.getId())).findFirst();
            dateFilter.ifPresentOrElse(filterDescriptor -> filterDescriptor.setParameter(DATE_FORMAT, dateFormat),
                () -> {
                    LiveDataPropertyDescriptor.FilterDescriptor filterDescriptor =
                        new LiveDataPropertyDescriptor.FilterDescriptor(DATE_FILTER);
                    filterDescriptor.setParameter(DATE_FORMAT, dateFormat);
                    meta.getFilters().add(filterDescriptor);
                });
        }
    }

}
