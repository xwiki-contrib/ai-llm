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

import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.xwiki.model.document.DocumentAuthors;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.user.SuperAdminUserReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.classes.BaseClass;

import static org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorUserClassDocumentInitializer.ISSUER_FIELD;
import static org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorUserClassDocumentInitializer.SUBJECT_FIELD;

/**
 * Wrapper around a user document to provide a simplified API to update user properties.
 *
 * @version $Id$
 * @since 0.3
 */
public class JWTTokenAuthenticatorUserDocument
{
    private static final String ACTIVE_FIELD = "active";

    private static final String EMAIL_FIELD = "email";

    private static final Pattern USERNAME_DISALLOWED_PATTERN = Pattern.compile("[^a-zA-Z0-9]+");

    private static final String REPLACEMENT = "-";

    private final XWikiDocument document;

    private XWikiDocument modifiableDocument;

    private BaseObject userObject;

    private BaseObject modifiableUserObject;

    private BaseObject tokenObject;

    private BaseObject modifiableTokenObject;

    private boolean modified;

    /**
     * Create a wrapper around an existing user.
     *
     * @param document the user document to wrap
     */
    public JWTTokenAuthenticatorUserDocument(XWikiDocument document)
    {
        if (document == null || document.isNew()) {
            throw new IllegalArgumentException("Document cannot be null or new");
        }

        this.document = document;
    }

    /**
     * Create a new user document.
     *
     * @param username the username of the new user
     * @param context the XWiki context
     * @throws XWikiException if the user document cannot be created
     */
    public JWTTokenAuthenticatorUserDocument(String username, XWikiContext context) throws XWikiException
    {
        this.document = getNewUserDocument(username, context);
        this.modifiableDocument = this.document;
        this.modified = true;
    }

    /**
     * Set the active status of the user.
     *
     * @param active the new active status
     * @param context the XWiki context
     * @throws XWikiException if the user property cannot be updated
     */
    public void setActive(boolean active, XWikiContext context) throws XWikiException
    {
        int activeValue = active ? 1 : 0;
        setUserProperty(ACTIVE_FIELD, Integer.toString(activeValue), context);
    }

    /**
     * Set the email of the user.
     *
     * @param email the new email
     * @param context the XWiki context
     * @throws XWikiException if the user property cannot be updated
     */
    public void setEmail(String email, XWikiContext context) throws XWikiException
    {
        setUserProperty(EMAIL_FIELD, email, context);
    }

    /**
     * Set the first name of the user.
     *
     * @param firstName the new first name
     * @param context the XWiki context
     * @throws XWikiException if the user property cannot be updated
     */
    public void setFirstName(String firstName, XWikiContext context) throws XWikiException
    {
        setUserProperty("first_name", firstName, context);
    }

    /**
     * Set the last name of the user.
     *
     * @param lastName the new last name
     * @param context the XWiki context
     * @throws XWikiException if the user property cannot be updated
     */
    public void setLastName(String lastName, XWikiContext context) throws XWikiException
    {
        setUserProperty("last_name", lastName, context);
    }

    /**
     * Save the user document if it has been modified.
     *
     * @param context the XWiki context
     * @throws XWikiException if the document cannot be saved
     */
    public void maybeSave(XWikiContext context) throws XWikiException
    {
        if (this.modified) {
            context.getWiki().saveDocument(this.modifiableDocument, "Update user profile", context);
            this.modified = false;
        }
    }

    /**
     * Get the document reference of the user.
     *
     * @return the document reference
     */
    public DocumentReference getDocumentReference()
    {
        return this.document.getDocumentReference();
    }

    private void setUserProperty(String propertyName, String value, XWikiContext context) throws XWikiException
    {
        BaseObject user = this.getUserObject(context);
        if (value != null && (user == null || !value.equals(user.getStringValue(propertyName)))) {
            getModifiableUserObject(context).set(propertyName, value, context);
            this.modified = true;
        }
    }

    /**
     * Set the subject and issuer of the token associated with the user.
     *
     * @param subject the subject of the token
     * @param issuer the issuer of the token
     * @param context the XWiki context
     */
    public void setSubjectIssuer(String subject, String issuer, XWikiContext context)
    {
        BaseObject token = this.getTokenObject();
        if (token == null || !subject.equals(token.getStringValue(SUBJECT_FIELD))
            || !issuer.equals(token.getStringValue(ISSUER_FIELD))) {
            BaseObject modifiableObject = getModifiableTokenObject(context);
            modifiableObject.set(SUBJECT_FIELD, subject, context);
            modifiableObject.set(ISSUER_FIELD, issuer, context);
            this.modified = true;
        }
    }

    private BaseObject getUserObject(XWikiContext context) throws XWikiException
    {
        if (this.userObject == null) {
            BaseClass userClass = context.getWiki().getUserClass(context);

            this.userObject = this.document.getXObject(userClass.getDocumentReference());
        }
        return this.userObject;
    }

    private BaseObject getModifiableUserObject(XWikiContext context) throws XWikiException
    {
        if (this.modifiableUserObject == null) {
            BaseClass userClass = context.getWiki().getUserClass(context);

            this.modifiableUserObject = getModifiableDocument().getXObject(userClass.getDocumentReference(), true,
                context);
            // Set the user object to the modifiable object. This is for two reasons:
            // a) to make sure that repeated calls to setters don't try getting the object again and again
            // b) to ensure that calls to setters after saving the document will compare against the saved version.
            this.userObject = this.modifiableUserObject;
        }
        return this.modifiableUserObject;
    }

    private BaseObject getTokenObject()
    {
        if (this.tokenObject == null) {
            this.tokenObject =
                this.document.getXObject(JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE);
        }
        return this.tokenObject;
    }

    private BaseObject getModifiableTokenObject(XWikiContext context)
    {
        if (this.modifiableTokenObject == null) {
            this.modifiableTokenObject =
                getModifiableDocument().getXObject(JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE,
                    true, context);
            // Set the token object to the modifiable object similar to the user object.
            this.tokenObject = this.modifiableTokenObject;
        }
        return this.modifiableTokenObject;
    }

    private XWikiDocument getModifiableDocument()
    {
        if (this.modifiableDocument == null) {
            this.modifiableDocument = this.document.clone();
        }

        return this.modifiableDocument;
    }

    private static XWikiDocument getNewUserDocument(String username, XWikiContext context) throws XWikiException
    {

        // TODO: add support for subwikis
        SpaceReference spaceReference = new SpaceReference(context.getMainXWiki(), "XWiki");

        // Generate default document name
        String documentName = USERNAME_DISALLOWED_PATTERN.matcher(username).replaceAll(REPLACEMENT);
        documentName = StringUtils.removeEnd(StringUtils.removeStart(documentName, REPLACEMENT), REPLACEMENT);
        if (StringUtils.isBlank(documentName)) {
            documentName = "user";
        }
        DocumentReference reference = new DocumentReference(documentName, spaceReference);
        XWikiDocument result = context.getWiki().getDocument(reference, context);
        for (int index = 0; !result.isNew(); ++index) {
            reference = new DocumentReference(documentName + '-' + index, spaceReference);

            result = context.getWiki().getDocument(reference, context);
        }

        // Initialize document
        DocumentAuthors authors = result.getAuthors();
        authors.setCreator(SuperAdminUserReference.INSTANCE);
        authors.setContentAuthor(SuperAdminUserReference.INSTANCE);
        authors.setEffectiveMetadataAuthor(SuperAdminUserReference.INSTANCE);
        authors.setOriginalMetadataAuthor(SuperAdminUserReference.INSTANCE);
        context.getWiki().protectUserPage(result.getFullName(), Right.EDIT.getName(),
            result, context);

        return result;
    }
}
