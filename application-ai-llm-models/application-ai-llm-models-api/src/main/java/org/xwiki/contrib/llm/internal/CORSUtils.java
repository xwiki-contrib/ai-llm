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

import java.util.Map;

import javax.ws.rs.core.Response;

import org.xwiki.contrib.llm.ChatClientConfig;
import org.xwiki.contrib.llm.ChatClientConfigProvider;

/**
 * Utility class for CORS handling.
 * 
 * @version $Id$
 * @since 0.1
 */
public final class CORSUtils
{
    /**
     * The name of the header that carries the allowed origin.
     */
    public static final String CORS_ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    /**
     * The name of the header that carries the allowed methods.
     */
    public static final String CORS_ALLOW_METHODS = "Access-Control-Allow-Methods";

    /**
     * The name of the header that carries the allowed request headers.
     */
    public static final String CORS_ALLOW_HEADERS = "Access-Control-Allow-Headers";

    private CORSUtils()
    {
        // Private constructor to hide the implicit public one
    }

    /**
     * Match the origin of a request to a list of allowed origins.
     * 
     * @param requestOrigin The origin of the request.
     * @param configProvider The configuration provider.
     * @param wikiName The name of the wiki.
     * @return The origin of the request if it is allowed, null otherwise.
     */
    public static String matchOrigin(String requestOrigin, ChatClientConfigProvider configProvider, String wikiName)
    {
        if (requestOrigin == null) {
            return null;
        }
        try {
            Map<String, ChatClientConfig> originConfigs = configProvider.getConfigObjects(wikiName);
            for (ChatClientConfig config : originConfigs.values()) {
                if (config.getURL().equals(requestOrigin)) {
                    return requestOrigin;
                }
            }
        } catch (Exception e) {
            // Consider logging the exception
        }
        return null;
    }

    /**
     * Add CORS headers to a response.
     * 
     * @param origin The origin of the request.
     * @param methods The allowed methods.
     * @param headers The allowed headers.
     * @return A response builder with the CORS headers added.
     */
    public static Response.ResponseBuilder addCORSHeaders(String origin, String methods, String headers)
    {
        return addCORSHeaders(Response.ok(), origin, methods, headers);
    }

    /**
     * Add CORS headers to a response under construction. The allow-origin header carries the given origin and is
     * omitted when the origin is {@code null}, i.e. when the request origin matched no configured chat client, so
     * an unknown origin is never echoed back.
     *
     * @param builder the response under construction
     * @param origin the matched origin of the request, or {@code null} when there is none
     * @param methods the allowed methods
     * @param headers the allowed headers
     * @return the given builder with the CORS headers added
     * @since 0.11
     */
    public static Response.ResponseBuilder addCORSHeaders(Response.ResponseBuilder builder, String origin,
        String methods, String headers)
    {
        if (origin != null) {
            builder.header(CORS_ALLOW_ORIGIN, origin);
        }
        return builder.header(CORS_ALLOW_METHODS, methods)
                      .header(CORS_ALLOW_HEADERS, headers);
    }
}
