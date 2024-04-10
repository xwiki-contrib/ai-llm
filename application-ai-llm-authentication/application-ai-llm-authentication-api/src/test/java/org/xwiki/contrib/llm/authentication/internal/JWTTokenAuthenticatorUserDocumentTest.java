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
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.xwiki.job.event.status.JobProgressManager;
import org.xwiki.localization.internal.DefaultContextualLocalizationManager;
import org.xwiki.localization.internal.DefaultLocalizationManager;
import org.xwiki.localization.internal.DefaultTranslationBundleContext;
import org.xwiki.model.internal.DefaultModelContext;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.internal.DefaultObservationManager;
import org.xwiki.properties.internal.DefaultConverterManager;
import org.xwiki.properties.internal.converter.ConvertUtilsConverter;
import org.xwiki.properties.internal.converter.EnumConverter;
import org.xwiki.test.annotation.ComponentList;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.localization.XWikiLocalizationContext;
import com.xpn.xwiki.internal.mandatory.XWikiUsersDocumentInitializer;
import com.xpn.xwiki.internal.sheet.ClassSheetBinder;
import com.xpn.xwiki.internal.sheet.DocumentSheetBinder;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JWTTokenAuthenticatorUserDocument}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
@ComponentList({
    JWTTokenAuthenticatorUserClassDocumentInitializer.class,
    XWikiUsersDocumentInitializer.class,
    // Dependencies to get the document initializer to work, mostly for sheet binding.
    ClassSheetBinder.class,
    DocumentSheetBinder.class,
    DefaultObservationManager.class,
    EnumConverter.class,
    DefaultConverterManager.class,
    ConvertUtilsConverter.class,
    DefaultContextualLocalizationManager.class,
    DefaultLocalizationManager.class,
    DefaultTranslationBundleContext.class,
    XWikiLocalizationContext.class,
    DefaultModelContext.class
})
class JWTTokenAuthenticatorUserDocumentTest
{
    private static final DocumentReference USER_REFERENCE = new DocumentReference("xwiki", "XWiki", "User");

    private static final String ACTIVE = "active";

    private static final String EMAIL = "email";

    private static final String FIRST_NAME = "first_name";

    private static final String LAST_NAME = "last_name";

    @InjectMockitoOldcore
    private MockitoOldcore mockitoOldcore;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.mockitoOldcore.mockQueryManager().createQuery(any(), any())).thenReturn(mock());
        this.mockitoOldcore.getMocker().registerMockComponent(JobProgressManager.class);
        this.mockitoOldcore.getSpyXWiki().initializeMandatoryDocuments(this.mockitoOldcore.getXWikiContext());
    }

    @Test
    void createWithNewDocument()
    {
        XWikiDocument document = new XWikiDocument(USER_REFERENCE);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new JWTTokenAuthenticatorUserDocument(document);
        });

        assertEquals("Document cannot be null or new", exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "Alice, Alice",
        "Alöce, Al-ce",
        "Älice, lice",
        "Ä, user",
        "existing, existing-0",
        "twoexisting, twoexisting-1"
    })
    void createUser(String username, String expectedUsername) throws XWikiException
    {
        for (String existingName : List.of("existing", "twoexisting", "twoexisting-0")) {
            DocumentReference userReference = new DocumentReference("xwiki", "XWiki", existingName);
            XWikiDocument document = new XWikiDocument(userReference);
            this.mockitoOldcore.getSpyXWiki().saveDocument(document, this.mockitoOldcore.getXWikiContext());
        }

        JWTTokenAuthenticatorUserDocument userDocument = new JWTTokenAuthenticatorUserDocument(username,
            this.mockitoOldcore.getXWikiContext());
        assertEquals(expectedUsername, userDocument.getDocumentReference().getName());

        verify(this.mockitoOldcore.getSpyXWiki()).protectUserPage(eq(XWiki.SYSTEM_SPACE + "." + expectedUsername),
            eq("edit"), any(), any());

        // Set a property and save the document
        userDocument.setActive(true, this.mockitoOldcore.getXWikiContext());
        userDocument.maybeSave(this.mockitoOldcore.getXWikiContext());
        verify(this.mockitoOldcore.getSpyXWiki()).saveDocument(any(), eq("Update user profile"), any());

        // Verify that the document has been saved.
        XWikiDocument savedDocument = this.mockitoOldcore.getSpyXWiki().getDocument(userDocument.getDocumentReference(),
            this.mockitoOldcore.getXWikiContext());
        assertEquals("1", savedDocument.getXObject(XWikiUsersDocumentInitializer.XWIKI_USERS_DOCUMENT_REFERENCE)
            .getStringValue(ACTIVE));
    }

    @ParameterizedTest
    @MethodSource("userPropertiesProvider")
    void setNewUserProperty(String propertyName, Object oldValue, Object newValue) throws Exception
    {
        XWikiDocument document = new XWikiDocument(USER_REFERENCE);
        XWikiContext context = this.mockitoOldcore.getXWikiContext();
        if (oldValue != null) {
            document.getXObject(XWikiUsersDocumentInitializer.XWIKI_USERS_DOCUMENT_REFERENCE, true, context)
                .set(propertyName, oldValue, context);
        }
        this.mockitoOldcore.getSpyXWiki().saveDocument(document, context);
        XWikiDocument savedDocument = this.mockitoOldcore.getSpyXWiki().getDocument(USER_REFERENCE, context);

        XWikiDocument initialDocument = document.clone();

        JWTTokenAuthenticatorUserDocument userDocument = new JWTTokenAuthenticatorUserDocument(document);
        switch (propertyName) {
            case ACTIVE:
                userDocument.setActive(Integer.valueOf(1).equals(newValue), context);
                break;
            case EMAIL:
                userDocument.setEmail((String) newValue, context);
                break;
            case FIRST_NAME:
                userDocument.setFirstName((String) newValue, context);
                break;
            case LAST_NAME:
                userDocument.setLastName((String) newValue, context);
                break;
            default:
                throw new IllegalArgumentException("Unknown property name: " + propertyName);
        }
        userDocument.maybeSave(context);

        // Verify that the original document hasn't been changed, but a clone has been created instead.
        assertEquals(initialDocument, document);
        XWikiDocument updatedDocument = this.mockitoOldcore.getSpyXWiki().getDocument(USER_REFERENCE, context);
        if (Objects.equals(oldValue, newValue)) {
            // Verify that the document hasn't been saved.
            assertSame(savedDocument, updatedDocument);
            verify(this.mockitoOldcore.getSpyXWiki(), never()).saveDocument(any(), eq("Update user profile"), any());
        } else {
            // Verify that the document has been saved.
            verify(this.mockitoOldcore.getSpyXWiki()).saveDocument(any(), eq("Update user profile"), any());
        }
        assertEquals(String.valueOf(newValue),
            updatedDocument.getXObject(XWikiUsersDocumentInitializer.XWIKI_USERS_DOCUMENT_REFERENCE)
            .getStringValue(propertyName));

        // Verify that saving again doesn't save the document again.
        userDocument.maybeSave(context);
        assertSame(updatedDocument, this.mockitoOldcore.getSpyXWiki().getDocument(USER_REFERENCE, context));
    }

    private static Stream<Arguments> userPropertiesProvider()
    {
        return Stream.of(
            Arguments.of(ACTIVE, 1, 1),
            Arguments.of(ACTIVE, 0, 1),
            Arguments.of(ACTIVE, 0, 0),
            Arguments.of(ACTIVE, null, 1),
            Arguments.of(EMAIL, null, "test@example.com"),
            Arguments.of(FIRST_NAME, null, "John"),
            Arguments.of(FIRST_NAME, "Alice", "Bob"),
            Arguments.of(LAST_NAME, LAST_NAME, LAST_NAME),
            Arguments.of(LAST_NAME, null, "Doe")
        );
    }

    @Test
    void setIssuerSubject() throws Exception
    {
        XWikiDocument document = new XWikiDocument(USER_REFERENCE);
        XWikiContext context = this.mockitoOldcore.getXWikiContext();
        this.mockitoOldcore.getSpyXWiki().saveDocument(document, context);

        XWikiDocument initialDocument = document.clone();

        JWTTokenAuthenticatorUserDocument userDocument = new JWTTokenAuthenticatorUserDocument(document);
        userDocument.setSubjectIssuer("subject", "issuer", context);
        userDocument.maybeSave(context);

        // Verify that the original document hasn't been changed, but a clone has been created instead.
        assertEquals(initialDocument, document);
        XWikiDocument updatedDocument = this.mockitoOldcore.getSpyXWiki().getDocument(USER_REFERENCE, context);
        // Verify that the document has been saved.
        verify(this.mockitoOldcore.getSpyXWiki()).saveDocument(any(), eq("Update user profile"), any());
        assertEquals("subject",
            updatedDocument.getXObject(JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE)
            .getStringValue(JWTTokenAuthenticatorUserClassDocumentInitializer.SUBJECT_FIELD));
        assertEquals("issuer",
            updatedDocument.getXObject(JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE)
            .getStringValue(JWTTokenAuthenticatorUserClassDocumentInitializer.ISSUER_FIELD));

        // Set the same values again.
        userDocument.setSubjectIssuer("subject", "issuer", context);

        // Verify that saving again doesn't save the document again.
        userDocument.maybeSave(context);
        assertSame(updatedDocument, this.mockitoOldcore.getSpyXWiki().getDocument(USER_REFERENCE, context));
    }
}
