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
package org.xwiki.contrib.llm.internal.xwikistore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.function.FailableConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentDocument}.
 *
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class XWikiDocumentDocumentTest
{
    private static final String COLLECTION = "mycollection";

    private static final DocumentReference DOCUMENT_REFERENCE =
        new DocumentReference("mywiki", List.of("AI", "Documents"), "MyDocument", Locale.FRENCH);

    @InjectMockComponents
    private XWikiDocumentDocument xWikiDocumentDocument;

    @Mock
    private XWikiDocument xWikiDocument;

    @BeforeEach
    void setUp()
    {
        this.xWikiDocumentDocument.initialize(COLLECTION, this.xWikiDocument);
        when(this.xWikiDocument.getDocumentReferenceWithLocale()).thenReturn(DOCUMENT_REFERENCE);
        when(this.xWikiDocument.getDocumentReference()).thenReturn(DOCUMENT_REFERENCE.withoutLocale());
    }

    @Test
    void getID()
    {
        assertEquals("mywiki:AI.Documents.MyDocument;fr", this.xWikiDocumentDocument.getID());
    }

    @Test
    void getTitle()
    {
        String title = "My Document";
        when(this.xWikiDocument.getRenderedTitle(eq(Syntax.PLAIN_1_0), any())).thenReturn(title);
        assertEquals(title, this.xWikiDocumentDocument.getTitle());
    }

    @Test
    void getLanguage()
    {
        when(this.xWikiDocument.getLocale()).thenReturn(Locale.FRENCH);
        assertEquals("fr", this.xWikiDocumentDocument.getLanguage());
        when(this.xWikiDocument.getLocale()).thenReturn(Locale.ROOT);
        when(this.xWikiDocument.getDefaultLocale()).thenReturn(Locale.GERMAN);
        assertEquals("de", this.xWikiDocumentDocument.getLanguage());
    }

    @Test
    void getURL()
    {
        String url = "https://mywiki/xwiki/bin/view/AI/Documents/MyDocument";
        when(this.xWikiDocument.getExternalURL(eq("view"), any())).thenReturn(url);
        assertEquals(url, this.xWikiDocumentDocument.getURL());
    }

    @Test
    void getCollection()
    {
        assertEquals(COLLECTION, this.xWikiDocumentDocument.getCollection());
    }

    @ParameterizedTest
    @MethodSource("getMimetypeParameters")
    void getMimetype(Syntax syntax, String expectedMimetype)
    {
        when(this.xWikiDocument.getSyntax()).thenReturn(syntax);
        assertEquals(expectedMimetype, this.xWikiDocumentDocument.getMimetype());
    }

    private static Stream<Arguments> getMimetypeParameters()
    {
        return Stream.of(
            Arguments.of(Syntax.PLAIN_1_0, "text/plain"),
            Arguments.of(Syntax.XHTML_1_0, "text/xhtml"),
            Arguments.of(Syntax.HTML_5_0, "text/html"),
            Arguments.of(Syntax.XWIKI_2_1, "text/xwiki"),
            Arguments.of(Syntax.MARKDOWN_1_0, "text/markdown")
        );
    }

    @Test
    void getContent()
    {
        String content = "My content";
        when(this.xWikiDocument.getContent()).thenReturn(content);
        when(this.xWikiDocument.getSyntax()).thenReturn(Syntax.XWIKI_2_1);
        String title = "My Title";
        when(this.xWikiDocument.getRenderedTitle(eq(Syntax.XWIKI_2_1), any())).thenReturn(title);

        assertEquals("""
            = My Title =
            
            My content""", this.xWikiDocumentDocument.getContent());
    }

    @Test
    void getContentMarkdown()
    {
        String content = "Markdown content";
        when(this.xWikiDocument.getContent()).thenReturn(content);
        when(this.xWikiDocument.getSyntax()).thenReturn(Syntax.MARKDOWN_1_0);
        String title = "Markdown Title";
        when(this.xWikiDocument.getRenderedTitle(eq(Syntax.MARKDOWN_1_0), any())).thenReturn(title);

        assertEquals("""
            # Markdown Title
            
            Markdown content""", this.xWikiDocumentDocument.getContent());
    }

    @Test
    void getContentXObjects()
    {
        String content = "XObjects content";
        when(this.xWikiDocument.getContent()).thenReturn(content);
        when(this.xWikiDocument.getSyntax()).thenReturn(Syntax.XWIKI_2_1);
        String title = "XObjects Title";
        when(this.xWikiDocument.getRenderedTitle(eq(Syntax.XWIKI_2_1), any())).thenReturn(title);

        BaseClass baseClass = mock();
        List<PropertyClass> properties = new ArrayList<>();
        when(baseClass.getFieldList()).thenReturn(properties);

        BaseObject xObject = mock();
        when(xObject.getXClass(any())).thenReturn(baseClass);
        when(xObject.getPrettyName()).thenReturn("XObject Pretty Name");

        for (String propertyName : List.of("property1", "property2")) {
            PropertyClass propertyClass = mock();
            when(propertyClass.getTranslatedPrettyName(any())).thenReturn("Property " + propertyName);
            when(propertyClass.getName()).thenReturn(propertyName);
            when(xObject.getStringValue(propertyName)).thenReturn(propertyName + "Value");
            when(xObject.safeget(propertyName)).thenReturn(mock());
            properties.add(propertyClass);
        }
        when(this.xWikiDocument.getXObjects()).thenReturn(Map.of(mock(), List.of(xObject)));

        assertEquals("""
            = XObjects Title =
            
            XObjects content
            
            = XObject Pretty Name =
            
            == Property property1 ==
            
            property1Value
            
            == Property property2 ==
            
            property2Value""", this.xWikiDocumentDocument.getContent());
    }

    @Test
    void getContentAttachments() throws XWikiException
    {
        String content = "Attachments content";
        when(this.xWikiDocument.getContent()).thenReturn(content);
        when(this.xWikiDocument.getSyntax()).thenReturn(Syntax.XWIKI_2_1);
        String title = "Attachments Title";
        when(this.xWikiDocument.getRenderedTitle(eq(Syntax.XWIKI_2_1), any())).thenReturn(title);

        List<XWikiAttachment> attachments = new ArrayList<>();
        for (String attachmentNumber : List.of("1", "2")) {
            XWikiAttachment attachment = mock();
            when(attachment.getFilename()).thenReturn("Attachment Filename " + attachmentNumber);
            when(attachment.getContentInputStream(any()))
                .thenReturn(IOUtils.toInputStream("Attachment Content " + attachmentNumber, StandardCharsets.UTF_8));
            attachments.add(attachment);
        }
        when(this.xWikiDocument.getAttachmentList()).thenReturn(attachments);


        assertEquals("""
            = Attachments Title =
            
            Attachments content
            
            == Attachment Filename 1 ==
            
            Attachment Content 1
            
            
            == Attachment Filename 2 ==
            
            Attachment Content 2
            """, this.xWikiDocumentDocument.getContent());
    }

    private record MethodParameter(
        FailableConsumer<XWikiDocumentDocument, IndexException> method,
        String displayName)
    {
        @Override
        public String toString()
        {
            return this.displayName;
        }
    }

    @ParameterizedTest
    @MethodSource("unsupportedMethodsProvider")
    void unsupportedMethodsTest(MethodParameter methodParameter)
    {
        assertThrows(UnsupportedOperationException.class,
            () -> methodParameter.method.accept(this.xWikiDocumentDocument));
    }

    private static Stream<MethodParameter> unsupportedMethodsProvider()
    {
        return Stream.of(
            new MethodParameter(document -> document.setContent(""), "setContent"),
            new MethodParameter(document -> document.setTitle(""), "setTitle"),
            new MethodParameter(document -> document.setLanguage(""), "setLanguage"),
            new MethodParameter(document -> document.setURL(""), "setURL"),
            new MethodParameter(document -> document.setCollection(""), "setCollection"),
            new MethodParameter(document -> document.setMimetype(""), "setMimetype"),
            new MethodParameter(document -> document.setID(""), "setID"),
            new MethodParameter(XWikiDocumentDocument::getXWikiDocument, "getXWikiDocument"),
            new MethodParameter(XWikiDocumentDocument::chunkDocument, "chunkDocument")
        );
    }
}
