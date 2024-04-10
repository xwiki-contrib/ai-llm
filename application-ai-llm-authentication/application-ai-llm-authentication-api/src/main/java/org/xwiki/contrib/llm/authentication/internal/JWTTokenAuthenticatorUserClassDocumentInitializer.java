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

import java.util.List;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.doc.AbstractMandatoryClassInitializer;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Initialize the JWTTokenAuthenticatorUserClass document.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named(JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_FULLNAME)
@Singleton
public class JWTTokenAuthenticatorUserClassDocumentInitializer extends AbstractMandatoryClassInitializer
{
    /**
     * The full name of the class.
     */
    public static final String CLASS_FULLNAME = "AI.Code.JWTTokenAuthenticatorUserClass";

    /**
     * The (local) reference to the class.
     */
    public static final LocalDocumentReference CLASS_REFERENCE = new LocalDocumentReference(List.of("AI", "Code"),
        "JWTTokenAuthenticatorUserClass");

    /**
     * The field for storing the issuer of the token.
     */
    public static final String ISSUER_FIELD = "issuer";

    /**
     * The field for storing the subject of the token.
     */
    public static final String SUBJECT_FIELD = "subject";

    /**
     * Default constructor.
     */
    public JWTTokenAuthenticatorUserClassDocumentInitializer()
    {
        super(CLASS_REFERENCE, "JWT Token Authenticator User Class");
    }

    @Override
    protected void createClass(BaseClass xclass)
    {
        xclass.addTextField(ISSUER_FIELD, "Issuer", 30);
        xclass.addTextField(SUBJECT_FIELD, "Subject", 30);
    }
}
