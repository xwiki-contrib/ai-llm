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
package org.xwiki.contrib.llm.mcp.internal.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.internal.server.MCPConfigChangeEventListener;
import org.xwiki.contrib.llm.mcp.internal.server.MCPServerConfiguration;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;

import com.xpn.xwiki.XWikiContext;

/**
 * Default {@link MCPSpaceFilter}: reads the source endpoint's space whitelist/blacklist from
 * {@link MCPServerConfiguration} and decides, per document or as Solr filter queries, what the endpoint's MCP
 * tools may reach.
 *
 * <p>The configuration is always the current (source) endpoint's own configuration; the target wiki's own filter
 * is never consulted cross-wiki. Each configured entry is resolved with the {@code current} resolver without a
 * wiki hint, so a wiki-qualified entry (e.g. {@code second:Sandbox}) resolves to its own wiki and an unqualified
 * entry to the source wiki. Reference equality already includes the wiki, so an entry only ever matches content in
 * the wiki it names.</p>
 *
 * <p>This is a content-visibility narrowing layered <em>on top of</em> the regular rights checks, never a
 * replacement: a document the filter allows must still pass the usual {@code VIEW}/{@code EDIT}
 * authorization. A legitimately empty configuration or {@code mode=none} imposes no restriction. On a
 * configuration-read error, however, the filter fails closed: {@link #isAllowed(DocumentReference)} denies the
 * document and {@link #filterQueries()} returns a match-nothing clause so the search yields no results,
 * so a transient glitch cannot silently defeat a blacklist.</p>
 *
 * <p>The parsed configuration (mode plus resolved entries) is cached per source wiki so a search checking
 * thousands of rows reads the configuration document once, not once per row. Only a successful parse is cached;
 * a read failure propagates to the fail-closed handling above and leaves no cache entry, so the next check
 * re-reads. The cache is invalidated on every cluster node by {@link MCPConfigChangeEventListener} when a wiki's
 * MCP configuration document is saved.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Singleton
public class DefaultMCPSpaceFilter implements MCPSpaceFilter
{
    private static final String OPEN_PAREN = "(";

    private static final String CLOSE_PAREN = ")";

    private static final String COLON = ":";

    private static final String AND = " AND ";

    private static final String OR = " OR ";

    /**
     * Solr filter query that matches no document. Used to fail closed: when the configuration cannot be read,
     * the search is narrowed to zero results rather than being left unrestricted.
     */
    private static final String MATCH_NOTHING = "-*:*";

    /**
     * Parsed filter state per source wiki. Holds only successfully parsed configurations; see
     * {@link #getState(String)} for the caching and invalidation contract.
     */
    private final Map<String, FilterState> cache = new ConcurrentHashMap<>();

    @Inject
    private MCPServerConfiguration configuration;

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> spaceResolver;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> documentResolver;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Override
    public boolean isAllowed(DocumentReference target)
    {
        String wikiId = sourceWikiId();
        try {
            FilterState state = getState(wikiId);
            if (state.unrestricted) {
                return true;
            }

            boolean matches = matchesSpace(target, state.spaces) || matchesDocument(target, state.documents);
            return state.whitelist ? matches : !matches;
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP space filter for wiki [{}]; denying access: [{}]",
                wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP space filter read failure for wiki [{}]", wikiId, e);
            return false;
        }
    }

    @Override
    public List<String> filterQueries()
    {
        String wikiId = sourceWikiId();
        try {
            FilterState state = getState(wikiId);
            if (state.unrestricted) {
                return List.of();
            }

            List<String> clauses = buildEntryClauses(state.spaces, state.documents);
            if (clauses.isEmpty()) {
                // Every configured entry was malformed (a resolvable entry always yields a clause). A whitelist
                // matches nothing (deny all), a blacklist excludes nothing (allow all), consistent with isAllowed
                // skipping the malformed entries.
                return state.whitelist ? List.of(MATCH_NOTHING) : List.of();
            }

            String inner = String.join(OR, clauses);
            return state.whitelist ? List.of(inner) : List.of("-" + OPEN_PAREN + inner + CLOSE_PAREN);
        } catch (Exception e) {
            this.logger.warn("Could not build the MCP space filter queries for wiki [{}]; returning no "
                + "results: [{}]", wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP space filter query build failure for wiki [{}]", wikiId, e);
            return List.of(MATCH_NOTHING);
        }
    }

    @Override
    public void invalidate(String wikiId)
    {
        this.cache.remove(wikiId);
    }

    @Override
    public void invalidateAll()
    {
        this.cache.clear();
    }

    /**
     * Returns the parsed filter state for the given source wiki, computing and caching it when absent.
     *
     * <p>{@code computeIfAbsent} runs the parse atomically per key: a parse failure propagates to the caller
     * (which fails closed) without establishing a mapping, so failures are never cached. A concurrent
     * {@link #invalidate(String)} cannot resurrect stale state either, because its removal cannot interleave
     * inside the atomic compute-and-insert: it takes effect entirely before the parse (which then reads the
     * saved configuration) or entirely after (removing the freshly inserted entry). The worst case is one
     * request served the pre-save state — the same window the uncached read-then-save race had.</p>
     *
     * @param wikiId the source wiki whose configuration governs the filter
     * @return the parsed filter state
     */
    private FilterState getState(String wikiId)
    {
        return this.cache.computeIfAbsent(wikiId, this::parseState);
    }

    private FilterState parseState(String wikiId)
    {
        String mode = this.configuration.getSpaceFilterMode(wikiId);
        if (isNoRestriction(mode)) {
            return new FilterState(true, false, List.of(), List.of());
        }

        List<String> spaces = this.configuration.getSpaceFilterSpaces(wikiId);
        List<String> documents = this.configuration.getSpaceFilterDocuments(wikiId);
        boolean unrestricted = spaces.isEmpty() && documents.isEmpty();
        boolean whitelist = MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST.equals(mode);
        return new FilterState(unrestricted, whitelist, resolveSpaces(spaces), resolveDocuments(documents));
    }

    private List<SpaceReference> resolveSpaces(List<String> configured)
    {
        List<SpaceReference> resolved = new ArrayList<>();
        for (String entry : configured) {
            SpaceReference space = resolveSpace(entry);
            if (space != null) {
                resolved.add(space);
            }
        }
        return resolved;
    }

    private List<DocumentReference> resolveDocuments(List<String> configured)
    {
        List<DocumentReference> resolved = new ArrayList<>();
        for (String entry : configured) {
            DocumentReference document = resolveDocument(entry);
            if (document != null) {
                resolved.add(document);
            }
        }
        return resolved;
    }

    private boolean matchesSpace(DocumentReference target, List<SpaceReference> spaces)
    {
        SpaceReference targetSpace = target.getLastSpaceReference();
        for (SpaceReference configuredSpace : spaces) {
            // hasParent is strict ancestry, so equals covers a document directly in the configured space.
            // SpaceReference equality includes the wiki, so a wiki-qualified entry matches only its own wiki.
            if (targetSpace.equals(configuredSpace) || targetSpace.hasParent(configuredSpace)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDocument(DocumentReference target, List<DocumentReference> documents)
    {
        for (DocumentReference configuredDoc : documents) {
            if (target.equals(configuredDoc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds one Solr clause per resolved entry, each carrying its own wiki so entries targeting different
     * wikis coexist in a single search.
     *
     * @param spaces the resolved space references (a malformed entry was already skipped at parse time)
     * @param documents the resolved document references (a malformed entry was already skipped at parse time)
     * @return the per-entry clauses, empty when every configured entry was malformed
     */
    private List<String> buildEntryClauses(List<SpaceReference> spaces, List<DocumentReference> documents)
    {
        List<String> clauses = new ArrayList<>();
        for (SpaceReference space : spaces) {
            clauses.add(scopedClause(space.getWikiReference().getName(), FieldUtils.SPACE_PREFIX,
                this.localSerializer.serialize(space)));
        }
        for (DocumentReference document : documents) {
            clauses.add(scopedClause(document.getWikiReference().getName(), FieldUtils.FULLNAME,
                this.localSerializer.serialize(document)));
        }
        return clauses;
    }

    /**
     * Builds one wiki-scoped Solr clause, e.g. {@code (wiki:second AND space_prefix:Sandbox)}, so the entry only
     * ever matches content in the wiki it names.
     *
     * @param wiki the wiki the entry targets
     * @param field the Solr field to match the local reference against
     * @param localReference the wiki-less local reference (e.g. {@code Sandbox} or {@code Sandbox.Foo})
     * @return the wiki-scoped clause
     */
    private String scopedClause(String wiki, String field, String localReference)
    {
        return OPEN_PAREN + FieldUtils.WIKI + COLON + esc(wiki) + AND + field + COLON + esc(localReference)
            + CLOSE_PAREN;
    }

    private String esc(String value)
    {
        return this.solrUtils.toCompleteFilterQueryString(value);
    }

    private SpaceReference resolveSpace(String configured)
    {
        try {
            return this.spaceResolver.resolve(configured);
        } catch (Exception e) {
            // WARN, not DEBUG: a dropped entry silently widens access relative to the admin's intent when the
            // mode is restricting, and with caching the message appears once per cache lifetime, not per check.
            this.logger.warn("Skipping malformed MCP space filter space entry [{}]: [{}]", configured,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Malformed MCP space filter space entry [{}]", configured, e);
            return null;
        }
    }

    private DocumentReference resolveDocument(String configured)
    {
        try {
            return this.documentResolver.resolve(configured);
        } catch (Exception e) {
            this.logger.warn("Skipping malformed MCP space filter document entry [{}]: [{}]", configured,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Malformed MCP space filter document entry [{}]", configured, e);
            return null;
        }
    }

    private String sourceWikiId()
    {
        return this.contextProvider.get().getWikiId();
    }

    private boolean isNoRestriction(String mode)
    {
        return MCPServerConfiguration.SPACE_FILTER_MODE_NONE.equals(mode);
    }

    /**
     * Parsed filter state for one source wiki: the interpreted mode plus the configured entries already resolved
     * into references. Immutable once built, so a cached instance is safely shared across threads.
     */
    private static final class FilterState
    {
        /** Whether the filter imposes no restriction ({@code mode=none} or both configured lists empty). */
        private final boolean unrestricted;

        /** Whether the mode is whitelist; any other restricting mode inverts the match (blacklist). */
        private final boolean whitelist;

        /** The resolvable configured space entries; malformed entries were skipped at parse time. */
        private final List<SpaceReference> spaces;

        /** The resolvable configured document entries; malformed entries were skipped at parse time. */
        private final List<DocumentReference> documents;

        FilterState(boolean unrestricted, boolean whitelist, List<SpaceReference> spaces,
            List<DocumentReference> documents)
        {
            this.unrestricted = unrestricted;
            this.whitelist = whitelist;
            this.spaces = spaces;
            this.documents = documents;
        }
    }
}
