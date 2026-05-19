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
package org.xwiki.contrib.llm.internal.mcp;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.security.SecurityConfiguration;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that performs hybrid semantic + keyword search over indexed collections.
 * Contributed by the index module when installed; the MCP server has no compile-time
 * knowledge of this class.
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Named(MCPSearchCollectionsTool.TOOL_ID)
@Singleton
public class MCPSearchCollectionsTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint and as the config object key.
     */
    public static final String TOOL_ID = "search_collections";

    private static final int DEFAULT_LIMIT = 10;

    private static final String QUERY_PARAM = "query";

    private static final String COLLECTIONS_PARAM = "collections";

    private static final String LIMIT_KEYWORD_PARAM = "limitKeywordResults";

    private static final String LIMIT_SEMANTIC_PARAM = "limitSemanticResults";

    private static final String REQUIRED_QUERY_MESSAGE = "Error: 'query' parameter is required and must not be empty.";

    private static final String INVALID_LIMITS_MESSAGE = "Error: Limits must be greater than or equal to 0.";

    private static final String QUERY_STRING_PARAMETER_MESSAGE = "Error: 'query' parameter must be a string.";

    private static final String COLLECTION_ARRAY_PARAMETER_MESSAGE =
        "Error: 'collections' parameter must be an array of strings.";

    private static final String INTEGER_PARAMETER_MESSAGE = "Error: '%s' parameter must be an integer.";

    private static final String TYPE = "type";

    private static final String STRING = "string";

    private static final String DESCRIPTION = "description";

    private static final String INTEGER = "integer";

    private static final String OBJECT = "object";

    @Inject
    private Logger logger;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    private SecurityConfiguration securityConfiguration;

    @Override
    public String getId()
    {
        return TOOL_ID;
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder()
            .name(TOOL_ID)
            .description("Search indexed collections using semantic and keyword similarity. "
                + "Returns the most relevant content chunks from indexed pages.")
            .inputSchema(buildInputSchema())
            .build();
    }

    @Override
    public boolean isEnabled()
    {
        // TODO: consult MCPToolConfig XObject for this tool ID.
        return true;
    }

    @Override
    public McpSchema.CallToolResult execute(McpTransportContext context, McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String query = getQueryParam(args);

            int keywordLimit = getIntParam(args, LIMIT_KEYWORD_PARAM);
            int semanticLimit = getIntParam(args, LIMIT_SEMANTIC_PARAM);

            validateLimits(keywordLimit, semanticLimit);

            List<String> collections = getCollectionsParam(args);
            if (collections.isEmpty()) {
                collections = this.collectionManager.getCollections();
            }

            List<Context> results =
                this.collectionManager.hybridSearch(query, collections, semanticLimit, keywordLimit);
            return McpSchema.CallToolResult.builder()
                .addTextContent(formatResults(results))
                .build();
        } catch (IndexException e) {
            this.logger.error("MCP search_collections tool failed for query [{}]", args.get(QUERY_PARAM), e);
            return buildErrorResult("Error searching collections: " + ExceptionUtils.getRootCauseMessage(e));
        } catch (IllegalArgumentException e) {
            return buildErrorResult(e.getMessage());
        }
    }

    private McpSchema.JsonSchema buildInputSchema()
    {
        Map<String, Object> properties = Map.of(
            QUERY_PARAM, Map.of(
                TYPE, STRING,
                DESCRIPTION, "The text to search for"
            ),
            COLLECTIONS_PARAM, Map.of(
                TYPE, "array",
                "items", Map.of(TYPE, STRING),
                DESCRIPTION,
                "Optional list of collection IDs to search in. Omit to search all accessible collections."
            ),
            LIMIT_KEYWORD_PARAM, Map.of(
                TYPE, INTEGER,
                DESCRIPTION, "Maximum number of keyword search results (default: %d)".formatted(DEFAULT_LIMIT)
            ),
            LIMIT_SEMANTIC_PARAM, Map.of(
                TYPE, INTEGER,
                DESCRIPTION,
                "Maximum number of semantic similarity results (default: %d)".formatted(DEFAULT_LIMIT)
            )
        );
        return new McpSchema.JsonSchema(OBJECT, properties, List.of(QUERY_PARAM), null, null, null);
    }

    private String getQueryParam(Map<String, Object> args)
    {
        Object value = args.get(QUERY_PARAM);
        if (value == null || value instanceof String) {
            String query = (String) value;
            if (StringUtils.isBlank(query)) {
                throw new IllegalArgumentException(REQUIRED_QUERY_MESSAGE);
            }
            return query;
        }
        throw new IllegalArgumentException(QUERY_STRING_PARAMETER_MESSAGE);
    }

    @SuppressWarnings("unchecked")
    private List<String> getCollectionsParam(Map<String, Object> args)
    {
        Object value = args.get(COLLECTIONS_PARAM);
        if (value == null) {
            return List.of();
        }

        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(COLLECTION_ARRAY_PARAMETER_MESSAGE);
        }

        for (Object entry : list) {
            if (!(entry instanceof String)) {
                throw new IllegalArgumentException(COLLECTION_ARRAY_PARAMETER_MESSAGE);
            }
        }

        // The cast is safe because we checked the contents of the list above.
        return (List<String>) list;
    }

    private void validateLimits(int keywordLimit, int semanticLimit)
    {
        if (keywordLimit < 0 || semanticLimit < 0) {
            throw new IllegalArgumentException(INVALID_LIMITS_MESSAGE);
        }
        if (isLimitExceeded(keywordLimit) || isLimitExceeded(semanticLimit)) {
            throw new IllegalArgumentException(getLimitExceededMessage());
        }
    }

    private boolean isLimitExceeded(int limit)
    {
        int configuredLimit = this.securityConfiguration.getQueryItemsLimit();
        return configuredLimit > 0 && limit > configuredLimit;
    }

    private String getLimitExceededMessage()
    {
        return "Error: Limits must be less than or equal to " + this.securityConfiguration.getQueryItemsLimit();
    }

    private int getIntParam(Map<String, Object> args, String key)
    {
        Object value = args.get(key);
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            if (StringUtils.isBlank(stringValue)) {
                throw new IllegalArgumentException(getIntegerParameterMessage(key));
            }
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(getIntegerParameterMessage(key), e);
            }
        }
        throw new IllegalArgumentException(getIntegerParameterMessage(key));
    }

    private String getIntegerParameterMessage(String key)
    {
        return INTEGER_PARAMETER_MESSAGE.formatted(key);
    }

    private McpSchema.CallToolResult buildErrorResult(String message)
    {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .isError(true)
            .build();
    }

    private String formatResults(List<Context> results)
    {
        if (results.isEmpty()) {
            return "No relevant content found.";
        }
        StringBuilder sb = new StringBuilder();
        for (Context ctx : results) {
            sb.append("<result>\n");
            if (ctx.url() != null) {
                sb.append("<url>").append(ctx.url()).append("</url>\n");
            }
            if (ctx.documentId() != null) {
                sb.append("<documentId>").append(ctx.documentId()).append("</documentId>\n");
            }
            if (ctx.content() != null) {
                sb.append("<content>\n").append(ctx.content()).append("\n</content>\n");
            }
            sb.append("</result>\n");
        }
        return sb.toString().trim();
    }
}
