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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.localization.internal.DefaultContextualLocalizationManager;
import org.xwiki.localization.internal.DefaultLocalizationManager;
import org.xwiki.localization.internal.DefaultTranslationBundleContext;
import org.xwiki.model.internal.DefaultModelContext;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.observation.internal.DefaultObservationManager;
import org.xwiki.properties.internal.DefaultConverterManager;
import org.xwiki.properties.internal.converter.ConvertUtilsConverter;
import org.xwiki.properties.internal.converter.EnumConverter;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.localization.XWikiLocalizationContext;
import com.xpn.xwiki.internal.mandatory.XWikiGroupsDocumentInitializer;
import com.xpn.xwiki.internal.sheet.ClassSheetBinder;
import com.xpn.xwiki.internal.sheet.DocumentSheetBinder;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@OldcoreTest
@ReferenceComponentList
@ComponentList({
    JWTTokenAuthenticatorUserClassDocumentInitializer.class,
    XWikiGroupsDocumentInitializer.class,
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
class GroupUpdaterTest
{
    private static final DocumentReference GROUP_REFERENCE =
        new DocumentReference("xwiki", XWiki.SYSTEM_SPACE, "TestGroup");

    private static final LocalDocumentReference GROUP_CLASS =
        new LocalDocumentReference(XWiki.SYSTEM_SPACE, "XWikiGroups");

    @InjectMockComponents
    private GroupUpdater groupUpdater;

    @InjectMockitoOldcore
    private MockitoOldcore mockitoOldcore;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension();

    @Test
    void addAndRemoveUser() throws XWikiException
    {
        String firstUser = "XWiki.User1";
        String secondUser = "XWiki.User2";
        XWikiContext context = this.mockitoOldcore.getXWikiContext();
        this.groupUpdater.addUserToXWikiGroup(firstUser, GROUP_REFERENCE, context);
        this.groupUpdater.addUserToXWikiGroup(secondUser, GROUP_REFERENCE, context);

        XWikiDocument document = this.mockitoOldcore.getSpyXWiki().getDocument(GROUP_REFERENCE, context);
        assertFalse(document.isNew());

        List<BaseObject> groups = document.getXObjects(GROUP_CLASS);
        assertEquals(3, groups.size());
        assertTrue(isMember(groups, firstUser));
        assertTrue(isMember(groups, secondUser));

        this.groupUpdater.removeUserFromXWikiGroup(firstUser, GROUP_REFERENCE, context);

        document = this.mockitoOldcore.getSpyXWiki().getDocument(GROUP_REFERENCE, context);
        groups = document.getXObjects(GROUP_CLASS);
        assertEquals(2, groups.stream().filter(Objects::nonNull).count());
        assertFalse(isMember(groups, firstUser));
        assertTrue(isMember(groups, secondUser));
        assertTrue(isMember(groups, ""));
    }

    @Test
    void addDuplicateUser() throws XWikiException
    {
        String firstUser = "XWiki.User";
        XWikiContext context = this.mockitoOldcore.getXWikiContext();
        this.groupUpdater.addUserToXWikiGroup(firstUser, GROUP_REFERENCE, context);

        XWikiDocument document = this.mockitoOldcore.getSpyXWiki().getDocument(GROUP_REFERENCE, context);
        assertFalse(document.isNew());

        List<BaseObject> groups = document.getXObjects(GROUP_CLASS);
        assertEquals(2, groups.size());
        assertTrue(isMember(groups, firstUser));

        this.groupUpdater.addUserToXWikiGroup(firstUser, GROUP_REFERENCE, context);

        document = this.mockitoOldcore.getSpyXWiki().getDocument(GROUP_REFERENCE, context);
        groups = document.getXObjects(GROUP_CLASS);
        assertEquals(2, groups.size());
        assertTrue(isMember(groups, firstUser));

        assertEquals(1, this.logCapture.size());
        assertEquals("User [%s] already exists in group [%s]".formatted(firstUser, GROUP_REFERENCE),
            this.logCapture.getMessage(0));

    }

    private static boolean isMember(List<BaseObject> groups, String user)
    {
        return groups.stream()
            .filter(Objects::nonNull)
            .anyMatch(object -> user.equals(object.getStringValue("member")));
    }
}
