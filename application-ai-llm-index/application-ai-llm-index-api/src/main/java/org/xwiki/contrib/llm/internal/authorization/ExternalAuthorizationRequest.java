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
package org.xwiki.contrib.llm.internal.authorization;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Request to an external authorization API.
 *
 * @param documentIds the document ids to check
 * @param xwikiUsername the XWiki username in the form wiki:XWiki.Username
 * @param ldapUser the LDAP user if the user is an LDAP user
 * @param jwtUsers the JWT users if the user has associated one or several JWT tokens with their account
 * @param oidcUsers the OIDC users if the user has associated one or several OIDC accounts with their account
 *
 * @version $Id$
 * @since 0.3
 */
@JsonAutoDetect(creatorVisibility = JsonAutoDetect.Visibility.ANY)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExternalAuthorizationRequest(
    Set<String> documentIds,
    String xwikiUsername,
    LDAPUser ldapUser,
    List<JWTUser> jwtUsers,
    List<JWTUser> oidcUsers
)
{
}
