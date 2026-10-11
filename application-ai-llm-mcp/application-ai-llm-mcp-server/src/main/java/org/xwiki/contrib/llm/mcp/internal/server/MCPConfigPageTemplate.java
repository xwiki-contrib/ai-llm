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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.extension.InstalledExtension;
import org.xwiki.extension.repository.InstalledExtensionRepository;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.xar.XarEntry;
import org.xwiki.xar.XarException;
import org.xwiki.xar.XarPackage;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Produces the MCP configuration page of a wiki exactly as the extension ships it, read from the XAR package of
 * the installed UI module. A write to an untrusted configuration page starts from this document instead of the
 * stored page, so nothing an untrusted author put on the page (objects, title, content) is kept.
 *
 * @version $Id$
 * @since 0.10.1
 */
@Component(roles = MCPConfigPageTemplate.class)
@Singleton
public class MCPConfigPageTemplate
{
    /** The id of the UI module of this extension: the XAR package that ships the configuration page. */
    static final String UI_EXTENSION_ID = "org.xwiki.contrib.llm:application-ai-llm-mcp-ui";

    /** The configuration page without a wiki, as the XAR package names it. */
    private static final LocalDocumentReference CONFIG_PAGE_NAME =
        new LocalDocumentReference(MCPServerConfiguration.CONFIG_SPACES, MCPServerConfiguration.CONFIG_DOC_NAME);

    /**
     * The configuration page as the XAR package index keys it: with its locale, since the index records every entry
     * with the locale its XML declares and the shipped page declares the root locale.
     */
    private static final LocalDocumentReference CONFIG_PAGE = new LocalDocumentReference(CONFIG_PAGE_NAME, Locale.ROOT);

    @Inject
    private InstalledExtensionRepository installedExtensionRepository;

    @Inject
    private Logger logger;

    /**
     * Reads the configuration page of the given wiki from the XAR package of the installed UI module. The
     * returned document is new ({@link XWikiDocument#isNew()}), so saving it replaces the stored page.
     *
     * @param wikiId the wiki the page belongs to
     * @param context the current context; its wiki is switched to {@code wikiId} while the XML is parsed and
     *     restored afterwards
     * @return the shipped page, or {@code null} when the UI module is not installed, its package does not carry
     *     the page, or the page cannot be read
     * @since 0.10.1
     */
    public XWikiDocument shippedPage(String wikiId, XWikiContext context)
    {
        try {
            InstalledExtension extension = installedUiExtension(wikiId);
            if (extension == null) {
                this.logger.warn("The extension [{}] is not installed; the MCP configuration page of wiki [{}] cannot "
                    + "be read from its package", UI_EXTENSION_ID, wikiId);
                return null;
            }
            byte[] xml = readPageXml(extension);
            if (xml == null) {
                this.logger.warn("The package of extension [{}] does not contain the page [{}]; the MCP configuration "
                    + "page of wiki [{}] cannot be read from it", UI_EXTENSION_ID, CONFIG_PAGE_NAME, wikiId);
                return null;
            }
            XWikiDocument page = parse(xml, wikiId, context);
            if (!isShippedConfigPage(page, wikiId)) {
                this.logger.warn("The page [{}] read from the package of extension [{}] is not the MCP configuration "
                    + "page of wiki [{}]", page.getDocumentReference(), UI_EXTENSION_ID, wikiId);
                return null;
            }
            return page;
        } catch (Exception e) {
            this.logger.warn("Could not read the MCP configuration page of wiki [{}] from the package of extension "
                + "[{}]: [{}]", wikiId, UI_EXTENSION_ID, ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to read the MCP configuration page of wiki [{}] from the extension package",
                wikiId, e);
            return null;
        }
    }

    /**
     * Finds the installed UI module. Its pom installs it on the root namespace
     * ({@code xwiki.extension.namespaces} = {@code {root}}), and the repository falls back to the root namespace
     * when a wiki namespace misses, so a single lookup on the wiki's namespace finds it wherever it is installed.
     *
     * @param wikiId the wiki whose namespace is looked up
     * @return the installed extension, or {@code null} when it is not installed
     */
    private InstalledExtension installedUiExtension(String wikiId)
    {
        return this.installedExtensionRepository.getInstalledExtension(UI_EXTENSION_ID, "wiki:" + wikiId);
    }

    /**
     * Reads the XML of the configuration page out of the extension's XAR package: the package index names the
     * zip entry of the page, and a second pass over the zip reads that entry.
     *
     * @param extension the installed UI module
     * @return the XML of the page, or {@code null} when the package does not contain it
     * @throws IOException when the package cannot be read
     * @throws XarException when the package index cannot be parsed
     */
    private byte[] readPageXml(InstalledExtension extension) throws IOException, XarException
    {
        String entryName;
        try (InputStream stream = extension.getFile().openStream()) {
            XarEntry entry = new XarPackage(stream).getEntry(CONFIG_PAGE);
            if (entry == null) {
                return null;
            }
            entryName = entry.getEntryName();
        }
        try (ZipInputStream zip = new ZipInputStream(extension.getFile().openStream())) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entryName.equals(entry.getName())) {
                    // ZipInputStream reports the end of the current entry as the end of the stream.
                    return zip.readAllBytes();
                }
            }
        }
        return null;
    }

    /**
     * Parses the page XML into a new document of the given wiki. The XML names the page without a wiki and the
     * parser resolves it against the context wiki, so the context is switched to the target wiki for the
     * duration of the parse and restored afterwards.
     *
     * @param xml the page XML
     * @param wikiId the wiki the page belongs to
     * @param context the current context
     * @return the parsed document
     * @throws XWikiException when the XML cannot be parsed
     */
    private XWikiDocument parse(byte[] xml, String wikiId, XWikiContext context) throws XWikiException
    {
        XWikiDocument page = new XWikiDocument(configReference(wikiId));
        String previousWiki = context.getWikiId();
        context.setWikiId(wikiId);
        try {
            page.fromXML(new ByteArrayInputStream(xml), false);
        } finally {
            context.setWikiId(previousWiki);
        }
        return page;
    }

    /**
     * Checks that the parsed document is the configuration page of the wiki, is new and carries the
     * configuration object the extension ships on it.
     *
     * @param page the parsed document
     * @param wikiId the wiki the page must belong to
     * @return whether the document can stand in for the shipped configuration page
     */
    private static boolean isShippedConfigPage(XWikiDocument page, String wikiId)
    {
        DocumentReference classReference = new DocumentReference(wikiId, MCPServerConfiguration.CONFIG_SPACES,
            MCPServerConfiguration.CONFIG_CLASS_NAME);
        return page.isNew() && configReference(wikiId).equals(page.getDocumentReference())
            && page.getXObject(classReference) != null;
    }

    private static DocumentReference configReference(String wikiId)
    {
        return new DocumentReference(wikiId, MCPServerConfiguration.CONFIG_SPACES,
            MCPServerConfiguration.CONFIG_DOC_NAME);
    }
}
