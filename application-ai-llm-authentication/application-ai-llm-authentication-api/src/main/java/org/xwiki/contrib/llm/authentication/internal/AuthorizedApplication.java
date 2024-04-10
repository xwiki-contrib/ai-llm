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
package org.xwiki.contrib.llm.authentication.internal;

import org.xwiki.model.reference.DocumentReference;

import com.nimbusds.jose.jwk.OctetKeyPair;

/**
 * Represents an application authorized to generate JWT tokens.
 *
 * @param documentReference the reference to the document that represents the application
 * @param issuer the expected issuer of the token
 * @param name the display name of the application
 * @param groupFormat the format to use for the groups in the token
 * @param keyPair the public key to use to verify the signature of the token
 *
 * @version $Id$
 */
public record AuthorizedApplication(
    DocumentReference documentReference,
    String issuer,
    String name,
    String groupFormat,
    OctetKeyPair keyPair
)
{
}
