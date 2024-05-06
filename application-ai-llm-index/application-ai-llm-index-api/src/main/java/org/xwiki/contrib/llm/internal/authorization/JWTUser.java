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

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Represents a user that authorized using a token with issuer and subject.
 *
 * @param issuer the issuer of the token
 * @param subject the subject of the token
 *
 * @version $Id$
 */
@JsonAutoDetect(creatorVisibility = JsonAutoDetect.Visibility.ANY)
public record JWTUser(
    String issuer,
    String subject
)
{
}
