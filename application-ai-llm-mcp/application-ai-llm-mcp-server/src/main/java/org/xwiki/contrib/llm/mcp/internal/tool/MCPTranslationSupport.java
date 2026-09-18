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

import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Translation-addressing plumbing shared by the read tools ({@code get_document}, {@code get_history},
 * {@code get_links}): routes a requested locale to the addressed language row with exact-match
 * semantics. A locale naming the default language answers the default row itself, a stored translation
 * answers the locale-carrying reference with the reloaded translation row, and a missing translation is
 * refused listing the translations that do exist and the document's default language. One component
 * owns the existence probe, the reload and the refusal grammar so the three tools cannot drift apart on
 * any of them.
 *
 * <p>This component performs NO authorization: {@link #resolve} expects the ALREADY-AUTHORIZED
 * locale-free document reference. A document's translations share its rights and the security cache is
 * keyed on parameter-free references, so the locale attaches to loads only - callers authorize the
 * locale-free reference before resolving a translation.</p>
 *
 * <p>The default-language routing delegates to
 * {@link MCPWriteSupport#isDefaultLanguageRequest(XWikiContext, XWikiDocument, Locale)}, the predicate
 * the write tools use, so reads and writes route a {@code locale} argument identically.</p>
 *
 * @version $Id$
 * @since 0.10
 */
@Component(roles = MCPTranslationSupport.class)
@Singleton
public class MCPTranslationSupport
{
    /**
     * Separator of the comma-joined translation-locale lists.
     */
    private static final String LIST_SEPARATOR = ", ";

    /**
     * Prefix of the translation list of the missing-translation refusal.
     */
    private static final String TRANSLATIONS_PREFIX = "Translations: ";

    /**
     * The agent-facing message prefix of a failed existence probe or load, mirroring the read tools'
     * own wording so a broken document reads the same through every path.
     */
    private static final String COULD_NOT_READ_PREFIX = "Could not read the document ";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    @Inject
    private Logger logger;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * Routes a requested locale to the language row it addresses, exact-match: the default row for a
     * locale naming the default language (the default row stores an empty language, so a
     * per-translation probe for e.g. {@code "en"} on an English-default page would falsely refuse), or
     * the reloaded translation row of a stored translation. A missing translation is refused with the
     * miss message. The reference must already be authorized; this method authorizes nothing.
     *
     * @param reference the already-authorized, locale-free document reference
     * @param defaultDocument the loaded default document
     * @param locale the requested locale
     * @param rawReference the original reference string from the tool call, for error messages
     * @return the addressed row: the reference to use, the loaded document, and the translation locale
     *     ({@code null} for the default row)
     * @throws IllegalArgumentException with the agent-facing message when the translation does not
     *     exist or a probe or load fails
     */
    public TranslationTarget resolve(DocumentReference reference, DocumentModelBridge defaultDocument,
        Locale locale, String rawReference)
    {
        if (defaultDocument instanceof XWikiDocument xdoc
            && MCPWriteSupport.isDefaultLanguageRequest(this.contextProvider.get(), xdoc, locale)) {
            return new TranslationTarget(reference, xdoc, null);
        }
        DocumentReference localizedRef = new DocumentReference(reference, locale);
        if (!documentExists(localizedRef, rawReference)) {
            throw new IllegalArgumentException(missingTranslationMessage(reference, defaultDocument, locale));
        }
        return new TranslationTarget(localizedRef, loadDocument(localizedRef, rawReference), locale);
    }

    /**
     * Lists the document's translation locales (the platform list excludes the default language),
     * degrading to an empty list on a lookup failure so a discovery nicety can never break a read.
     *
     * @param document the loaded document
     * @return the translation locales, or an empty list
     */
    public List<Locale> translationLocalesOf(DocumentModelBridge document)
    {
        if (!(document instanceof XWikiDocument xdoc)) {
            return List.of();
        }
        try {
            List<Locale> translations = xdoc.getTranslationLocales(this.contextProvider.get());
            return translations != null ? translations : List.of();
        } catch (Exception e) {
            this.logger.debug("MCP translation-locale lookup failed for [{}]",
                document.getDocumentReference(), e);
            return List.of();
        }
    }

    /**
     * Resolves the page's EFFECTIVE default language: the declared default locale, or the wiki's
     * default when the row's is ROOT (undeclared - programmatic creation skips the UI's stamp). The
     * wiki fallback matches {@link MCPWriteSupport#isDefaultLanguageRequest}: whatever this method
     * names is exactly what an omitted or equal {@code locale} routes to. Never fall back to the
     * row's real locale - on a translation row (whose default locale is always ROOT) that would
     * name the translation's own language as the page default.
     *
     * @param document the loaded document
     * @return the effective default locale, or {@code null} when the document does not expose its
     *     locale
     */
    public Locale defaultLocaleOf(DocumentModelBridge document)
    {
        if (!(document instanceof XWikiDocument xdoc)) {
            return null;
        }
        Locale defaultLocale = xdoc.getDefaultLocale();
        if (defaultLocale == null || Locale.ROOT.equals(defaultLocale)) {
            XWikiContext xcontext = this.contextProvider.get();
            return xcontext.getWiki().getDefaultLocale(xcontext);
        }
        return defaultLocale;
    }

    /**
     * @param document the loaded document
     * @return the display form of the page's effective default language, stripped of line breaks, or
     *     {@code null} when the document does not expose its locale, so the caller can drop the
     *     mention instead of printing a dangling empty value
     */
    public String defaultLocaleDisplay(DocumentModelBridge document)
    {
        Locale defaultLocale = defaultLocaleOf(document);
        if (defaultLocale == null || defaultLocale.toString().isEmpty()) {
            return null;
        }
        return MCPToolSupport.stripLineBreaks(defaultLocale.toString());
    }

    /**
     * Joins the locale identifiers with the list separator, stripping line breaks from each: a stored
     * locale can carry them in its variant segment and must not forge extra response lines.
     *
     * @param locales the locales to list
     * @return the comma-joined locale identifiers
     */
    static String joinLocales(List<Locale> locales)
    {
        return String.join(LIST_SEPARATOR,
            locales.stream().map(locale -> MCPToolSupport.stripLineBreaks(locale.toString())).toList());
    }

    /**
     * Builds the missing-translation refusal, listing the translations that do exist and the default
     * language so the agent can correct the call instead of retrying blindly.
     *
     * @param reference the resolved locale-free document reference
     * @param defaultDocument the loaded default document
     * @param locale the requested locale that has no stored translation
     * @return the agent-facing error message
     */
    private String missingTranslationMessage(DocumentReference reference, DocumentModelBridge defaultDocument,
        Locale locale)
    {
        String canonicalRef = MCPToolSupport.stripLineBreaks(this.serializer.serialize(reference));
        List<Locale> translations = translationLocalesOf(defaultDocument);
        String existing = translations.isEmpty() ? "This document has no translations."
            : TRANSLATIONS_PREFIX + joinLocales(translations) + PERIOD;
        // The requested locale is echoed stripped: a validated Locale can still carry line breaks in
        // its variant segment, which would otherwise forge extra response lines.
        String message = "Error: no " + QUOTE + MCPToolSupport.stripLineBreaks(locale.toString()) + QUOTE
            + " translation of " + QUOTE + canonicalRef + QUOTE + PERIOD + ' ' + existing;
        String defaultDisplay = defaultLocaleDisplay(defaultDocument);
        if (defaultDisplay != null) {
            message += " The default language is " + defaultDisplay + PERIOD;
        }
        return message + " Omit 'locale' for the default version.";
    }

    /**
     * Probes the existence of a language row, degrading a broken store to the shared agent-facing
     * message.
     *
     * @param reference the locale-carrying document reference to probe
     * @param rawReference the original reference string, for the error message
     * @return whether the row exists
     * @throws IllegalArgumentException with the agent-facing message when the probe fails
     */
    private boolean documentExists(DocumentReference reference, String rawReference)
    {
        try {
            return this.documentAccessBridge.exists(reference);
        } catch (Exception e) {
            this.logger.warn("MCP translation existence probe failed for [{}]: [{}]", rawReference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP translation existence probe failure details", e);
            throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
                + MCPTextGuards.fragment(rawReference) + QUOTE + PERIOD);
        }
    }

    /**
     * Loads a translation row as its oldcore instance.
     *
     * @param reference the locale-carrying document reference
     * @param rawReference the original reference string, for error messages
     * @return the loaded row
     * @throws IllegalArgumentException with the agent-facing message when the load fails
     */
    private XWikiDocument loadDocument(DocumentReference reference, String rawReference)
    {
        Object document = null;
        try {
            document = this.documentAccessBridge.getDocumentInstance(reference);
        } catch (Exception e) {
            this.logger.warn("MCP translation load failed for [{}]: [{}]", rawReference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP translation load failure details", e);
        }
        if (document instanceof XWikiDocument xdoc) {
            return xdoc;
        }
        throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
            + MCPTextGuards.fragment(rawReference) + QUOTE + PERIOD);
    }

    /**
     * The language row a {@code locale} argument addresses, resolved by
     * {@link #resolve(DocumentReference, DocumentModelBridge, Locale, String)}.
     *
     * @param reference the reference to read: locale-free for the default row, locale-carrying for a
     *     translation
     * @param document the loaded row: the default document itself for the default row, the reloaded
     *     translation row otherwise
     * @param locale the translation locale in play, or {@code null} for the default row
     * @version $Id$
     */
    public record TranslationTarget(DocumentReference reference, XWikiDocument document, Locale locale)
    {
    }
}
