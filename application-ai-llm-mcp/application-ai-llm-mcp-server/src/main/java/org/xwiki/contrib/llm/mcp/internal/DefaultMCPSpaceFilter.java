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
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;

/**
 * Default {@link MCPSpaceFilter}: reads the per-wiki space whitelist/blacklist from
 * {@link MCPServerConfiguration} and decides, per document or as Solr filter queries, what the wiki's MCP
 * tools may reach.
 *
 * <p>This is a content-visibility narrowing layered <em>on top of</em> the regular rights checks, never a
 * replacement: a document the filter allows must still pass the usual {@code VIEW}/{@code EDIT}
 * authorization. A legitimately empty configuration or {@code mode=none} imposes no restriction. On a
 * configuration-read error, however, the filter fails closed: {@link #isAllowed(DocumentReference)} denies the
 * document and {@link #filterQueries(String)} returns a match-nothing clause so the search yields no results,
 * so a transient glitch cannot silently defeat a blacklist.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Singleton
public class DefaultMCPSpaceFilter implements MCPSpaceFilter
{
    private static final String CLAUSE_OPEN = ":(";

    private static final String CLAUSE_CLOSE = ")";

    private static final String SPACE_CLAUSE_START = FieldUtils.SPACE_PREFIX + CLAUSE_OPEN;

    private static final String DOC_CLAUSE_START = FieldUtils.FULLNAME + CLAUSE_OPEN;

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
    private SolrUtils solrUtils;

    @Inject
    private Logger logger;

    @Override
    public boolean isAllowed(DocumentReference target)
    {
        String wikiId = target.getWikiReference().getName();
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
            try {
                SpaceReference configuredSpace = this.spaceResolver.resolve(configured);
                // hasParent is strict ancestry, so equals covers a document directly in the configured space.
                if (targetSpace.equals(configuredSpace) || targetSpace.hasParent(configuredSpace)) {
                    return true;
                }
            } catch (Exception e) {
                this.logger.debug("Skipping malformed MCP space filter space entry [{}]", configured, e);
            }
        }
        return false;
    }

    private boolean matchesDocument(DocumentReference target, List<String> documents)
    {
        for (String configured : documents) {
            try {
                if (target.equals(this.documentResolver.resolve(configured))) {
                    return true;
                }
            } catch (Exception e) {
                this.logger.debug("Skipping malformed MCP space filter document entry [{}]", configured, e);
            }
        }
        return false;
    }

    @Override
    public List<String> filterQueries(String wikiId)
    {
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

            String inner = buildInnerClause(spaces, documents);
            if (MCPServerConfiguration.SPACE_FILTER_MODE_WHITELIST.equals(mode)) {
                return List.of(inner);
            }
            return List.of("-(" + inner + CLAUSE_CLOSE);
        } catch (Exception e) {
            this.logger.warn("Could not build the MCP space filter queries for wiki [{}]; returning no "
                + "results: [{}]", wikiId, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP space filter query build failure for wiki [{}]", wikiId, e);
            return List.of(MATCH_NOTHING);
        }
    }

    /**
     * Builds the inner clause matching the configured spaces and documents, e.g.
     * {@code space_prefix:(A.B OR C.D) OR fullname:(E.F)}. When both a space part and a document part are
     * present the two are wrapped in parentheses so the surrounding mode (whitelist plain, blacklist negated)
     * applies to the whole disjunction.
     *
     * @param spaces the configured local space references
     * @param documents the configured local document references
     * @return the inner Solr clause
     */
    private String buildInnerClause(List<String> spaces, List<String> documents)
    {
        String spacePart = spaces.isEmpty() ? null : SPACE_CLAUSE_START + joinEscaped(spaces) + CLAUSE_CLOSE;
        String docPart = documents.isEmpty() ? null : DOC_CLAUSE_START + joinEscaped(documents) + CLAUSE_CLOSE;
        if (spacePart != null && docPart != null) {
            return "(" + spacePart + OR + docPart + CLAUSE_CLOSE;
        }
        return spacePart != null ? spacePart : docPart;
    }

    private String joinEscaped(List<String> values)
    {
        List<String> escaped = new ArrayList<>(values.size());
        for (String value : values) {
            escaped.add(this.solrUtils.toCompleteFilterQueryString(value));
        }
        return String.join(OR, escaped);
    }

    private boolean isNoRestriction(String mode)
    {
        return MCPServerConfiguration.SPACE_FILTER_MODE_NONE.equals(mode);
    }
}
