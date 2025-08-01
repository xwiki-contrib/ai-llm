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
package org.xwiki.contrib.llm.internal.authorization;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.authorization.AuthorizationManagerBuilder;
import org.xwiki.contrib.llm.authorization.ExternalAuthorizationConfiguration;
import org.xwiki.contrib.llm.internal.HttpClientFactory;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.LocalDocumentReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.objects.BaseObject;

/**
 * An {@link AuthorizationManagerBuilder} that checks an external API if access to the documents is allowed.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Singleton
@Named("external")
public class ExternalAuthorizationManagerBuilder implements AuthorizationManagerBuilder
{
    private static final String URL_FIELD = "url";

    private static final String APPLICATION_JSON = "application/json";

    private static final List<String> SPACE_NAMES = List.of("AI", "Collections", "Code");

    private static final LocalDocumentReference CONFIGURATION_CLASS_REFERENCE =
        new LocalDocumentReference(SPACE_NAMES, "ExternalAuthorizationConfigurationClass");

    private static final LocalDocumentReference CONFIGURATION_SHEET_REFERENCE =
        new LocalDocumentReference(SPACE_NAMES, "ExternalAuthorizationConfigurationSheet");

    @Inject
    private HttpClientFactory httpClientFactory;

    @Inject
    private Logger logger;

    @Inject
    private ExternalAuthorizationRequestBuilder externalAuthorizationRequestBuilder;

    @Override
    public AuthorizationManager build(BaseObject configurationObject) throws IndexException
    {
        return buildInternal(getExternalAuthorizationConfiguration(configurationObject));
    }

    private AuthorizationManager buildInternal(ExternalAuthorizationConfiguration configuration)
    {
        return documentIds -> {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(configuration.url()))
                    .header("Accept", APPLICATION_JSON)
                    .header("Content-Type", APPLICATION_JSON)
                    .header("User-Agent", "XWiki AI LLM Application");

                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

                ExternalAuthorizationRequest request = this.externalAuthorizationRequestBuilder.build(documentIds);

                builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)));

                HttpResponse<InputStream> httpResponse = this.httpClientFactory.createHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

                if (httpResponse.statusCode() != 200) {
                    throw new IOException("HTTP request failed with status code " + httpResponse.statusCode());
                }

                return objectMapper.readValue(httpResponse.body(),
                    new ObjectMapper().getTypeFactory().constructMapType(Map.class, String.class, Boolean.class));
            } catch (InterruptedException e) {
                this.logger.warn("Failed to check access to documents at url [{}], thread was interrupted: {}",
                    configuration.url(), ExceptionUtils.getRootCauseMessage(e));
                Thread.currentThread().interrupt();
                return Map.of();
            } catch (Exception e) {
                this.logger.warn("Failed to check access to documents at url [{}]: {}", configuration.url(),
                    ExceptionUtils.getRootCauseMessage(e));
                return Map.of();
            }
        };
    }

    @Override
    public EntityReference getConfigurationClassReference()
    {
        return CONFIGURATION_CLASS_REFERENCE;
    }

    @Override
    public EntityReference getConfigurationSheetReference()
    {
        return CONFIGURATION_SHEET_REFERENCE;
    }

    @Override
    public Class<?> getConfigurationType()
    {
        return ExternalAuthorizationConfiguration.class;
    }

    @Override
    public Object getConfiguration(BaseObject object)
    {
        return getExternalAuthorizationConfiguration(object);
    }

    private static ExternalAuthorizationConfiguration getExternalAuthorizationConfiguration(BaseObject object)
    {
        return new ExternalAuthorizationConfiguration(object.getStringValue(URL_FIELD));
    }

    @Override
    public void setConfiguration(BaseObject object, Object configuration)
    {
        if (configuration instanceof ExternalAuthorizationConfiguration externalAuthorizationConfiguration) {
            object.setStringValue(URL_FIELD, externalAuthorizationConfiguration.url());
        }
    }
}
