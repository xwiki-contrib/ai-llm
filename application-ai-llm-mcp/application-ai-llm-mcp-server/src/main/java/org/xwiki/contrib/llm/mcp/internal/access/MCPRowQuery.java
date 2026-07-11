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

import java.util.List;
import java.util.Map;

import org.xwiki.component.annotation.Role;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.QueryException;

/**
 * Internal door to the authorized HQL row pipeline of the MCP navigation tools: bounded row fetching against
 * one target wiki, plus per-row resolution and authorization of the fetched document rows.
 *
 * <p>The platform's {@code document} query filter resolves rows into the CONTEXT wiki and ignores
 * {@code Query#setWiki(String)}, so this door never uses {@code Query#addFilter}: hidden-page exclusion is
 * explicit HQL (the hidden clauses composed by {@link #hierarchyRows}) and row resolution is explicit into
 * the target wiki ({@link #resolveInto}).</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Role
public interface MCPRowQuery
{
    /**
     * Absolute ceiling on the rows any single query may pull from the database, so a broad statement cannot
     * make the store materialize an unbounded row set. Every fetch limit passed to this door is clamped into
     * the {@code 1..MAX_FETCH_PER_QUERY} range: the store only applies a limit when it is strictly positive,
     * so a non-positive value would otherwise mean "unbounded", the opposite of what a caller computing a
     * degenerate limit intends.
     */
    int MAX_FETCH_PER_QUERY = 2000;

    /**
     * Marks a bind value as a substring match: the door binds it through the query parameter escaping API
     * ({@code bindValue(name).anyChars().literal(text).anyChars().query()}), so the platform escapes the
     * {@code LIKE} wildcard characters ({@code %}, {@code _}, {@code !}) inside the text and appends the
     * {@code ESCAPE} clause, while the surrounding {@code %} wildcards stay live. Callers wrap the RAW
     * user-supplied text - never pre-escaped - and pair it with a {@code like} operator in the statement.
     *
     * @param text the raw substring to match, escaped by the platform at execution time
     * @version $Id$
     * @since 0.9.1
     */
    record Contains(String text)
    {
    }

    /**
     * Runs a complete HQL statement against the given wiki, with a bounded row ceiling and an optional named
     * bind. The statement reaches the query manager verbatim: no hidden clause and no ordering is appended.
     *
     * <p>The declared {@code List<Object[]>} element type holds only for multi-column selects: a statement
     * selecting a SINGLE item yields the scalar itself as each row element at runtime (the store does not
     * wrap it, and erasure means the declared element type is never checked). A caller running a
     * single-column statement must read the elements as plain {@code Object} and dispatch on the actual
     * shape, as the {@code get_schema} instance count does.</p>
     *
     * @param statement the complete HQL statement
     * @param wiki the id of the wiki to query
     * @param bindName the name of the bind parameter, or {@code null} when the statement binds nothing
     * @param bindValue the value bound to {@code bindName}; ignored when {@code bindName} is {@code null}
     * @param limit the row ceiling, clamped into the {@code 1..}{@link #MAX_FETCH_PER_QUERY} range
     * @return the raw result rows
     * @throws QueryException if the query fails
     */
    List<Object[]> rows(String statement, String wiki, String bindName, Object bindValue, int limit)
        throws QueryException;

    /**
     * Runs a complete HQL statement against the given wiki, with a bounded row ceiling and any number of
     * named binds. The statement reaches the query manager verbatim: no hidden clause and no ordering is
     * appended. Each map entry is bound by name; an empty map binds nothing.
     *
     * <p>The single-select-item caveat of {@link #rows(String, String, String, Object, int)} applies here
     * too: a single-column statement yields scalar row elements at runtime despite the declared
     * {@code List<Object[]>} element type. A {@link Contains} value is bound through the escaping query
     * parameter API instead of as a plain value.</p>
     *
     * @param statement the complete HQL statement
     * @param wiki the id of the wiki to query
     * @param bindValues the named bind values, one entry per bind parameter; an empty map binds nothing
     * @param limit the row ceiling, clamped into the {@code 1..}{@link #MAX_FETCH_PER_QUERY} range
     * @return the raw result rows
     * @throws QueryException if the query fails
     */
    List<Object[]> rows(String statement, String wiki, Map<String, Object> bindValues, int limit)
        throws QueryException;

    /**
     * Runs a hierarchy statement base against the given wiki, composing the final statement: the base, the
     * explicit hidden clauses when hidden pages are excluded (the hidden-space clause only where the base has
     * a {@code space} alias), then a deterministic {@code order by doc.fullName} last. The hidden clauses
     * match the platform's hidden-document and hidden-space filter predicates, applied explicitly so the
     * exclusion holds regardless of the caller's profile preference.
     *
     * @param baseStatement the HQL statement base, without an {@code order by}
     * @param showHidden whether hidden pages are included (no hidden clauses are appended)
     * @param hasSpaceAlias whether the base has a {@code space} alias for the hidden-space clause
     * @param wiki the id of the wiki to query
     * @param bindName the name of the bind parameter, or {@code null} when the statement binds nothing
     * @param bindValue the value bound to {@code bindName}; ignored when {@code bindName} is {@code null}
     * @param limit the row ceiling, clamped into the {@code 1..}{@link #MAX_FETCH_PER_QUERY} range
     * @return the raw result rows
     * @throws QueryException if the query fails
     */
    List<Object[]> hierarchyRows(String baseStatement, boolean showHidden, boolean hasSpaceAlias, String wiki,
        String bindName, Object bindValue, int limit) throws QueryException;

    /**
     * Resolves a row's document full name explicitly into the target wiki, so the row is authorized and
     * rendered where it was fetched rather than in the context wiki.
     *
     * @param fullName the row's local document full name
     * @param wiki the wiki the row belongs to
     * @return the resolved document reference
     */
    DocumentReference resolveInto(String fullName, WikiReference wiki);

    /**
     * @param reference the resolved document reference to check
     * @return whether the current user may see the document: the endpoint's space filter allows it and the
     *     user holds view right on it, checked in that order
     */
    boolean isAuthorized(DocumentReference reference);

    /**
     * Composes {@link #resolveInto} and {@link #isAuthorized} for callers that only need the surviving rows;
     * a caller that also tracks denied rows (such as the survey's denied-space bookkeeping) uses the two
     * methods separately to keep the denied reference.
     *
     * @param fullName the row's local document full name
     * @param wiki the wiki the row belongs to
     * @return the resolved document reference when the current user may see it, or {@code null} when denied
     */
    DocumentReference authorizedDocument(String fullName, WikiReference wiki);
}
