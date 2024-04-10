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

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;

import javax.inject.Singleton;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.xwiki.component.annotation.Component;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;

/**
 * Read an Ed25519 public or private key from a PEM string. This is currently not supported by Nimbus JOSE + JWT.
 *
 * @see <a href="https://bitbucket.org/connect2id/nimbus-jose-jwt/issues/281/enhance-jwkparsefrompemencodedobjects-to">
 * Enhance JWK.parseFromPEMEncodedObjects to support EdDSA keys</a>
 * @version $Id$
 * @since 0.3
 */
@Component(roles = Ed25519PEMReader.class)
@Singleton
public class Ed25519PEMReader
{
    /**
     * The expected header for an Ed25519 public key.
     *
     * @see <a href="https://stackoverflow.com/a/77248795/1293930">This Stack Overflow post for an overview of the
     * magic values for Ed25519 keys</a>
     */
    private static final byte[] EXPECTED_PUBLIC_KEY_HEADER = new byte[] {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private static final byte[] EXPECTED_PRIVATE_KEY_HEADER = new byte[] {
        0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };

    /**
     * Read an Ed25519 public key from a PEM string.
     *
     * @param input the PEM string
     * @return the public key
     * @throws IOException if the PEM string is invalid
     */
    public OctetKeyPair readPublicEd25519KeyFromPEM(String input) throws IOException
    {
        byte[] publicKeyBytes = getPEMObjectBytes(input);

        if (publicKeyBytes.length != 44) {
            throw new IOException("Invalid public key length.");
        }

        // Verify that the first 12 bytes are the expected header for an Ed25519 key.
        if (!Arrays.equals(EXPECTED_PUBLIC_KEY_HEADER, Arrays.copyOf(publicKeyBytes, 12))) {
            throw new IOException("Invalid public key header.");
        }

        byte[] rawPublicKey = Arrays.copyOfRange(publicKeyBytes, 12, publicKeyBytes.length);

        return new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(rawPublicKey)).build();
    }

    /**
     * Read an Ed25519 private key from a PEM string.
     *
     * @param input the PEM string
     * @return the private key
     * @throws IOException if the PEM string is invalid
     */
    public OctetKeyPair readPrivateEd25519KeyFromPEM(String input) throws IOException
    {
        byte[] privateKeyBytes = getPEMObjectBytes(input);

        if (privateKeyBytes.length != 48) {
            throw new IOException("Invalid private key length.");
        }

        // Verify that the first 16 bytes are the expected header for an Ed25519 key.
        if (!Arrays.equals(EXPECTED_PRIVATE_KEY_HEADER, Arrays.copyOf(privateKeyBytes, 16))) {
            throw new IOException("Invalid private key header.");
        }

        byte[] d = Arrays.copyOfRange(privateKeyBytes, 16, privateKeyBytes.length);

        // Generate the public key from the private key. Code inspired from
        // https://stackoverflow.com/questions/64867752/how-derive-ed25519-eddsa-public-key-from-private-key-using-java
        var publicKey = new Ed25519PrivateKeyParameters(d).generatePublicKey();

        return new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(publicKey.getEncoded()))
            .d(Base64URL.encode(d)).build();
    }

    private static byte[] getPEMObjectBytes(String input) throws IOException
    {
        Reader pemReader = new StringReader(input);
        try (PemReader parser = new PemReader(pemReader)) {
            PemObject pemObject = parser.readPemObject();
            if (pemObject == null) {
                throw new IOException("No PEM object found.");
            }

            return pemObject.getContent();
        }
    }
}
