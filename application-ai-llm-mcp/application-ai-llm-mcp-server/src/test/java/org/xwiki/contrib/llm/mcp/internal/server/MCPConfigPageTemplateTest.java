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
package org.xwiki.contrib.llm.mcp.internal.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.xwiki.extension.InstalledExtension;
import org.xwiki.extension.LocalExtensionFile;
import org.xwiki.extension.repository.InstalledExtensionRepository;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.component.XWikiDocumentFilterUtilsComponentList;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPConfigPageTemplate}. The XAR package is a real zip built in a temporary directory and the
 * page XML goes through the real {@code fromXML} parser, so the test covers the reference resolution against the
 * context wiki.
 *
 * @version $Id$
 */
@OldcoreTest
@XWikiDocumentFilterUtilsComponentList
class MCPConfigPageTemplateTest
{
    private static final String CONTEXT_WIKI = "xwiki";

    private static final String WIKI = "subwiki";

    private static final String WIKI_NAMESPACE = "wiki:subwiki";

    private static final String ENTRY_NAME = "AI/MCP/Code/MCPServerConfig.xml";

    private static final String PACKAGE_NOT_INSTALLED =
        "The extension [org.xwiki.contrib.llm:application-ai-llm-mcp-ui] is not installed; the MCP configuration page"
        + " of wiki [subwiki] cannot be read from its package";

    private static final DocumentReference CONFIG_REFERENCE =
        new DocumentReference(WIKI, List.of("AI", "MCP", "Code"), "MCPServerConfig");

    private static final DocumentReference CLASS_REFERENCE =
        new DocumentReference(WIKI, List.of("AI", "MCP", "Code"), "MCPServerConfigClass");

    private static final String PAGE_XML_HEAD = """
        <?xml version="1.0" encoding="UTF-8"?>
        <xwikidoc version="1.6" reference="AI.MCP.Code.MCPServerConfig" locale="">
          <web>AI.MCP.Code</web>
          <name>MCPServerConfig</name>
          <language/>
          <defaultLanguage/>
          <translation>0</translation>
          <creator>xwiki:XWiki.Admin</creator>
          <parent>AI.MCP.Code.WebHome</parent>
          <author>xwiki:XWiki.Admin</author>
          <contentAuthor>xwiki:XWiki.Admin</contentAuthor>
          <version>1.1</version>
          <title>MCP Server Configuration</title>
          <comment/>
          <minorEdit>false</minorEdit>
          <syntaxId>xwiki/2.1</syntaxId>
          <hidden>true</hidden>
          <content/>
        """;

    private static final String OBJECT_XML_TEMPLATE = """
          <object>
            <name>AI.MCP.Code.MCPServerConfig</name>
            <number>0</number>
            <className>AI.MCP.Code.%1$s</className>
            <guid>aaae5e1e-beb8-475a-bac4-4db1542ba2af</guid>
            <class>
              <name>AI.MCP.Code.%1$s</name>
              <customClass/>
              <customMapping/>
              <defaultViewSheet/>
              <defaultEditSheet/>
              <defaultWeb/>
              <nameField/>
              <validationScript/>
              <enabled>
                <customDisplay/>
                <defaultValue/>
                <disabled>0</disabled>
                <displayFormType>checkbox</displayFormType>
                <displayType/>
                <name>enabled</name>
                <number>3</number>
                <prettyName>Enabled</prettyName>
                <unmodifiable>0</unmodifiable>
                <validationMessage/>
                <validationRegExp/>
                <classType>com.xpn.xwiki.objects.classes.BooleanClass</classType>
              </enabled>
            </class>
            <property>
              <enabled>1</enabled>
            </property>
          </object>
        """;

    private static final String PAGE_XML_TAIL = "</xwikidoc>\n";

    private static final String SHIPPED_PAGE_XML =
        PAGE_XML_HEAD + String.format(OBJECT_XML_TEMPLATE, "MCPServerConfigClass") + PAGE_XML_TAIL;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPConfigPageTemplate template;

    @MockComponent
    private InstalledExtensionRepository installedExtensionRepository;

    @Mock
    private InstalledExtension extension;

    @Mock
    private LocalExtensionFile extensionFile;

    @TempDir
    private Path tempDir;

    private XWikiContext context;

    @BeforeEach
    void setUp(MockitoOldcore oldcore)
    {
        this.context = oldcore.getXWikiContext();
        this.context.setWikiId(CONTEXT_WIKI);
        lenient().when(this.extension.getFile()).thenReturn(this.extensionFile);
    }

    private void installed()
    {
        when(this.installedExtensionRepository.getInstalledExtension(MCPConfigPageTemplate.UI_EXTENSION_ID,
            WIKI_NAMESPACE)).thenReturn(this.extension);
    }

    /**
     * Builds a XAR (a zip of page XML files) in the temporary directory and serves it from the extension file.
     *
     * @param entries the zip entries: entry name to XML content
     * @return the path of the package
     */
    private Path packageWith(Map<String, String> entries) throws IOException
    {
        Path xar = this.tempDir.resolve("application-ai-llm-mcp-ui.xar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(xar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        when(this.extensionFile.openStream()).thenAnswer(invocation -> Files.newInputStream(xar));
        return xar;
    }

    private void assertShippedPage(XWikiDocument page)
    {
        assertNotNull(page);
        assertTrue(page.isNew());
        assertEquals(CONFIG_REFERENCE, page.getDocumentReference());
        assertEquals("MCP Server Configuration", page.getTitle());
        assertTrue(page.isHidden());
        BaseObject object = page.getXObject(CLASS_REFERENCE);
        assertNotNull(object);
        assertEquals(1, object.getIntValue("enabled"));
        // The parse switches the context to the target wiki and switches it back.
        assertEquals(CONTEXT_WIKI, this.context.getWikiId());
    }

    @Test
    void shippedPageIsNullWhenTheExtensionIsNotInstalled()
    {
        assertNull(this.template.shippedPage(WIKI, this.context));

        // One lookup on the wiki's namespace: the repository itself falls back to the root namespace.
        verify(this.installedExtensionRepository).getInstalledExtension(MCPConfigPageTemplate.UI_EXTENSION_ID,
            WIKI_NAMESPACE);
        verify(this.installedExtensionRepository, never()).getInstalledExtension(any(), eq(null));
        assertEquals(PACKAGE_NOT_INSTALLED, this.logCapture.getMessage(0));
    }

    @Test
    void shippedPageReadsThePageFromTheInstalledExtension() throws Exception
    {
        installed();
        packageWith(Map.of(ENTRY_NAME, SHIPPED_PAGE_XML));

        assertShippedPage(this.template.shippedPage(WIKI, this.context));
    }

    @Test
    void shippedPageFindsThePageWhateverItsEntryName() throws Exception
    {
        installed();
        // The page is located through the package index (built from the XML), not through a path convention.
        packageWith(Map.of("Other/Page.xml", PAGE_XML_HEAD.replace("MCPServerConfig", "Other") + PAGE_XML_TAIL,
            "some/odd/path.xml", SHIPPED_PAGE_XML));

        assertShippedPage(this.template.shippedPage(WIKI, this.context));
    }

    @Test
    void shippedPageIsNullWhenThePackageDoesNotContainThePage() throws Exception
    {
        installed();
        packageWith(Map.of("Other/Page.xml", PAGE_XML_HEAD.replace("MCPServerConfig", "Other") + PAGE_XML_TAIL));

        assertNull(this.template.shippedPage(WIKI, this.context));
        assertEquals("The package of extension [org.xwiki.contrib.llm:application-ai-llm-mcp-ui] does not contain the "
            + "page [AI.MCP.Code.MCPServerConfig]; the MCP configuration page of wiki [subwiki] cannot be read from "
            + "it", this.logCapture.getMessage(0));
    }

    @Test
    void shippedPageIsNullWhenThePageLacksTheConfigurationObject() throws Exception
    {
        installed();
        packageWith(Map.of(ENTRY_NAME,
            PAGE_XML_HEAD + String.format(OBJECT_XML_TEMPLATE, "SomeOtherClass") + PAGE_XML_TAIL));

        assertNull(this.template.shippedPage(WIKI, this.context));
        assertEquals("The page [subwiki:AI.MCP.Code.MCPServerConfig] read from the package of extension "
            + "[org.xwiki.contrib.llm:application-ai-llm-mcp-ui] is not the MCP configuration page of wiki [subwiki]",
            this.logCapture.getMessage(0));
        assertEquals(CONTEXT_WIKI, this.context.getWikiId());
    }

    @Test
    void shippedPageIsNullAndRestoresTheContextWikiWhenThePageXmlCannotBeParsed() throws Exception
    {
        installed();
        // The package index only needs the root element to register the entry; the parser needs the whole page.
        packageWith(Map.of(ENTRY_NAME, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xwikidoc version=\"1.6\" reference=\"AI.MCP.Code.MCPServerConfig\" locale=\"\">\n<web>AI.MCP"));

        assertNull(this.template.shippedPage(WIKI, this.context));
        assertEquals(CONTEXT_WIKI, this.context.getWikiId());
        assertTrue(this.logCapture.getMessage(0).startsWith("Could not read the MCP configuration page of wiki "
            + "[subwiki] from the package of extension [org.xwiki.contrib.llm:application-ai-llm-mcp-ui]: ["),
            this.logCapture.getMessage(0));
    }

    @Test
    void shippedPageIsNullWhenThePackageCannotBeReadASecondTime() throws Exception
    {
        installed();
        Path xar = packageWith(Map.of(ENTRY_NAME, SHIPPED_PAGE_XML));
        when(this.extensionFile.openStream()).thenReturn(Files.newInputStream(xar))
            .thenThrow(new IOException("Disk gone"));

        assertNull(this.template.shippedPage(WIKI, this.context));
        assertEquals("Could not read the MCP configuration page of wiki [subwiki] from the package of extension "
            + "[org.xwiki.contrib.llm:application-ai-llm-mcp-ui]: [IOException: Disk gone]",
            this.logCapture.getMessage(0));
    }
}
