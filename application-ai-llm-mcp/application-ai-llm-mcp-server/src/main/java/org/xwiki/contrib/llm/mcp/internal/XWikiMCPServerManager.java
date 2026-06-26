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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.reference.EntityReferenceSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Singleton component that manages the lifecycle of one MCP (Model Context Protocol) server per wiki.
 *
 * <p>Each wiki advertises its own server name and instructions, read from that wiki's
 * {@code AI.MCP.Code.MCPServerConfig} document. The first request for a wiki builds that wiki's server
 * lazily: it queries the component manager for all registered {@link MCPTool} implementations, filters by
 * {@link MCPTool#isEnabled()}, and registers the enabled tools with the MCP SDK.</p>
 *
 * <p>Per-wiki servers are cached in a {@link ConcurrentHashMap}. {@code computeIfAbsent} builds a wiki's
 * server once on first request; {@link #invalidate(String)} removes and closes a wiki's server so the next
 * request rebuilds it with fresh configuration. No global lock is required: each request resolves its own
 * {@link ServerHolder} and routes to its transport. Closing a removed server only marks its transport as
 * closing (rejecting new requests); in-flight requests on it complete naturally because the stateless
 * transport releases nothing they depend on.</p>
 *
 * @version $Id$
 * @since 0.8
 */
@Component(roles = XWikiMCPServerManager.class)
@Singleton
public class XWikiMCPServerManager implements Disposable
{
    /**
     * Pairs a built MCP server with the transport that serves it. One holder is cached per wiki; a request
     * routes to {@link #transport()} and {@link #invalidate(String)} closes {@link #server()}.
     *
     * @version $Id$
     */
    private record ServerHolder(McpStatelessSyncServer server, HttpServletStatelessServerTransport transport)
    {
    }

    /**
     * Usage hint appended to the initialization instructions whenever the man tool is enabled: the
     * instructions are the only text a connecting agent sees unprompted, so they must point it at the
     * tool catalog.
     */
    private static final String MAN_HINT = "Call the 'man' tool with no arguments first for a catalog of "
        + "the available tools and reference pages.";

    private static final String FALLBACK_SERVER_VERSION = "0.0.0";

    /**
     * Audit outcome for a call that did not produce a usable result (exception or {@code null}).
     */
    private static final String OUTCOME_FAILED = "failed";

    @Inject
    private Logger logger;

    /**
     * Farm-scoped component manager used to discover {@link MCPTool} implementations at rebuild time.
     * Using the plain (non-context) manager keeps all tool instances in the same classloader scope as
     * the MCP server itself, avoiding classloader-mismatch issues across extension reloads.
     * Only tools registered at farm scope or above are supported.
     */
    @Inject
    private ComponentManager componentManager;

    @Inject
    private MCPServerConfiguration mcpConfig;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private EntityReferenceSerializer<String> userSerializer;

    /** One MCP server per wiki, built lazily on first request and invalidated when that wiki's config saves. */
    private final Map<String, ServerHolder> servers = new ConcurrentHashMap<>();

    /**
     * Builds a fresh MCP server and transport for the given wiki from its configured name, description and
     * the current set of enabled {@link MCPTool} components. Pure builder: it mutates no shared state and
     * closes nothing, so it is safe to call from {@link Map#computeIfAbsent}.
     *
     * @param wikiId the wiki whose server to build
     * @return the holder pairing the new server with its transport
     */
    private ServerHolder buildServer(String wikiId)
    {
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

        HttpServletStatelessServerTransport newTransport = HttpServletStatelessServerTransport.builder()
            .jsonMapper(jsonMapper)
            .build();

        // Guard against null/blank: MCPServerConfiguration never returns null in production, but Mockito
        // mocks return null by default, and the MCP SDK rejects null or empty server names with
        // IllegalArgumentException. The guard keeps tests stable without requiring every test to stub this.
        String serverName = this.mcpConfig.getServerName(wikiId);
        if (StringUtils.isBlank(serverName)) {
            serverName = MCPServerConfiguration.DEFAULT_SERVER_NAME;
        }
        String serverDescription = this.mcpConfig.getServerDescription(wikiId);
        String serverVersion = getServerVersion();

        List<MCPTool> currentTools;
        try {
            currentTools = this.componentManager.getInstanceList(MCPTool.class);
        } catch (ComponentLookupException e) {
            this.logger.warn("Failed to look up MCPTool components; no tools will be registered: [{}]",
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to look up MCPTool components", e);
            currentTools = List.of();
        }

        McpServer.StatelessSyncSpecification builder = McpServer.sync(newTransport)
            .serverInfo(serverName, serverVersion)
            // Run tool handlers inline on the request thread instead of offloading them to Reactor's
            // boundedElastic scheduler (the SDK default, meant for non-blocking transports). The servlet
            // transport blocks the request thread waiting for the handler anyway, and running inline keeps
            // XWiki's thread-locals (ExecutionContext/XWikiContext) available to tools without any
            // cross-thread context propagation.
            .immediateExecution(true)
            // The declarative parameter layer in MCPToolSupport is the validation gate: it produces the
            // curated agent-facing error messages. SDK-side input validation would reject calls before
            // the tool runs, with generic SDK text instead.
            .validateToolInputs(false)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());

        List<String> registeredNames = new ArrayList<>();
        for (MCPTool tool : currentTools) {
            registerTool(builder, tool, registeredNames);
        }

        // The UI calls this "server description"; the MCP SDK exposes it as initialization instructions.
        // Built from the names that actually registered, so the man hint is only sent when man is served.
        builder.instructions(buildInstructions(serverDescription, registeredNames));

        return new ServerHolder(builder.build(), newTransport);
    }

    /**
     * Invalidates the cached MCP server for the given wiki so its name, description and instructions are
     * re-read from configuration on the next connection. The removed server is closed: {@code
     * closeGracefully()} only marks its transport as closing (rejecting new requests); in-flight requests
     * on the removed holder complete naturally since the stateless transport releases nothing they depend on.
     *
     * @param wikiId the wiki whose cached server to drop
     */
    public void invalidate(String wikiId)
    {
        ServerHolder removed = this.servers.remove(wikiId);
        if (removed != null) {
            closeQuietly(removed.server());
        }
    }

    /**
     * Closes one MCP server gracefully, logging at WARN (with a DEBUG stack trace) instead of propagating
     * if the close fails.
     *
     * @param server the server to close
     */
    private void closeQuietly(McpStatelessSyncServer server)
    {
        try {
            server.closeGracefully();
        } catch (Exception e) {
            this.logger.warn("Failed to close MCP server gracefully: {}", ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to close MCP server gracefully", e);
        }
    }

    /**
     * Executes a tool call through the manager's middleware: every registered tool handler goes through
     * this single seam, so per-call cross-cutting concerns are implemented once here rather than in each
     * tool.
     *
     * <p>It currently does two things. It normalizes a misbehaving tool — an escaping
     * {@link RuntimeException}, a {@link LinkageError} (realistic for a tool whose extension was
     * reloaded), or a {@code null} result — into a regular MCP error result with a generic message;
     * without this, the failure would surface as a transport-level HTTP 500 carrying the raw exception
     * message (or a bare {@code null} body), leaking internals and bypassing the {@code isError}
     * convention agents know how to recover from (other {@link Error}s, e.g. out-of-memory, are
     * deliberately left to propagate). And it emits one INFO audit line per call (tool, acting user,
     * outcome, duration — never the arguments, which may carry document content), giving administrators
     * a trail of agent actions. The audit's user resolution reads the request thread's XWiki context, so
     * it relies on tool handlers running inline on that thread ({@code immediateExecution(true)}
     * below).</p>
     *
     * @param toolName the registered tool name, captured at registration time
     * @param tool the tool implementation
     * @param request the tool call request
     * @return the tool result, or a normalized error result if the tool misbehaved
     */
    private McpSchema.CallToolResult executeWrapped(String toolName, MCPTool tool,
        McpSchema.CallToolRequest request)
    {
        long startNanos = System.nanoTime();
        try {
            McpSchema.CallToolResult result = tool.execute(request);
            if (result == null) {
                this.logger.error("MCP tool [{}] returned a null result", toolName);
                audit(toolName, OUTCOME_FAILED, startNanos);
                return internalError(toolName);
            }
            audit(toolName, Boolean.TRUE.equals(result.isError()) ? "error" : "ok", startNanos);
            return result;
        } catch (RuntimeException | LinkageError e) {
            this.logger.error("MCP tool [{}] failed with an unexpected error", toolName, e);
            audit(toolName, OUTCOME_FAILED, startNanos);
            return internalError(toolName);
        }
    }

    private McpSchema.CallToolResult internalError(String toolName)
    {
        return McpSchema.CallToolResult.builder()
            .addTextContent("Internal error in tool '" + toolName
                + "'. Try again, and report it to the wiki administrator if it persists.")
            .isError(true)
            .build();
    }

    /**
     * Emits the per-call audit line at INFO: the tool, the acting user, the outcome ({@code ok} for a
     * normal result, {@code error} for a result the tool itself flagged as an error, {@code failed} for
     * an unexpected exception) and the call duration.
     *
     * @param toolName the tool name
     * @param outcome the call outcome
     * @param startNanos the {@link System#nanoTime()} timestamp taken before the call
     */
    private void audit(String toolName, String outcome, long startNanos)
    {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        this.logger.info("MCP tool call: tool=[{}] user=[{}] outcome=[{}] duration=[{}ms]",
            toolName, currentUser(), outcome, durationMs);
    }

    /**
     * Resolves the acting user for the audit line: the serialized user reference, {@code "guest"} for an
     * unauthenticated caller (reachable when no OIDC provider gates the endpoint), or {@code "unknown"}
     * when the user cannot be resolved at all.
     *
     * @return the audit representation of the acting user
     */
    private String currentUser()
    {
        try {
            var user = this.documentAccessBridge.getCurrentUserReference();
            return user != null ? this.userSerializer.serialize(user) : "guest";
        } catch (Exception e) {
            // No context on this thread - cannot happen on the request thread, but the audit line must
            // never be the thing that breaks a tool call.
            return "unknown";
        }
    }

    /**
     * Registers one tool on the server builder, isolating the rest of the rebuild from a misbehaving
     * tool: if the tool throws from {@code isEnabled()} or {@code getToolDefinition()}, advertises a
     * malformed schema, or the SDK rejects the registration (e.g. a duplicate tool name from another
     * contribution), the tool is skipped with a warning instead of failing the whole server build.
     *
     * <p>The schemas are meta-validated here, per tool, with the same validator the SDK uses: the SDK
     * runs that validation for all registered tools at {@code build()}, where one malformed
     * third-party schema fails the whole server build. Validating before {@code toolCall(...)} keeps
     * the fault isolated to the offending tool.</p>
     *
     * @param builder the server builder
     * @param tool the tool to register
     * @param registeredNames collector for the names that actually registered, in registration order
     */
    private void registerTool(McpServer.StatelessSyncSpecification builder, MCPTool tool,
        List<String> registeredNames)
    {
        try {
            if (!tool.isEnabled()) {
                return;
            }
            McpSchema.Tool definition = tool.getToolDefinition();
            JsonSchemaValidator validator = McpJsonDefaults.getSchemaValidator();
            String schemaContext = "Tool '" + definition.name() + "'";
            validator.assertConforms(schemaContext + " inputSchema", definition.inputSchema());
            validator.assertConforms(schemaContext + " outputSchema", definition.outputSchema());
            builder.toolCall(definition, (ctx, req) -> executeWrapped(definition.name(), tool, req));
            registeredNames.add(definition.name());
        } catch (RuntimeException e) {
            this.logger.warn("Skipping MCP tool [{}]: [{}]", tool.getClass().getName(),
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP tool registration failure", e);
        }
    }

    /**
     * Builds the initialization instructions sent to a connecting client: the configured server
     * description, with the {@code man} usage hint appended whenever the man tool is registered (the
     * instructions are the only guidance an agent receives without calling a tool).
     *
     * @param serverDescription the configured description, possibly blank
     * @param registeredToolNames the names of the tools that actually registered
     * @return the instructions text
     */
    private String buildInstructions(String serverDescription, List<String> registeredToolNames)
    {
        if (!registeredToolNames.contains(MCPManTool.TOOL_ID)) {
            return serverDescription;
        }
        if (StringUtils.isBlank(serverDescription)) {
            return MAN_HINT;
        }
        return serverDescription + "\n\n" + MAN_HINT;
    }

    private String getServerVersion()
    {
        // Primary source: MANIFEST.MF Implementation-Version set by the maven-jar-plugin.
        Package packageMetadata = XWikiMCPServerManager.class.getPackage();
        if (packageMetadata != null && StringUtils.isNotBlank(packageMetadata.getImplementationVersion())) {
            return packageMetadata.getImplementationVersion().trim();
        }

        return FALLBACK_SERVER_VERSION;
    }

    /**
     * Handle an incoming request by routing it to the given wiki's MCP server, building and caching that
     * server lazily on the first request for the wiki.
     *
     * @param wikiId the wiki whose server should serve the request
     * @param request the incoming request
     * @param response the outgoing response
     * @throws ServletException if the transport throws
     * @throws IOException if the transport throws
     */
    public void handleRequest(String wikiId, HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        ServerHolder holder = this.servers.computeIfAbsent(wikiId, this::buildServer);
        holder.transport().service(request, response);
    }

    @Override
    public void dispose()
    {
        for (ServerHolder holder : this.servers.values()) {
            closeQuietly(holder.server());
        }
        this.servers.clear();
    }
}
