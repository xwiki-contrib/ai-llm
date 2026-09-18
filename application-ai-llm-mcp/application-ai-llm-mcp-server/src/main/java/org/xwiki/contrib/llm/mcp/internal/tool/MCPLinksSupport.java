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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.contrib.llm.mcp.internal.access.MCPRowQuery;
import org.xwiki.link.LinkException;
import org.xwiki.link.LinkStore;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.PageReference;
import org.xwiki.query.QueryException;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Link-side plumbing of the {@code get_links} tool: the backlink pipeline (Solr link index read,
 * locale-stripping dedupe, reach filter, deterministic sort, scan ceiling, per-reference authorization
 * and the batched hidden/stale filter), the live outgoing-link extraction with its PAGE-family
 * conversion, and the document loads. A separate component so the tool composes responses while this
 * class owns the link store, the row-query door and the reference machinery.
 *
 * <p>The backlink index answers RAW hits: farm-wide, without any rights check or hidden filter, one
 * entry per translation of a linking page. Everything this class returns from
 * {@link #backlinks(DocumentReference, boolean)} has already been reduced to what the current user may
 * see on this endpoint, in the fixed order reach filter first (an out-of-reach wiki's reference is
 * dropped before any authorization or existence probe can observe it), then the scan ceiling on the
 * sorted set, then the space filter plus view right per reference, then one batched query per wiki
 * dropping stale (no longer existing) documents and flagging hidden ones - counted but not listed by
 * default, listed with a marker when hidden pages are requested.</p>
 *
 * @version $Id$
 * @since 0.10
 */
@Component(roles = MCPLinksSupport.class)
@Singleton
public class MCPLinksSupport
{
    /**
     * Batched visibility statement of the backlink filter: the hidden flag of every surviving document
     * of one wiki, keyed by local full name. A name absent from the answer is a stale index entry (the
     * document no longer exists) and its reference is dropped.
     */
    private static final String VISIBILITY_QUERY =
        "select doc.fullName, doc.hidden from XWikiDocument doc"
            + " where doc.translation = 0 and doc.fullName in (:names)";

    private static final String NAMES_BIND = "names";

    /**
     * How many names one {@link #VISIBILITY_QUERY} execution binds into its {@code in (:names)} list.
     * The scan ceiling allows up to {@link MCPRowQuery#MAX_FETCH_PER_QUERY} surviving names per wiki,
     * but databases cap the size of an SQL expression list (Oracle at 1000), so the names are chunked
     * well below that cap and the per-chunk answers merged.
     */
    private static final int NAMES_CHUNK_SIZE = 500;

    private static final String NEW_LINE = "\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    /**
     * The agent-facing message prefix shared by the existence-check and load failures, mirroring the
     * {@code get_document}/{@code get_history} wording so a broken document reads the same through
     * every read tool.
     */
    private static final String COULD_NOT_READ_PREFIX = "Could not read the document ";

    /**
     * The heading of the outgoing-links block, shared by its populated and empty forms.
     */
    private static final String OUTGOING_HEADING = "Outgoing links: ";

    /**
     * Marks a hidden document's row when hidden pages are listed, so an agent can tell which links a
     * reader browsing with the default preference never sees.
     */
    private static final String HIDDEN_MARKER = " (hidden)";

    @Inject
    private Logger logger;

    @Inject
    private LinkStore linkStore;

    @Inject
    private MCPRowQuery rowQuery;

    @Inject
    private MCPWikiReach wikiReach;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private DocumentReferenceResolver<EntityReference> documentResolver;

    @Inject
    private EntityReferenceResolver<EntityReference> referenceConverter;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<PageReference> currentDocumentResolver;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private MCPTranslationSupport translationSupport;

    /**
     * @param reference a resolved document reference
     * @return its full serialized form, neutralized for the line grammar (the header form of the tool)
     */
    public String canonical(DocumentReference reference)
    {
        return MCPToolSupport.stripLineBreaks(this.serializer.serialize(reference));
    }

    /**
     * Probes the existence of a document, degrading a broken store to the shared agent-facing message.
     *
     * @param reference the resolved (possibly locale-carrying) document reference
     * @param rawReference the original reference string, for the error message
     * @return whether the document exists
     * @throws IllegalArgumentException with the agent-facing message when the probe fails
     */
    public boolean documentExists(DocumentReference reference, String rawReference)
    {
        try {
            return this.documentAccessBridge.exists(reference);
        } catch (Exception e) {
            this.logger.warn("MCP get_links tool failed to check existence of [{}]: [{}]", rawReference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_links tool existence-check failure details", e);
            throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
                + MCPTextGuards.fragment(rawReference) + QUOTE + PERIOD);
        }
    }

    /**
     * Runs the whole backlink pipeline for one target: reads the link index, collapses the raw hits to
     * locale-free document references (the index carries one entry per translation of a linking page),
     * drops every reference whose wiki is out of this endpoint's reach BEFORE anything else can observe
     * it, sorts by full serialized reference so the scan ceiling and the paging are deterministic, scans
     * at most {@link MCPRowQuery#MAX_FETCH_PER_QUERY} references, keeps the ones passing the space
     * filter and the view right, and finally drops stale index entries and flags hidden documents
     * through one batched query per wiki. A hidden document is dropped-but-counted by default, or
     * listed at its sorted position with the {@code (hidden)} marker when hidden pages are requested.
     * The returned rows are display-ready: local names for the endpoint's own wiki, wiki-prefixed
     * names elsewhere, each passed through the shared fragment guard.
     *
     * @param target the locale-free document reference whose backlinks are read
     * @param showHidden whether hidden documents are listed (marked) instead of only counted
     * @return the authorized display rows in their sorted order, the count of authorized-but-hidden
     *     backlinks, and whether the raw set exceeded the scan ceiling (the row count is then a floor)
     * @throws LinkException when the link index read or the batched visibility check fails
     */
    public BacklinkPage backlinks(DocumentReference target, boolean showHidden) throws LinkException
    {
        Set<EntityReference> raw = this.linkStore.resolveBackLinkedEntities(target);
        Set<DocumentReference> unique = new HashSet<>();
        for (EntityReference entityReference : raw) {
            DocumentReference resolved = this.documentResolver.resolve(entityReference);
            unique.add(new DocumentReference(resolved, (Locale) null));
        }
        // Decorate-sort-undecorate: each reference is serialized once into its sort key instead of on
        // every comparison, so a large raw set costs a linear number of serializations.
        List<DocumentReference> reachable = new ArrayList<>();
        Map<DocumentReference, String> sortKeys = new HashMap<>();
        for (DocumentReference reference : unique) {
            if (this.wikiReach.canReachWiki(reference.getWikiReference().getName())) {
                reachable.add(reference);
                sortKeys.put(reference, this.serializer.serialize(reference));
            }
        }
        reachable.sort(Comparator.comparing(sortKeys::get));
        boolean capped = reachable.size() > MCPRowQuery.MAX_FETCH_PER_QUERY;
        List<DocumentReference> scanned =
            capped ? reachable.subList(0, MCPRowQuery.MAX_FETCH_PER_QUERY) : reachable;
        List<DocumentReference> authorized = new ArrayList<>();
        for (DocumentReference reference : scanned) {
            if (this.rowQuery.isAuthorized(reference)) {
                authorized.add(reference);
            }
        }
        List<String> rows = new ArrayList<>();
        int hiddenCount = 0;
        for (FlaggedReference entry : visibilityOf(authorized)) {
            if (!entry.hidden()) {
                rows.add(display(entry.reference()));
            } else {
                hiddenCount++;
                if (showHidden) {
                    rows.add(display(entry.reference()) + HIDDEN_MARKER);
                }
            }
        }
        return new BacklinkPage(rows, hiddenCount, capped);
    }

    /**
     * Routes a {@code locale} argument to the addressed translation for an outgoing-link read through
     * the shared resolver ({@link MCPTranslationSupport}), with the exact-match semantics of
     * {@code get_document}/{@code get_history}: a locale naming the default language answers the
     * locale-free reference, a stored translation answers the locale-carrying reference, and a missing
     * translation is refused with the translations that do exist and the default language.
     *
     * @param reference the resolved locale-free document reference
     * @param locale the validated {@code locale} argument
     * @param rawReference the original reference string, for error messages
     * @return the reference to read: {@code reference} itself for the default language, or the
     *     locale-carrying reference of the stored translation
     * @throws IllegalArgumentException with the agent-facing message when the translation does not
     *     exist or a load fails
     */
    public DocumentReference resolveTranslation(DocumentReference reference, Locale locale, String rawReference)
    {
        XWikiDocument document = loadDocument(reference, rawReference);
        return this.translationSupport.resolve(reference, document, locale, rawReference).reference();
    }

    /**
     * Renders the outgoing-links block of one document row: the document's unique static entity
     * references, extracted live from its stored content and its objects' wiki-content fields, with
     * PAGE-family references converted to their document-based form (mirroring the platform link
     * store's own conversion), bucketed into linked documents and linked attachments, deduplicated and
     * sorted. Targets are part of the source content the caller is authorized to view, so they are
     * listed as authored: no rights or reach filtering, and their existence is not probed.
     *
     * @param target the resolved (possibly locale-carrying) document reference to read
     * @param rawReference the original reference string, for error messages
     * @return the rendered block: the counting heading, then the non-empty {@code Documents:} and
     *     {@code Attachments:} subsections
     * @throws IllegalArgumentException with the agent-facing message when the load or the extraction
     *     fails
     */
    public String outgoingBlock(DocumentReference target, String rawReference)
    {
        XWikiDocument document = loadDocument(target, rawReference);
        Set<EntityReference> links = document.getUniqueLinkedEntities(this.contextProvider.get());
        if (links == null) {
            throw new IllegalArgumentException("Could not extract the links of " + QUOTE
                + MCPTextGuards.fragment(rawReference) + QUOTE + PERIOD);
        }
        Set<String> documents = new TreeSet<>();
        Set<String> attachments = new TreeSet<>();
        for (EntityReference link : links) {
            EntityReference converted = toDocumentBasedReference(link);
            if (converted.getType() == EntityType.ATTACHMENT) {
                attachments.add(display(converted));
            } else {
                documents.add(display(converted));
            }
        }
        return renderOutgoingBlock(documents, attachments);
    }

    /**
     * Resolves the visibility of an authorized reference list, dropping the stale index entries, with
     * batched queries per wiki: the references are grouped by wiki, each group's local full names are
     * bound into {@link #VISIBILITY_QUERY} in chunks, and each reference is then routed by its
     * answered hidden flag. A name absent from the answer is a stale entry (the document no longer
     * exists) and is dropped uncounted; the surviving references keep their per-wiki-group order and
     * carry their hidden flag, so the caller decides whether a hidden document is listed or only
     * counted. Only already-authorized references reach this method, so no flag can disclose denied
     * content.
     *
     * @param references the authorized references, at most {@link MCPRowQuery#MAX_FETCH_PER_QUERY}
     * @return the existing references with their hidden flags, in their per-wiki-group order
     * @throws LinkException when a batched query fails: the safe backlink set cannot be produced, so
     *     the failure surfaces on the single backlink failure channel
     */
    private List<FlaggedReference> visibilityOf(List<DocumentReference> references) throws LinkException
    {
        Map<String, List<DocumentReference>> byWiki = new LinkedHashMap<>();
        for (DocumentReference reference : references) {
            byWiki.computeIfAbsent(reference.getWikiReference().getName(), key -> new ArrayList<>())
                .add(reference);
        }
        List<FlaggedReference> flagged = new ArrayList<>();
        for (Map.Entry<String, List<DocumentReference>> entry : byWiki.entrySet()) {
            Map<String, Boolean> hiddenFlags = hiddenFlagsOf(entry.getKey(), entry.getValue());
            for (DocumentReference reference : entry.getValue()) {
                Boolean hidden = hiddenFlags.get(this.localSerializer.serialize(reference));
                if (hidden != null) {
                    flagged.add(new FlaggedReference(reference, hidden));
                }
            }
        }
        return flagged;
    }

    /**
     * Runs one wiki's batched visibility queries and collects each existing document's hidden flag,
     * keyed by local full name. The names are bound in chunks of {@link #NAMES_CHUNK_SIZE} and the
     * answers merged, so the bound list stays below the database expression-list caps. The flags are
     * normalized to {@link Boolean#TRUE}/{@link Boolean#FALSE}, so an absent key always means a stale
     * entry (a database answering a null hidden column cannot masquerade as one).
     *
     * @param wiki the wiki id
     * @param references the wiki's authorized references
     * @return the hidden flag of each existing document, keyed by local full name
     * @throws LinkException when a query fails
     */
    private Map<String, Boolean> hiddenFlagsOf(String wiki, List<DocumentReference> references)
        throws LinkException
    {
        List<String> names = new ArrayList<>(references.size());
        for (DocumentReference reference : references) {
            names.add(this.localSerializer.serialize(reference));
        }
        Map<String, Boolean> hiddenFlags = new HashMap<>(names.size());
        for (int start = 0; start < names.size(); start += NAMES_CHUNK_SIZE) {
            List<String> chunk = names.subList(start, Math.min(start + NAMES_CHUNK_SIZE, names.size()));
            try {
                for (Object[] columns : this.rowQuery.rows(VISIBILITY_QUERY, wiki, NAMES_BIND, chunk,
                    MCPRowQuery.MAX_FETCH_PER_QUERY)) {
                    hiddenFlags.put((String) columns[0], Boolean.TRUE.equals(columns[1]));
                }
            } catch (QueryException e) {
                throw new LinkException(
                    "Failed to check the visibility of the backlink documents of wiki [" + wiki + "]", e);
            }
        }
        return hiddenFlags;
    }

    /**
     * Renders one entity reference for a response row: the local serialized form when the reference is
     * in the endpoint's own (context) wiki, the wiki-prefixed full form elsewhere, passed through the
     * shared fragment guard so a crafted page name can neither forge a row nor reorder how it displays.
     *
     * @param reference the reference to render
     * @return the guarded display form
     */
    private String display(EntityReference reference)
    {
        EntityReference wiki = reference.extractReference(EntityType.WIKI);
        boolean sameWiki = wiki != null && wiki.getName().equals(this.contextProvider.get().getWikiId());
        return MCPTextGuards.fragment(
            sameWiki ? this.localSerializer.serialize(reference) : this.serializer.serialize(reference));
    }

    /**
     * Converts a PAGE-family reference to its document-based form, replicating the platform link
     * store's conversion with the same resolver components: a document-based reference passes through
     * unchanged, a PAGE reference resolves to the document it currently designates, and a
     * PAGE_ATTACHMENT reference becomes an ATTACHMENT reference under that resolved document. These are
     * the only PAGE-family types the outgoing extraction emits, so no other conversion exists here.
     *
     * @param entityReference the extracted reference
     * @return the document-based reference
     */
    private EntityReference toDocumentBasedReference(EntityReference entityReference)
    {
        EntityReference pageReference = entityReference.extractReference(EntityType.PAGE);
        if (pageReference == null) {
            return entityReference;
        }
        PageReference page = pageReference instanceof PageReference typed
            ? typed : new PageReference(pageReference);
        DocumentReference documentReference = this.currentDocumentResolver.resolve(page);
        if (entityReference.getType() == EntityType.PAGE) {
            return documentReference;
        }
        EntityReference documentBased = this.referenceConverter.resolve(entityReference, EntityType.ATTACHMENT);
        return documentBased.replaceParent(documentBased.extractReference(EntityType.DOCUMENT),
            documentReference);
    }

    /**
     * Renders the outgoing block from the sorted display buckets: the counting heading and the
     * non-empty subsections, or the fixed empty-form sentence.
     *
     * @param documents the sorted linked-document rows
     * @param attachments the sorted linked-attachment rows
     * @return the rendered block
     */
    private static String renderOutgoingBlock(Set<String> documents, Set<String> attachments)
    {
        if (documents.isEmpty() && attachments.isEmpty()) {
            return OUTGOING_HEADING + "none. Only static links are extracted: links generated by macros "
                + "at render time never appear here.";
        }
        StringBuilder block = new StringBuilder(OUTGOING_HEADING).append(documents.size())
            .append(" linked documents, ").append(attachments.size())
            .append(" linked attachments (static links, listed as authored: not rights-filtered, "
                + "existence not checked)");
        if (!documents.isEmpty()) {
            block.append(NEW_LINE).append("Documents:").append(NEW_LINE)
                .append(String.join(NEW_LINE, documents));
        }
        if (!attachments.isEmpty()) {
            block.append(NEW_LINE).append("Attachments:").append(NEW_LINE)
                .append(String.join(NEW_LINE, attachments));
        }
        return block.toString();
    }

    /**
     * Loads a document row as its oldcore instance, which owns the link extraction.
     *
     * @param reference the resolved (possibly locale-carrying) document reference
     * @param rawReference the original reference string, for error messages
     * @return the loaded document
     * @throws IllegalArgumentException with the agent-facing message when the load fails
     */
    private XWikiDocument loadDocument(DocumentReference reference, String rawReference)
    {
        Object document = null;
        try {
            document = this.documentAccessBridge.getDocumentInstance(reference);
        } catch (Exception e) {
            this.logger.warn("MCP get_links tool failed to load [{}]: [{}]", rawReference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_links tool load failure details", e);
        }
        if (document instanceof XWikiDocument xdoc) {
            return xdoc;
        }
        throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
            + MCPTextGuards.fragment(rawReference) + QUOTE + PERIOD);
    }

    /**
     * One authorized page of the backlink pipeline: the display-ready rows in their deterministic
     * order, the count of authorized-but-hidden backlinks (dropped from the rows by default, listed
     * marked when hidden pages are requested), and whether the raw index answer exceeded the scan
     * ceiling (the row count is then a floor, rendered as {@code N+}).
     *
     * @param rows the authorized display rows, sorted
     * @param hiddenCount how many authorized backlinks are hidden documents
     * @param capped whether the raw reference set exceeded the scan ceiling
     * @version $Id$
     */
    public record BacklinkPage(List<String> rows, int hiddenCount, boolean capped)
    {
    }

    /**
     * One surviving reference of the batched visibility check with its hidden flag (stale entries are
     * already dropped).
     *
     * @param reference the existing, authorized reference
     * @param hidden whether the document is hidden
     * @version $Id$
     */
    private record FlaggedReference(DocumentReference reference, boolean hidden)
    {
    }
}
