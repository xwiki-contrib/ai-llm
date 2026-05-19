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
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.contrib.llm.mcp.MCPTool;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.scheduler.Schedulers;

/**
 * Singleton component that manages the lifecycle of the MCP (Model Context Protocol) server.
 *
 * <p>At initialisation it queries the component manager for all registered {@link MCPTool} implementations,
 * filters by {@link MCPTool#isEnabled()}, and registers the enabled tools with the MCP SDK. The server can
 * be rebuilt at any time (e.g. after a configuration change) by calling {@link #rebuildServer()}: in-flight
 * requests are protected by a read-write lock so no request is dropped during the swap.</p>
 *
 * <p>The {@link HttpServletStatelessServerTransport} is recreated on every rebuild. The JAX-RS resource
 * ({@link DefaultMCPResource}) always delegates to the current transport reference, which is swapped
 * atomically under the write lock.</p>
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

    private static final String SCHEDULER_HOOK_KEY = "xwiki-execution-propagation";

    private static final String POM_PROPERTIES_RESOURCE =
        "META-INF/maven/org.xwiki.contrib.llm/application-ai-llm-mcp-server/pom.properties";

    private static final String VERSION_PROPERTY = "version";

    private static final String FALLBACK_SERVER_VERSION = "0.0.0";

    /**
     * Guards {@link #mcpServer} and {@link #transportProvider}.
     * Read lock: held for the duration of each {@link #handleRequest} call.
     * Write lock: held only for the brief reference swap inside {@link #rebuildServer()}.
     */
    private final ReentrantReadWriteLock serverLock = new ReentrantReadWriteLock();

    @Inject
    private Logger logger;

    /**
     * Context-aware component manager used to discover {@link MCPTool} implementations at rebuild time.
     * Using the context manager (rather than a fixed injected list) means that tools contributed by
     * dynamically installed extensions are picked up on the next {@link #rebuildServer()} call.
     */
    @Inject
    @Named("context")
    private ComponentManager componentManager;

    @Inject
    private MCPServerConfiguration mcpConfig;

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

        rebuildServer();
    }

    /**
     * Rebuilds the MCP server from the current set of enabled {@link MCPTool} components.
     *
     * <p>A new {@link HttpServletStatelessServerTransport} and {@link McpStatelessSyncServer} are created,
     * then swapped in atomically under the write lock. The old server is closed <em>after</em> the lock is
     * released so that the write lock is held only for the brief reference swap, not for the potentially
     * blocking {@code closeGracefully()} call.</p>
     *
     * <p>This method is called from {@link #initialize()} and by {@link MCPConfigChangeEventListener} whenever
     * the {@code AI.MCP.Code.MCPServerConfig} document is saved.</p>
     */
    public void rebuildServer()
    {
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

        HttpServletStatelessServerTransport newTransport = HttpServletStatelessServerTransport.builder()
            .jsonMapper(jsonMapper)
            .build();

        // Guard against null/blank: MCPServerConfiguration never returns null in production, but Mockito
        // mocks return null by default, and the MCP SDK rejects null or empty server names with
        // IllegalArgumentException. The guard keeps tests stable without requiring every test to stub this.
        String serverName = this.mcpConfig.getServerName();
        if (serverName == null || serverName.isBlank()) {
            serverName = MCPServerConfiguration.DEFAULT_SERVER_NAME;
        }
        String serverDescription = this.mcpConfig.getServerDescription();
        String serverVersion = getServerVersion();
        McpServer.StatelessSyncSpecification builder = McpServer.sync(newTransport)
            .serverInfo(serverName, serverVersion)
            // The UI calls this "server description"; the MCP SDK exposes it as initialization instructions.
            .instructions(serverDescription)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());

        List<MCPTool> currentTools;
        try {
            currentTools = this.componentManager.getInstanceList(MCPTool.class);
        } catch (ComponentLookupException e) {
            this.logger.warn("Failed to look up MCPTool components; no tools will be registered", e);
            currentTools = List.of();
        }
        for (MCPTool tool : currentTools) {
            if (tool.isEnabled()) {
                builder = builder.toolCall(tool.getToolDefinition(), tool::execute);
            }
        }

        McpStatelessSyncServer newServer = builder.build();

        // Swap references under write lock (brief critical section — no I/O here).
        McpStatelessSyncServer oldServer;
        this.serverLock.writeLock().lock();
        try {
            oldServer = this.mcpServer;
            this.mcpServer = newServer;
            this.transportProvider = newTransport;
        } finally {
            this.serverLock.writeLock().unlock();
        }

        // Close the old server outside the lock to avoid blocking incoming requests.
        if (oldServer != null) {
            try {
                oldServer.closeGracefully().block();
            } catch (Exception e) {
                this.logger.warn("Failed to close MCP server gracefully during rebuild", e);
            }
        }
    }

    private String getServerVersion()
    {
        Package packageMetadata = XWikiMCPServerManager.class.getPackage();
        if (packageMetadata != null && packageMetadata.getImplementationVersion() != null
            && !packageMetadata.getImplementationVersion().trim().isEmpty()) {
            return packageMetadata.getImplementationVersion().trim();
        }

        try (InputStream inputStream =
            XWikiMCPServerManager.class.getClassLoader().getResourceAsStream(POM_PROPERTIES_RESOURCE)) {
            if (inputStream != null) {
                Properties properties = new Properties();
                properties.load(inputStream);
                String version = properties.getProperty(VERSION_PROPERTY);
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (IOException e) {
            this.logger.warn("Failed to read MCP server version from [{}], using fallback version",
                POM_PROPERTIES_RESOURCE, e);
        }

        return FALLBACK_SERVER_VERSION;
    }

    /**
     * Handle an incoming request. Acquires the read lock so that a concurrent {@link #rebuildServer()} call
     * waits until all in-flight requests have completed before swapping the server reference.
     *
     * @param request the incoming request
     * @param response the outgoing response
     * @throws ServletException if the transport throws
     * @throws IOException if the transport throws
     */
    public void handleRequest(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        this.serverLock.readLock().lock();
        try {
            this.transportProvider.service(request, response);
        } finally {
            this.serverLock.readLock().unlock();
        }
    }

    @Override
    public void dispose()
    {
        Schedulers.resetOnScheduleHook(SCHEDULER_HOOK_KEY);
        McpStatelessSyncServer serverToClose;
        this.serverLock.writeLock().lock();
        try {
            serverToClose = this.mcpServer;
            this.mcpServer = null;
        } finally {
            this.serverLock.writeLock().unlock();
        }
        if (serverToClose != null) {
            try {
                serverToClose.closeGracefully().block();
            } catch (Exception e) {
                this.logger.warn("Failed to close MCP server gracefully", e);
            }
        }
    }
}
