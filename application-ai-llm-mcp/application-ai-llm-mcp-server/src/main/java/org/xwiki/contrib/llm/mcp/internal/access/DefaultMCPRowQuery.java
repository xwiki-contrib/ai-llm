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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

/**
 * Default {@link MCPRowQuery}: composes and runs the HQL statements through the query manager with an
 * explicit target wiki and a clamped row ceiling, resolves rows with the {@code current} resolver into that
 * wiki, and authorizes each row through the endpoint's space filter and the contextual view right, in that
 * order.
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Singleton
public class DefaultMCPRowQuery implements MCPRowQuery
{
    /**
     * Explicit hidden-document clause appended to a statement base when hidden pages are excluded; matches
     * the platform's hidden-document filter predicate.
     */
    private static final String HIDDEN_DOC_CLAUSE = " and (doc.hidden <> true or doc.hidden is null)";

    /**
     * Explicit hidden-space clause appended to a statement base with a {@code space} alias when hidden pages
     * are excluded; matches the platform's hidden-space filter predicate.
     */
    private static final String HIDDEN_SPACE_CLAUSE = " and space.hidden <> true";

    /**
     * Deterministic ordering clause of the hierarchy statements; composed last, after any hidden clauses.
     */
    private static final String ORDER_BY_FULLNAME = " order by doc.fullName";

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> currentResolver;

    @Inject
    private ContextualAuthorizationManager authorization;

    @Inject
    private MCPSpaceFilter spaceFilter;

    @Override
    public List<Object[]> rows(String statement, String wiki, String bindName, Object bindValue, int limit)
        throws QueryException
    {
        // singletonMap rather than Map.of: the contract ignores bindValue when bindName is null, but a
        // caller may legitimately pass a non-null name with a null value, which Map.of would reject.
        return rows(statement, wiki,
            bindName == null ? Map.of() : Collections.singletonMap(bindName, bindValue), limit);
    }

    @Override
    public List<Object[]> rows(String statement, String wiki, Map<String, Object> bindValues, int limit)
        throws QueryException
    {
        Query query = this.queryManager.createQuery(statement, Query.HQL);
        query.setWiki(wiki);
        // Clamped on both sides: the store only applies a limit when it is strictly positive, so a
        // non-positive value would fetch an unbounded row set instead of nothing.
        query.setLimit(Math.min(Math.max(limit, 1), MAX_FETCH_PER_QUERY));
        for (Map.Entry<String, Object> bind : bindValues.entrySet()) {
            if (bind.getValue() instanceof Contains contains) {
                // The escaping query parameter API: the literal text has its %, _ and ! escaped by the
                // platform (with an ESCAPE clause appended), while the surrounding wildcards stay live.
                query.bindValue(bind.getKey()).anyChars().literal(contains.text()).anyChars().query();
            } else {
                query.bindValue(bind.getKey(), bind.getValue());
            }
        }
        return query.execute();
    }

    @Override
    public List<Object[]> hierarchyRows(String baseStatement, boolean showHidden, boolean hasSpaceAlias,
        String wiki, String bindName, Object bindValue, int limit) throws QueryException
    {
        return rows(compose(baseStatement, showHidden, hasSpaceAlias), wiki, bindName, bindValue, limit);
    }

    @Override
    public DocumentReference resolveInto(String fullName, WikiReference wiki)
    {
        return this.currentResolver.resolve(fullName, wiki);
    }

    @Override
    public boolean isAuthorized(DocumentReference reference)
    {
        return this.spaceFilter.isAllowed(reference) && this.authorization.hasAccess(Right.VIEW, reference);
    }

    @Override
    public DocumentReference authorizedDocument(String fullName, WikiReference wiki)
    {
        DocumentReference reference = resolveInto(fullName, wiki);
        return isAuthorized(reference) ? reference : null;
    }

    /**
     * Composes a final hierarchy statement: the base, the explicit hidden clauses when hidden pages are
     * excluded (the hidden-space clause only where a {@code space} alias exists), then the ordering clause
     * last.
     *
     * @param baseStatement the statement base, without an {@code order by}
     * @param showHidden whether hidden pages are included (no hidden clauses)
     * @param hasSpaceAlias whether the base has a {@code space} alias for the hidden-space clause
     * @return the composed statement
     */
    private static String compose(String baseStatement, boolean showHidden, boolean hasSpaceAlias)
    {
        StringBuilder hql = new StringBuilder(baseStatement);
        if (!showHidden) {
            hql.append(HIDDEN_DOC_CLAUSE);
            if (hasSpaceAlias) {
                hql.append(HIDDEN_SPACE_CLAUSE);
            }
        }
        return hql.append(ORDER_BY_FULLNAME).toString();
    }
}
