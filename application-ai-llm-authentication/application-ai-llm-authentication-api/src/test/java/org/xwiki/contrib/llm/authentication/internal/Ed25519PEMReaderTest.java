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

import org.junit.jupiter.api.Test;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ComponentTest
class Ed25519PEMReaderTest
{
    /**
     * A public key to use for testing.
     * Generated using the following command:
     * {@code openssl genpkey -algorithm ed25519 -outform PEM -out private.pem
     * && openssl pkey -in private.pem -pubout -outform PEM -out public.pem}
     */
    private static final String PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MCowBQYDK2VwAyEAshG1TnCjbamp/kw+hXb4il7JbPJFgfusCuyM4VW9VVk=
        -----END PUBLIC KEY-----
        """;

    private static final String PRIVATE_KEY = """
        -----BEGIN PRIVATE KEY-----
        MC4CAQAwBQYDK2VwBCIEIPArIU+xaeHcuGNQsWT5U4ixoKv3JXYhayvLlbFOLvGx
        -----END PRIVATE KEY-----
        """;

    @InjectMockComponents
    private Ed25519PEMReader reader;

    @Test
    void testReadPublicEd25519KeyFromPEM() throws Exception
    {
        var key = this.reader.readPublicEd25519KeyFromPEM(PUBLIC_KEY);
        assertNotNull(key);
        assertEquals(Curve.Ed25519, key.getCurve());

        var privateKey = this.reader.readPrivateEd25519KeyFromPEM(PRIVATE_KEY);
        assertNotNull(privateKey);
        assertEquals(Curve.Ed25519, privateKey.getCurve());
        // Verify that we derived the correct public key from the private key.
        assertArrayEquals(key.getX().decode(), privateKey.getX().decode());

        JWSSigner signer = new Ed25519Signer(privateKey);
        String payload = "XWiki is great!";

        final JWSObject jwsObject = new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(privateKey.getKeyID()).build(),
            new Payload(payload));

        jwsObject.sign(signer);

        // Verify that the signature generated with the private key can be verified with the public key.
        JWSVerifier verifier = new Ed25519Verifier(key);
        assertTrue(jwsObject.verify(verifier));
        assertEquals(payload, jwsObject.getPayload().toString());
    }
}
