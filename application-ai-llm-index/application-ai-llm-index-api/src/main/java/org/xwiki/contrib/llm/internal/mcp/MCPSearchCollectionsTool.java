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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStore;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.SecurityConfiguration;

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

    private static final String LOCALE_PARAM = "locale";

    private static final String LIMIT_KEYWORD_PARAM = "limitKeywordResults";

    private static final String LIMIT_SEMANTIC_PARAM = "limitSemanticResults";

    /**
     * Shared prefix of the limit-validation error messages, naming both limit parameters.
     */
    private static final String LIMITS_PREFIX =
        MCPToolSupport.ERROR_PREFIX + LIMIT_KEYWORD_PARAM + "'/'" + LIMIT_SEMANTIC_PARAM + "' must be ";

    private static final String INVALID_LIMITS_MESSAGE = LIMITS_PREFIX + "greater than or equal to 0.";

    /**
     * The declared parameter set: it generates the advertised input schema and coerces the arguments,
     * so both cannot disagree.
     */
    private static final MCPToolSupport PARAMS = MCPToolSupport.builder()
        .requiredString(QUERY_PARAM, "The text to search for")
        .stringArray(COLLECTIONS_PARAM,
            "Optional list of collection IDs to search in. Omit to search all accessible collections.")
        .string(LOCALE_PARAM, "Optional content language filter, e.g. " + MCPToolSupport.LOCALE_FORMS
            + ". Exact match on the stored language of each chunk: \"fr\" does not match \"fr_BR\" chunks; use "
            + "the underscore form for regional variants. Documents uploaded through the REST API carry "
            + "uploader-typed language metadata, so junk values simply never match.")
        .integer(LIMIT_KEYWORD_PARAM, "Maximum number of keyword search results (default: %d)".formatted(
            DEFAULT_LIMIT))
        .integer(LIMIT_SEMANTIC_PARAM, "Maximum number of semantic similarity results (default: %d)".formatted(
            DEFAULT_LIMIT))
        .build();

    @Inject
    private Logger logger;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    private SecurityConfiguration securityConfiguration;

    @Inject
    @Named("withparameters")
    private EntityReferenceResolver<String> withParametersReferenceResolver;

    @Inject
    @Named("withparameters")
    private EntityReferenceSerializer<String> withParametersReferenceSerializer;

    @Inject
    private EntityReferenceSerializer<String> defaultReferenceSerializer;

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.inputSchema())
            .description("Search indexed collections using semantic and keyword similarity. "
                + "Returns the most relevant content chunks from indexed pages. Each result carries the "
                + "index-internal <documentId>; results coming from wiki pages also carry <reference> (the "
                + "locale-free page reference to pass to get_document's 'reference' parameter) and <language> "
                + "(the language of that content; pass it as get_document's 'locale' parameter to read "
                + "that translation).")
            .build();
    }

    @Override
    public String getCategory()
    {
        return "Semantic Search";
    }

    @Override
    public String getSummary()
    {
        return "Semantic + keyword search over indexed collection content.";
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            String query = PARAMS.requireString(args, QUERY_PARAM);

            int keywordLimit = PARAMS.integer(args, LIMIT_KEYWORD_PARAM, DEFAULT_LIMIT);
            int semanticLimit = PARAMS.integer(args, LIMIT_SEMANTIC_PARAM, DEFAULT_LIMIT);

            validateLimits(keywordLimit, semanticLimit);

            Locale locale = MCPToolSupport.parseLocale(PARAMS.string(args, LOCALE_PARAM), LOCALE_PARAM);

            List<String> collections = PARAMS.stringList(args, COLLECTIONS_PARAM);
            if (CollectionUtils.isEmpty(collections)) {
                collections = this.collectionManager.getCollections();
            }

            List<Context> results =
                this.collectionManager.hybridSearch(query, collections, semanticLimit, keywordLimit, locale);
            return MCPToolSupport.result(formatResults(results));
        } catch (IndexException e) {
            // Keep the root cause in the logs, off the wire.
            this.logger.warn("MCP search_collections tool failed for query [{}]: [{}]", args.get(QUERY_PARAM),
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP search_collections tool failure details for query [{}]",
                args.get(QUERY_PARAM), e);
            return MCPToolSupport.errorResult("Failed to search collections. Try again; if it persists, report it "
                + "to a wiki administrator (details are in the server logs).");
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }
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
        return LIMITS_PREFIX + "less than or equal to " + this.securityConfiguration.getQueryItemsLimit() + ".";
    }

    private String formatResults(List<Context> results)
    {
        if (results.isEmpty()) {
            return "No relevant content found.";
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Boolean> wikiStoreCache = new HashMap<>();
        for (Context ctx : results) {
            sb.append("<result>\n");
            String url = sanitizeElementText(ctx.url());
            if (StringUtils.isNotBlank(url)) {
                sb.append("<url>").append(url).append("</url>\n");
            }
            if (ctx.documentId() != null) {
                sb.append("<documentId>").append(ctx.documentId()).append("</documentId>\n");
                if (wikiStoreCache.computeIfAbsent(ctx.collectionId(), this::isWikiStoreCollection)) {
                    appendReference(sb, ctx.documentId());
                }
            }
            String language = sanitizeElementText(ctx.language());
            if (StringUtils.isNotBlank(language)) {
                sb.append("<language>").append(language).append("</language>\n");
            }
            if (ctx.content() != null) {
                sb.append("<content>\n").append(ctx.content()).append("\n</content>\n");
            }
            sb.append("</result>\n");
        }
        return sb.toString().trim();
    }

    /**
     * Tells whether the given collection stores its documents in the XWiki document store. Only ids coming from
     * that store are trusted as wiki document references: internal-store (REST-uploaded) documents have
     * client-chosen string ids, so a reference-shaped id there could falsely attribute uploaded content to a real
     * wiki page. A collection that cannot be resolved is treated as not wiki-store (no reference is emitted).
     *
     * @param collectionId the id of the collection to resolve
     * @return {@code true} when the collection uses the XWiki document store
     */
    private Boolean isWikiStoreCollection(String collectionId)
    {
        if (StringUtils.isBlank(collectionId)) {
            return false;
        }
        try {
            Collection collection = this.collectionManager.getCollection(collectionId);
            return collection != null && XWikiDocumentStore.NAME.equals(collection.getDocumentStoreHint());
        } catch (IndexException e) {
            this.logger.debug("Failed to resolve collection [{}] while formatting search results, emitting no"
                + " <reference> for its results", collectionId, e);
            return false;
        }
    }

    /**
     * Strips line breaks and angle brackets from a value emitted as element text. Both callers (the language and
     * the url emission) handle uploader-controlled metadata: internal-store documents carry free-text language and
     * url values, which must neither break the line grammar of the results nor forge elements such as the
     * store-provenance-guarded {@code <reference>}.
     *
     * @param value the raw value, may be {@code null}
     * @return the sanitized value, {@code null} when the input is {@code null}
     */
    private String sanitizeElementText(String value)
    {
        return StringUtils.replaceChars(value, "\r\n<>", null);
    }

    /**
     * Appends a locale-free {@code <reference>} element derived from the given document id, but only when the
     * document id round-trips as a well-formed XWiki document reference. The caller only invokes this for results
     * whose collection uses the XWiki document store, so the id is known to come from the wiki index rather than
     * from an uploader.
     *
     * @param sb the output being built
     * @param documentId the index-internal document id
     */
    private void appendReference(StringBuilder sb, String documentId)
    {
        try {
            DocumentReference documentReference = new DocumentReference(
                this.withParametersReferenceResolver.resolve(documentId, EntityType.DOCUMENT));
            if (documentId.equals(this.withParametersReferenceSerializer.serialize(documentReference))) {
                sb.append("<reference>").append(this.defaultReferenceSerializer.serialize(documentReference))
                    .append("</reference>\n");
            }
        } catch (IllegalArgumentException e) {
            // The document id does not parse as an XWiki reference (e.g. its locale part is not a valid
            // locale), so the result keeps only its index-internal <documentId>.
        }
    }
}
