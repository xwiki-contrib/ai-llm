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
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;

/**
 * Default {@link MCPDocumentAccess}: resolves a request reference with the {@code current} resolver, confines
 * access to the endpoint's own wiki, enforces the required right via {@link ContextualAuthorizationManager},
 * and applies the per-wiki space filter.
 *
 * @version $Id$
 * @since 0.9
 */
@Component
@Singleton
public class DefaultMCPDocumentAccess implements MCPDocumentAccess
{
    private static final String OPEN_BRACKET = "[";

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @Inject
    private ContextualAuthorizationManager authorization;

    @Inject
    private MCPSpaceFilter spaceFilter;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public DocumentReference resolveAndAuthorize(String reference, Right right) throws MCPAccessDeniedException
    {
        DocumentReference target = this.referenceResolver.resolve(reference);
        String currentWiki = this.contextProvider.get().getWikiId();
        if (currentWiki != null && !currentWiki.equals(target.getWikiReference().getName())) {
            throw new MCPAccessDeniedException(OPEN_BRACKET + reference
                + "] is in another wiki; this MCP endpoint only serves the [" + currentWiki + "] wiki.");
        }
        if (!this.authorization.hasAccess(right, target)) {
            throw new MCPAccessDeniedException(rightsDeniedMessage(reference, right));
        }
        if (!this.spaceFilter.isAllowed(target)) {
            throw new MCPAccessDeniedException(OPEN_BRACKET + reference
                + "] is outside the spaces this MCP server is configured to expose.");
        }
        return target;
    }

    private String rightsDeniedMessage(String reference, Right right)
    {
        String verb = Right.EDIT.equals(right) ? "edit" : "view";
        return "You do not have permission to " + verb + " " + OPEN_BRACKET + reference + "].";
    }
}
