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

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
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
            String mode = this.configuration.getSpaceFilterMode(wikiId);
            if (isNoRestriction(mode)) {
                return true;
            }

            List<String> spaces = this.configuration.getSpaceFilterSpaces(wikiId);
            List<String> documents = this.configuration.getSpaceFilterDocuments(wikiId);
            if (spaces.isEmpty() && documents.isEmpty()) {
                return true;
            }

            boolean matches = matchesSpace(target, spaces) || matchesDocument(target, documents);
            return MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST.equals(mode) ? matches : !matches;
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP space filter for wiki [{}]; denying access: [{}]",
                wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP space filter read failure for wiki [{}]", wikiId, e);
            return false;
        }
    }

    private boolean matchesSpace(DocumentReference target, List<String> spaces)
    {
        SpaceReference targetSpace = target.getLastSpaceReference();
        for (String configured : spaces) {
            SpaceReference configuredSpace = resolveSpace(configured);
            // hasParent is strict ancestry, so equals covers a document directly in the configured space.
            // SpaceReference equality includes the wiki, so a wiki-qualified entry matches only its own wiki.
            if (configuredSpace != null
                && (targetSpace.equals(configuredSpace) || targetSpace.hasParent(configuredSpace))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDocument(DocumentReference target, List<String> documents)
    {
        for (String configured : documents) {
            DocumentReference configuredDoc = resolveDocument(configured);
            if (configuredDoc != null && target.equals(configuredDoc)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> filterQueries()
    {
        String wikiId = sourceWikiId();
        try {
            String mode = this.configuration.getSpaceFilterMode(wikiId);
            if (isNoRestriction(mode)) {
                return List.of();
            }

            List<String> spaces = this.configuration.getSpaceFilterSpaces(wikiId);
            List<String> documents = this.configuration.getSpaceFilterDocuments(wikiId);
            if (spaces.isEmpty() && documents.isEmpty()) {
                return List.of();
            }

            boolean whitelist = MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST.equals(mode);
            List<String> clauses = buildEntryClauses(spaces, documents);
            if (clauses.isEmpty()) {
                // Every configured entry was malformed. A whitelist matches nothing (deny all), a blacklist
                // excludes nothing (allow all), consistent with isAllowed skipping the malformed entries.
                return whitelist ? List.of(MATCH_NOTHING) : List.of();
            }

            String inner = String.join(OR, clauses);
            return whitelist ? List.of(inner) : List.of("-" + OPEN_PAREN + inner + CLOSE_PAREN);
        } catch (Exception e) {
            this.logger.warn("Could not build the MCP space filter queries for wiki [{}]; returning no "
                + "results: [{}]", wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP space filter query build failure for wiki [{}]", wikiId, e);
            return List.of(MATCH_NOTHING);
        }
    }

    /**
     * Builds one Solr clause per configured entry, each carrying its own wiki so entries targeting different
     * wikis coexist in a single search. A malformed entry is skipped (logged at debug), mirroring
     * {@link #isAllowed(DocumentReference)}.
     *
     * @param spaces the configured space references (wiki-qualified or unqualified)
     * @param documents the configured document references (wiki-qualified or unqualified)
     * @return the per-entry clauses, empty when every entry was malformed
     */
    private List<String> buildEntryClauses(List<String> spaces, List<String> documents)
    {
        List<String> clauses = new ArrayList<>();
        for (String configured : spaces) {
            SpaceReference space = resolveSpace(configured);
            if (space != null) {
                clauses.add(scopedClause(space.getWikiReference().getName(), FieldUtils.SPACE_PREFIX,
                    this.localSerializer.serialize(space)));
            }
        }
        for (String configured : documents) {
            DocumentReference document = resolveDocument(configured);
            if (document != null) {
                clauses.add(scopedClause(document.getWikiReference().getName(), FieldUtils.FULLNAME,
                    this.localSerializer.serialize(document)));
            }
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
            this.logger.debug("Skipping malformed MCP space filter space entry [{}]", configured, e);
            return null;
        }
    }

    private DocumentReference resolveDocument(String configured)
    {
        try {
            return this.documentResolver.resolve(configured);
        } catch (Exception e) {
            this.logger.debug("Skipping malformed MCP space filter document entry [{}]", configured, e);
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
}
