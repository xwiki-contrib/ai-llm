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
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
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
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;

import com.nimbusds.jwt.JWTClaimsSet;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.localization.XWikiLocalizationContext;
import com.xpn.xwiki.internal.mandatory.XWikiUsersDocumentInitializer;
import com.xpn.xwiki.internal.sheet.ClassSheetBinder;
import com.xpn.xwiki.internal.sheet.DocumentSheetBinder;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorUserClassDocumentInitializer.CLASS_REFERENCE;
import static org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorUserClassDocumentInitializer.ISSUER_FIELD;
import static org.xwiki.contrib.llm.authentication.internal.JWTTokenAuthenticatorUserClassDocumentInitializer.SUBJECT_FIELD;

/**
 * Tests for {@link JWTTokenAuthenticatorUserStore}.
 *
 * @version $Id$
 */
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
@OldcoreTest
class JWTTokenAuthenticatorUserStoreTest
{
    private static final String SUBJECT = "mySubject";

    private static final String GIVEN_NAME = "Given Name";

    private static final String FAMILY_NAME = "Family Name";

    private static final String EMAIL = "email@example.com";

    private static final List<String> CLAIM_GROUPS = List.of("Group1", "Group2");

    private static final List<String> MAPPED_GROUPS = List.of("ApplicationGroup1", "ApplicationGroup2");

    private static final String ISSUER = "myIssuer";

    private static final AuthorizedApplication APPLICATION = new AuthorizedApplication(null, ISSUER, "My Application",
        "Application${group}", null);

    private static final JWTClaimsSet CLAIMS = new JWTClaimsSet.Builder()
        .subject(SUBJECT)
        .issuer(ISSUER)
        .claim("given_name", GIVEN_NAME)
        .claim("family_name", FAMILY_NAME)
        .claim("email", EMAIL)
        .claim("groups", CLAIM_GROUPS)
        .build();

    private static final DocumentReference ALL_GROUP = getGroupReference("XWikiAllGroup");

    @InjectMockitoOldcore
    private MockitoOldcore mockitoOldcore;

    @InjectMockComponents
    private JWTTokenAuthenticatorUserStore userStore;

    @MockComponent
    private GroupUpdater groupUpdater;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    private GroupManager groupManager;

    @MockComponent
    private JWTTokenAuthenticatorConfiguration configuration;

    @Mock
    private Query query;

    @BeforeEach
    void beforeEach() throws Exception
    {
        when(this.queryManager.createQuery(any(), any())).thenReturn(this.query);
        when(this.query.bindValue(any(), any())).thenReturn(this.query);
        when(this.configuration.getInitialXWikiGroups()).thenReturn(Set.of("XWiki.XWikiAllGroup"));
        this.mockitoOldcore.getMocker().registerMockComponent(JobProgressManager.class);
        this.mockitoOldcore.getSpyXWiki().initializeMandatoryDocuments(this.mockitoOldcore.getXWikiContext());
    }

    @Test
    void updateNewUser() throws Exception
    {
        when(this.query.execute()).thenReturn(List.of());

        DocumentReference documentReference = this.userStore.updateUser(APPLICATION, CLAIMS);

        assertEquals(SUBJECT, documentReference.getName());
        XWikiDocument userDocument =
            this.mockitoOldcore.getSpyXWiki().getDocument(documentReference, this.mockitoOldcore.getXWikiContext());

        BaseObject userObject = userDocument.getXObject(XWikiUsersDocumentInitializer.XWIKI_USERS_DOCUMENT_REFERENCE);
        assertEquals(GIVEN_NAME, userObject.getStringValue("first_name"));
        assertEquals(FAMILY_NAME, userObject.getStringValue("last_name"));
        assertEquals(EMAIL, userObject.getStringValue("email"));
        assertEquals(1, userObject.getIntValue("active"));

        BaseObject jwtObject = userDocument.getXObject(CLASS_REFERENCE);
        assertEquals(ISSUER, jwtObject.getStringValue(ISSUER_FIELD));
        assertEquals(SUBJECT, jwtObject.getStringValue(SUBJECT_FIELD));

        MAPPED_GROUPS.forEach(group -> verify(this.groupUpdater).addUserToXWikiGroup(userDocument.getFullName(),
            getGroupReference(group), this.mockitoOldcore.getXWikiContext()));
        verify(this.groupUpdater, never()).removeUserFromXWikiGroup(any(), any(), any());
    }

    @Test
    void updateExistingUser() throws XWikiException, QueryException, GroupException
    {
        DocumentReference userReference = new DocumentReference("xwiki", "XWiki", SUBJECT);
        when(this.query.execute()).thenReturn(List.of(userReference.toString()));

        XWikiContext context = this.mockitoOldcore.getXWikiContext();
        JWTTokenAuthenticatorUserDocument userDocument = new JWTTokenAuthenticatorUserDocument(SUBJECT,
            context);
        String old = "old";
        userDocument.setEmail(old, context);
        userDocument.setFirstName(old, context);
        userDocument.setLastName(old, context);
        userDocument.setActive(false, context);
        userDocument.setSubjectIssuer(SUBJECT, ISSUER, context);
        userDocument.maybeSave(context);

        DocumentReference oldGroup = getGroupReference("oldGroup");
        when(this.groupManager.getGroups(userReference, userReference.getWikiReference(), false))
            .thenReturn(List.of(oldGroup, ALL_GROUP, getGroupReference(MAPPED_GROUPS.get(0))));

        when(this.query.execute()).thenReturn(List.of(userReference.toString()));

        DocumentReference documentReference = this.userStore.updateUser(APPLICATION, CLAIMS);
        assertEquals(userReference, documentReference);

        XWikiDocument userDoc = this.mockitoOldcore.getSpyXWiki().getDocument(userReference, context);
        BaseObject userObject = userDoc.getXObject(XWikiUsersDocumentInitializer.XWIKI_USERS_DOCUMENT_REFERENCE);
        assertEquals(GIVEN_NAME, userObject.getStringValue("first_name"));
        assertEquals(FAMILY_NAME, userObject.getStringValue("last_name"));
        assertEquals(EMAIL, userObject.getStringValue("email"));
        // Just logging in again shouldn't re-activate the user.
        assertEquals(0, userObject.getIntValue("active"));

        BaseObject jwtObject = userDoc.getXObject(CLASS_REFERENCE);
        assertEquals(ISSUER, jwtObject.getStringValue(ISSUER_FIELD));
        assertEquals(SUBJECT, jwtObject.getStringValue(SUBJECT_FIELD));

        verify(this.groupUpdater).removeUserFromXWikiGroup(userDoc.getFullName(), oldGroup, context);
        verify(this.groupUpdater).addUserToXWikiGroup(userDoc.getFullName(), getGroupReference(MAPPED_GROUPS.get(1)),
            context);
        verifyNoMoreInteractions(this.groupUpdater);
    }

    private static DocumentReference getGroupReference(String groupName)
    {
        return new DocumentReference("xwiki", "XWiki", groupName);
    }
}
