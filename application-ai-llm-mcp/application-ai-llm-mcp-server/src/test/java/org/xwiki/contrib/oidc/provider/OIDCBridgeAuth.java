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
package org.xwiki.contrib.oidc.provider;

import org.xwiki.contrib.oidc.token.TokenAuthService;

/**
 * Test stand-in sharing the fully qualified name of the deprecated name of the OIDC Provider's token bridge
 * authenticator, which extends {@code TokenAuthService} exactly as the real class does, so that the superclass
 * walk of {@code OIDCProviderStatus} (LLMAI-201) can be exercised without a dependency on the OIDC jars. If a
 * test-scope dependency ever brings the real {@code token-authenticator} jar onto the classpath, this stand-in
 * would shadow it silently; remove it then.
 *
 * @version $Id$
 */
public class OIDCBridgeAuth extends TokenAuthService
{
}
