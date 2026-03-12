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
package org.xwiki.contrib.llm.mcp.internal;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.openai.Context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.XWikiContext;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.scheduler.Schedulers;

/**
 * Singleton component that manages the lifecycle of the MCP (Model Context Protocol) server. It creates and owns the
 * {@link HttpServletStatelessServerTransport} that the JAX-RS resource delegates HTTP requests to, and registers the
 * {@code search_wiki} tool backed by {@link CollectionManager#hybridSearch}.
 *
 * @version $Id$
 * @since 0.8
 */
@Component(roles = XWikiMCPServerManager.class)
@Singleton
public class XWikiMCPServerManager implements Initializable, Disposable
{
    /**
     * Property key set on the {@link ExecutionContext} by {@link DefaultMCPResource} for the duration of an MCP
     * request. The scheduler hook checks for this property so that context propagation is scoped to MCP requests only
     * and does not interfere with unrelated Reactor work running in the same JVM.
     */
    static final String MCP_CONTEXT_PROPAGATION_PROPERTY = "xwiki.mcp.contextPropagation";

    private static final int DEFAULT_LIMIT = 10;

    private static final String SEARCH_TOOL_NAME = "search_wiki";

    private static final String QUERY_PARAM = "query";

    private static final String COLLECTIONS_PARAM = "collections";

    private static final String LIMIT_KEYWORD_PARAM = "limitKeywordResults";

    private static final String LIMIT_SEMANTIC_PARAM = "limitSemanticResults";

    private static final String TYPE = "type";

    private static final String STRING = "string";

    private static final String DESCRIPTION = "description";

    private static final String INTEGER = "integer";

    private static final String SCHEDULER_HOOK_KEY = "xwiki-execution-propagation";

    private static final String OBJECT = "object";

    @Inject
    private Logger logger;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    private Execution execution;

    private McpStatelessSyncServer mcpServer;

    private HttpServletStatelessServerTransport transportProvider;

    @Override
    public void initialize()
    {
        // Register a Reactor scheduler hook to propagate XWiki's ExecutionContext (which carries the XWikiContext
        // with wiki identity, current user, etc.) from the JAX-RS request thread to the Reactor boundedElastic
        // threads used by the MCP SDK internally.
        // The JAX-RS thread is blocked in block() while the Reactor thread runs, so sharing the XWikiContext
        // object is safe (no concurrent access).
        // The hook is intentionally scoped: it only propagates when MCP_CONTEXT_PROPAGATION_PROPERTY is present on
        // the captured context (set by DefaultMCPResource for the duration of each MCP request), so unrelated Reactor
        // work running elsewhere in the JVM is not affected.
        Schedulers.onScheduleHook(SCHEDULER_HOOK_KEY, runnable -> {
            ExecutionContext capturedContext = this.execution.getContext();
            if (capturedContext == null
                || !capturedContext.hasProperty(MCP_CONTEXT_PROPAGATION_PROPERTY)) {
                return runnable;
            }
            return () -> {
                this.execution.pushContext(capturedContext, false);
                try {
                    runnable.run();
                } finally {
                    this.execution.popContext();
                }
            };
        });

        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

        this.transportProvider = HttpServletStatelessServerTransport.builder()
            .jsonMapper(jsonMapper)
            .build();

        McpSchema.Tool searchTool = McpSchema.Tool.builder()
            .name(SEARCH_TOOL_NAME)
            .description("Search the wiki using semantic and keyword similarity. "
                + "Returns the most relevant content chunks from indexed pages.")
            .inputSchema(buildSearchInputSchema())
            .build();

        McpSchema.Tool collectionListTool = McpSchema.Tool.builder()
            .name("list_collections")
            .description("List all collections available for searching.")
            .inputSchema(new McpSchema.JsonSchema(OBJECT, Map.of(), List.of(), null, null, null))
            .build();

        this.mcpServer = McpServer.sync(this.transportProvider)
            .serverInfo("xwiki", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .toolCall(searchTool, this::handleSearchTool)
            .toolCall(collectionListTool, this::handleCollectionListTool)
            .build();
    }

    /**
     * Handles the {@code search_wiki} MCP tool call. The current wiki must have been set in the
     * {@link XWikiContext} (via {@link XWikiContext#setWikiId}) before this method is called, so that
     * the injected {@link CollectionManager} resolves collections from the correct wiki.
     *
     * @param context the MCP transport context (unused; wiki identity comes from the XWiki thread-local context)
     * @param request the MCP tool call request containing {@code query}, optional {@code collections},
     *     optional {@code limitKeywordResults}, and optional {@code limitSemanticResults}
     * @return the search results as human-readable text, or an error result
     */
    McpSchema.CallToolResult handleSearchTool(McpTransportContext context, McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
        String query = (String) args.get(QUERY_PARAM);
        if (StringUtils.isBlank(query)) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("Error: 'query' parameter is required and must not be empty.")
                .isError(true)
                .build();
        }

        List<String> collections = getListParam(args, COLLECTIONS_PARAM);
        int keywordLimit = getIntParam(args, LIMIT_KEYWORD_PARAM, DEFAULT_LIMIT);
        int semanticLimit = getIntParam(args, LIMIT_SEMANTIC_PARAM, DEFAULT_LIMIT);

        try {
            if (collections.isEmpty()) {
                collections = this.collectionManager.getCollections();
            }
            List<Context> results =
                this.collectionManager.hybridSearch(query, collections, semanticLimit, keywordLimit);
            return McpSchema.CallToolResult.builder()
                .addTextContent(formatResults(results))
                .build();
        } catch (IndexException e) {
            this.logger.error("MCP search_wiki tool failed for query [{}]", query, e);
            return McpSchema.CallToolResult.builder()
                .addTextContent("Error searching wiki: " + ExceptionUtils.getRootCauseMessage(e))
                .isError(true)
                .build();
        }
    }

    McpSchema.CallToolResult handleCollectionListTool(McpTransportContext context, McpSchema.CallToolRequest request)
    {
        try {
            List<String> allCollections = this.collectionManager.getCollections();
            List<String> accessibleCollections =
                this.collectionManager.filterCollectionbasedOnUserAccess(allCollections);
            return McpSchema.CallToolResult.builder()
                .addTextContent(String.join("\n", accessibleCollections))
                .build();
        } catch (IndexException e) {
            this.logger.error("MCP list_collections tool failed", e);
            return McpSchema.CallToolResult.builder()
                .addTextContent("Error listing collections: " + ExceptionUtils.getRootCauseMessage(e))
                .isError(true)
                .build();
        }
    }

    /**
     * Handle an incoming request.
     *
     * @param request the incoming request
     * @param response the outgoing response
     */
    public void handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException
    {
        this.transportProvider.service(request, response);
    }

    @Override
    public void dispose()
    {
        Schedulers.resetOnScheduleHook(SCHEDULER_HOOK_KEY);
        if (this.mcpServer != null) {
            try {
                this.mcpServer.closeGracefully().block();
            } catch (Exception e) {
                this.logger.warn("Failed to close MCP server gracefully", e);
            }
        }
    }

    private McpSchema.JsonSchema buildSearchInputSchema()
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

    private List<String> getListParam(Map<String, Object> args, String key)
    {
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(v -> (String) v).toList();
        }
        return List.of();
    }

    private int getIntParam(Map<String, Object> args, String key, int defaultValue)
    {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private String formatResults(List<Context> results)
    {
        if (results.isEmpty()) {
            return "No relevant content found.";
        }
        StringBuilder sb = new StringBuilder();
        // Format the results as XML-like text.
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
