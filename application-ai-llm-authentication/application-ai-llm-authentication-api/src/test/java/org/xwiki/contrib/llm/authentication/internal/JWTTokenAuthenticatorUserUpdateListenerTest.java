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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.localization.XWikiLocalizationContext;
import com.xpn.xwiki.internal.sheet.ClassSheetBinder;
import com.xpn.xwiki.internal.sheet.DocumentSheetBinder;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE;

@OldcoreTest(mockXWiki = false)
@ReferenceComponentList
@ComponentList({
    JWTTokenAuthenticatorUserUpdateListener.class,
    JWTTokenAuthenticatorUserClassDocumentInitializer.class,
    DefaultObservationManager.class,
    // Dependencies to get the document initializer to work, mostly for sheet binding.
    ClassSheetBinder.class,
    DocumentSheetBinder.class,
    EnumConverter.class,
    DefaultConverterManager.class,
    ConvertUtilsConverter.class,
    DefaultContextualLocalizationManager.class,
    DefaultLocalizationManager.class,
    DefaultTranslationBundleContext.class,
    XWikiLocalizationContext.class,
    DefaultModelContext.class
})
class JWTTokenAuthenticatorUserUpdateListenerTest
{
    private static final DocumentReference DOCUMENT_REFERENCE = new DocumentReference("wiki", "space", "page");
    private static final DocumentReference USER_REFERENCE = new DocumentReference("wiki", "XWiki", "user");

    @MockComponent
    private AuthorizationManager authorizationManager;

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.oldcore.mockQueryManager().createQuery(any(), any())).thenReturn(mock());
        this.oldcore.getMocker().registerMockComponent(JobProgressManager.class);
        this.oldcore.getSpyXWiki().initializeMandatoryDocuments(this.oldcore.getXWikiContext());
        doCallRealMethod().when(this.oldcore.getSpyXWiki()).checkSavingDocument(any(), any(), any());
    }

    @Test
    void testCreatingDocumentWithJWTToken() throws Exception
    {
        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.newXObject(CLASS_REFERENCE, this.oldcore.getXWikiContext());

        XWikiException exception = assertThrows(XWikiException.class, () -> this.oldcore.getSpyXWiki()
            .checkSavingDocument(USER_REFERENCE, document, this.oldcore.getXWikiContext()));
        assertEquals("Error number 9001 in 9: User [wiki:XWiki.user] has been denied the right to save the "
            + "document [wiki:space.page]. Reason: [Only wiki admins can create users with a JWT issuer/subject "
            + "prefilled.]", exception.getMessage());
    }

    @Test
    void testAddingNewJWTToken() throws Exception
    {
        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.newXObject(CLASS_REFERENCE, this.oldcore.getXWikiContext());
        this.oldcore.getSpyXWiki().saveDocument(document, this.oldcore.getXWikiContext());

        document.newXObject(CLASS_REFERENCE, this.oldcore.getXWikiContext());

        XWikiException exception = assertThrows(XWikiException.class, () -> this.oldcore.getSpyXWiki()
            .checkSavingDocument(USER_REFERENCE, document, this.oldcore.getXWikiContext()));
        assertEquals("Error number 9001 in 9: User [wiki:XWiki.user] has been denied the right to save the "
            + "document [wiki:space.page]. Reason: [Only wiki admins can create users with a JWT issuer/subject "
            + "prefilled.]", exception.getMessage());
    }

    @Test
    void testCreatingDocumentWithoutJWTToken() throws Exception
    {
        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);

        assertDoesNotThrow(() -> this.oldcore.getSpyXWiki()
            .checkSavingDocument(USER_REFERENCE, document, this.oldcore.getXWikiContext()));
    }

    @Test
    void testUpdatingDocumentWithJWTToken() throws XWikiException
    {
        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.newXObject(CLASS_REFERENCE, this.oldcore.getXWikiContext());
        this.oldcore.getSpyXWiki().saveDocument(document, this.oldcore.getXWikiContext());

        XWikiDocument updatedDocument =
            this.oldcore.getSpyXWiki().getDocument(DOCUMENT_REFERENCE, this.oldcore.getXWikiContext()).clone();
        assertFalse(updatedDocument.isNew());
        assertFalse(updatedDocument.getXObjects(CLASS_REFERENCE).isEmpty());
        updatedDocument.setContent("New content");

        assertDoesNotThrow(() -> this.oldcore.getSpyXWiki()
            .checkSavingDocument(USER_REFERENCE, updatedDocument, this.oldcore.getXWikiContext()));
    }

    @Test
    void testCreatingDocumentWithJWTTokenAsAdmin() throws Exception
    {
        when(this.authorizationManager.hasAccess(Right.ADMIN, USER_REFERENCE, DOCUMENT_REFERENCE.getWikiReference()))
            .thenReturn(true);

        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.newXObject(CLASS_REFERENCE, this.oldcore.getXWikiContext());

        assertDoesNotThrow(() -> this.oldcore.getSpyXWiki()
            .checkSavingDocument(USER_REFERENCE, document, this.oldcore.getXWikiContext()));
    }
}
