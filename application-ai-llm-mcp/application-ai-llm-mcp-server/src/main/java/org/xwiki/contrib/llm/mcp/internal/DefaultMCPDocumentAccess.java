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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;

/**
 * Default {@link MCPDocumentAccess}: resolves a request reference with the {@code current} resolver, enforces the
 * required right via {@link ContextualAuthorizationManager}, and applies the per-wiki space filter.
 *
 * <p>A reference is not strictly confined to the endpoint's own wiki: a reference in another wiki is permitted
 * whenever this endpoint has cross-wiki reach enabled (see {@link MCPWikiReach#canReachWiki}), regardless of whether
 * the target wiki has its own MCP endpoint enabled. The endpoint's own space filter and the usual rights check still
 * apply, so reaching a wiki never widens what may be read or edited there.</p>
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Singleton
public class DefaultMCPDocumentAccess implements MCPDocumentAccess
{
    private static final String QUOTE = "\"";

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @Inject
    private ContextualAuthorizationManager authorization;

    @Inject
    private MCPSpaceFilter spaceFilter;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public DocumentReference resolveAndAuthorize(String reference, Right right) throws MCPAccessDeniedException
    {
        return authorize(reference, this.referenceResolver.resolve(reference), right);
    }

    @Override
    public DocumentReference resolveAndAuthorize(String reference, Right right, WikiReference wikiContext)
        throws MCPAccessDeniedException
    {
        DocumentReference target = this.referenceResolver.resolve(reference, wikiContext);
        if (!target.getWikiReference().equals(wikiContext)) {
            throw new MCPAccessDeniedException("Reference " + QUOTE + reference + QUOTE + " is in wiki " + QUOTE
                + target.getWikiReference().getName() + QUOTE + " but the call targets wiki " + QUOTE
                + wikiContext.getName() + QUOTE + "; drop the wiki prefix or make them agree.");
        }
        return authorize(reference, target, right);
    }

    /**
     * The shared authorization pipeline of both resolve variants: the reach gate, the rights check and the
     * per-wiki space filter, in that order.
     *
     * @param reference the request reference, used in the agent-facing error messages
     * @param target the resolved document reference
     * @param right the right required on the document
     * @return {@code target}, once it has passed every check
     * @throws MCPAccessDeniedException when the reach gate, rights check or space filter denies access
     */
    private DocumentReference authorize(String reference, DocumentReference target, Right right)
        throws MCPAccessDeniedException
    {
        String targetWiki = target.getWikiReference().getName();
        String currentWiki = this.contextProvider.get().getWikiId();
        // Fail closed when the context wiki is unknown: a null current wiki is treated as a cross-wiki reference,
        // so it is denied unless reach explicitly allows it (which it will not without a context wiki).
        boolean sameWiki = currentWiki != null && currentWiki.equals(targetWiki);
        if (!sameWiki && !this.wikiReach.canReachWiki(targetWiki)) {
            throw new MCPAccessDeniedException(QUOTE + reference + QUOTE + " is in another wiki (" + QUOTE
                + targetWiki + QUOTE + "); cross-wiki reach is not enabled for this endpoint.");
        }
        if (!this.authorization.hasAccess(right, target)) {
            throw new MCPAccessDeniedException(rightsDeniedMessage(reference, right));
        }
        if (!this.spaceFilter.isAllowed(target)) {
            throw new MCPAccessDeniedException(QUOTE + reference + QUOTE
                + " is outside the content this MCP endpoint is configured to expose.");
        }
        return target;
    }

    private String rightsDeniedMessage(String reference, Right right)
    {
        String verb = Right.EDIT.equals(right) ? "edit" : "view";
        return "You do not have permission to " + verb + " " + QUOTE + reference + QUOTE + ".";
    }
}
