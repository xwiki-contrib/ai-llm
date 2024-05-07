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

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;

import com.nimbusds.jwt.JWTClaimsSet;
import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;

/**
 * Helper component to valid JWT token claims.
 *
 * @version $Id$
 */
@Component(roles = ClaimValidator.class)
@Singleton
public class ClaimValidator
{
    /**
     * Validate the given claims against the given context.
     *
     * @param claims the claims to validate
     * @param context the XWiki context to get the request URL to compare against
     * @throws XWikiException when the validation fails
     */
    public void validateClaims(JWTClaimsSet claims, XWikiContext context) throws XWikiException
    {
        validateTimes(claims);

        validateAudience(context, claims);
    }

    static void validateAudience(XWikiContext context, JWTClaimsSet claims) throws XWikiException
    {
        URL requestURL = XWiki.getRequestURL(context.getRequest());
        // Remove path and query parameters from the URL
        String rootURL;
        try {
            rootURL = new URL(requestURL, "/").toString();
        } catch (MalformedURLException e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                "Failed parsing the request URL to validate the audience.");
        }

        if (claims.getAudience().stream().noneMatch(rootURL::equals)) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                "The wiki's URL [%s] is not in the provided audience".formatted(rootURL));
        }
    }

    static void validateTimes(JWTClaimsSet claims) throws XWikiException
    {
        Date expirationTime = claims.getExpirationTime();
        if (expirationTime == null) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                "No expiration time specified.");
        }

        // Get an instant from a date as Instant.now() doesn't work as expected in tests.
        Instant now = (new Date()).toInstant();
        if (now.isAfter(expirationTime.toInstant())) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                "Token expired.");
        }

        // Verify that the token isn't valid for more than 25 hours (24 hours plus an hour of slack).
        Duration maximumAge = Duration.ofHours(25);
        if (now.plus(maximumAge).isBefore(expirationTime.toInstant())) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                "The token must not be valid for more than 24 hours.");
        }

        if (claims.getIssueTime() != null) {
            // Check that the token hasn't been issued more than 30 seconds in the future
            if (now.plusSeconds(30).isBefore(claims.getIssueTime().toInstant())) {
                throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                    "Token issued in the future.");
            }

            // The token isn't older than 25 hours (24 hours plus some slack)
            if (now.minus(maximumAge).isAfter(claims.getIssueTime().toInstant())) {
                throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                    "The token is more than 24 hours old.");
            }
        }

        if (claims.getNotBeforeTime() != null && now.plusSeconds(30).isBefore(claims.getNotBeforeTime().toInstant())) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP, XWikiException.ERROR_XWIKI_ACCESS_DENIED,
                "Token isn't valid yet.");
        }
    }
}
