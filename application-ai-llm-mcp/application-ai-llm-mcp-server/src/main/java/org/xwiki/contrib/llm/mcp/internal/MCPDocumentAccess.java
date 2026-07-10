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

import org.xwiki.component.annotation.Role;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;

/**
 * The single sanctioned way for an MCP tool to turn a request reference into a usable document reference.
 *
 * <p>Resolving a reference directly (via a {@code DocumentReferenceResolver}) and acting on it bypasses the
 * per-wiki space filter and the cross-wiki reach gate: it escapes the space policy and can reach into another
 * wiki unchecked. Every document tool must instead route through {@link #resolveAndAuthorize(String, Right)},
 * which resolves the reference, applies the reach gate (a reference in another wiki is permitted only when
 * this endpoint has cross-wiki reach enabled, see {@link MCPWikiReach}), enforces the required right, and
 * applies the space filter in one step.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Role
public interface MCPDocumentAccess
{
    /**
     * Resolves the given reference and authorizes access to it for the current user: the reference is resolved
     * with the {@code current} resolver, gated by cross-wiki reach (a call operates on one wiki; a reference
     * that resolves into another wiki is permitted only when this endpoint has cross-wiki reach enabled,
     * otherwise rejected), the required {@code right} is enforced, and the per-wiki space filter is applied.
     * This is the only sanctioned path from a request reference to a usable document; resolving a reference
     * directly bypasses the reach gate and the space policy.
     *
     * @param reference the request reference, as supplied by the agent
     * @param right the right required on the document (e.g. {@link Right#VIEW} or {@link Right#EDIT})
     * @return the resolved document reference, once it has passed the reach gate, the rights check and the
     *     space filter
     * @throws MCPAccessDeniedException with an agent-facing message when the reach gate, the rights check or
     *     the space filter denies access
     */
    DocumentReference resolveAndAuthorize(String reference, Right right) throws MCPAccessDeniedException;

    /**
     * Same as {@link #resolveAndAuthorize(String, Right)}, but resolves an unqualified reference into the
     * given wiki instead of the context wiki. A reference carrying an explicit wiki prefix keeps it; when
     * that explicit wiki differs from {@code wikiContext} the call is rejected, so a tool's {@code wiki}
     * parameter and a wiki-prefixed reference cannot silently contradict each other.
     *
     * @param reference the request reference, as supplied by the agent
     * @param right the right required on the document
     * @param wikiContext the wiki an unqualified reference resolves into
     * @return the resolved document reference, once it has passed the reach gate, the rights check and the
     *     space filter
     * @throws MCPAccessDeniedException when the wikis contradict, or the reach gate, rights check or space
     *     filter denies access
     * @since 0.9.1
     */
    DocumentReference resolveAndAuthorize(String reference, Right right, WikiReference wikiContext)
        throws MCPAccessDeniedException;
}
