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
package org.xwiki.contrib.llm.mcp.internal.tool;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.localization.LocalizationContext;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Base class for the oldcore-backed write tool tests: the universal mock components, the fixture every
 * write test needs, opt-in fixture helpers and the real-store document plumbing. Deliberately unannotated -
 * the concrete class carries {@code @OldcoreTest} and the {@code @InjectMockComponents} field.
 *
 * @version $Id$
 */
public abstract class AbstractMCPWriteToolTest extends AbstractMCPToolTest
{
    /**
     * The user the write runs as.
     */
    protected static final DocumentReference USER_REFERENCE =
        new DocumentReference("xwiki", "XWiki", "Author");

    @MockComponent
    protected MCPDocumentAccess documentAccess;

    @MockComponent
    protected EntityReferenceSerializer<String> serializer;

    @MockComponent
    protected DocumentAccessBridge documentAccessBridge;

    @MockComponent
    protected MCPWikiReach wikiReach;

    /**
     * The fixture core shared by every write tool test: rights pass, a legacy-string user is set, and the
     * endpoint has cross-wiki reach by default.
     *
     * @param oldcore the oldcore fixture
     * @throws Exception on fixture failure
     */
    @BeforeEach
    void setUpWriteFixture(MockitoOldcore oldcore) throws Exception
    {
        when(oldcore.getMockRightService().hasAccessLevel(any(), any(), any(), any())).thenReturn(true);

        // XWikiContext.setUserReference() resolves the legacy string user via the "compactwiki" serializer.
        if (!oldcore.getMocker().hasComponent(EntityReferenceSerializer.TYPE_STRING, "compactwiki")) {
            oldcore.getMocker().registerMockComponent(EntityReferenceSerializer.TYPE_STRING, "compactwiki");
        }
        oldcore.getXWikiContext().setUserReference(USER_REFERENCE);

        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
    }

    /**
     * Makes {@code api.Document.save} skip the author-rights branch.
     *
     * @param oldcore the oldcore fixture
     */
    protected static void allowSimpleSavePath(MockitoOldcore oldcore)
    {
        // Take the simple save path in api.Document.save (skip the saveAsAuthor branch).
        oldcore.getConfigurationSource().setProperty("security.script.save.checkAuthor", false);
    }

    /**
     * Registers a {@code current} document reference resolver answering the given class reference.
     *
     * @param oldcore the oldcore fixture
     * @param classReference the class reference every resolution answers
     * @return the registered resolver mock
     * @throws Exception on fixture failure
     */
    protected static DocumentReferenceResolver<EntityReference> registerCurrentResolver(MockitoOldcore oldcore,
        DocumentReference classReference) throws Exception
    {
        // removeXObject resolves the object's relative class reference through the "current" resolver,
        // which the oldcore fixture does not provide; the tests use a single class, so a fixed answer
        // reproduces the platform resolution.
        DocumentReferenceResolver<EntityReference> currentResolver = oldcore.getMocker()
            .registerMockComponent(DocumentReferenceResolver.TYPE_REFERENCE, "current");
        lenient().when(currentResolver.resolve(any(EntityReference.class), any()))
            .thenReturn(classReference);
        return currentResolver;
    }

    /**
     * Registers a {@link LocalizationContext} answering the English locale.
     *
     * @param oldcore the oldcore fixture
     * @throws Exception on fixture failure
     */
    protected static void registerLocalizationContext(MockitoOldcore oldcore) throws Exception
    {
        // DateClass.fromString resolves the context locale through Utils; without a LocalizationContext
        // the parse would spill a WARN that the LogCaptureExtension then requires asserting.
        LocalizationContext localizationContext =
            oldcore.getMocker().registerMockComponent(LocalizationContext.class);
        lenient().when(localizationContext.getCurrentLocale()).thenReturn(Locale.ENGLISH);
    }

    /**
     * Loads a document from the oldcore store.
     *
     * @param oldcore the oldcore fixture
     * @param reference the document to load
     * @return the loaded document
     * @throws Exception on store failure
     */
    protected static XWikiDocument loadDocument(MockitoOldcore oldcore, DocumentReference reference)
        throws Exception
    {
        return oldcore.getSpyXWiki().getDocument(reference, oldcore.getXWikiContext());
    }

    /**
     * Stores a document with the given content and, when non-null, title.
     *
     * @param oldcore the oldcore fixture
     * @param reference the document reference
     * @param content the document content
     * @param title the document title, or null to leave it unset
     * @throws Exception on store failure
     */
    protected static void storeDocument(MockitoOldcore oldcore, DocumentReference reference, String content,
        String title) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(reference);
        doc.setContent(content);
        if (title != null) {
            doc.setTitle(title);
        }
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    /**
     * Stores a translation row of a document with the given content.
     *
     * @param oldcore the oldcore fixture
     * @param reference the document reference
     * @param locale the translation locale
     * @param content the translation content
     * @throws Exception on store failure
     */
    protected static void storeTranslation(MockitoOldcore oldcore, DocumentReference reference, Locale locale,
        String content) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(reference, locale);
        doc.setContent(content);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    /**
     * Reads the current stored version of a document.
     *
     * @param oldcore the oldcore fixture
     * @param reference the document reference
     * @return the current version
     * @throws Exception on store failure
     */
    protected static String currentVersion(MockitoOldcore oldcore, DocumentReference reference) throws Exception
    {
        return loadDocument(oldcore, reference).getVersion();
    }

    /**
     * Asserts that the tool saved nothing.
     *
     * @param oldcore the oldcore fixture
     * @throws Exception on verification failure
     */
    protected static void verifyNothingSaved(MockitoOldcore oldcore) throws Exception
    {
        verify(oldcore.getSpyXWiki(), never())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }
}
