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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.localization.LocaleUtils;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryFilter;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWikiContext;

/**
 * Helper class for the {@link XWikiDocumentStore}.
 *
 * @version $Id$
 */
@Component(roles = XWikiDocumentStoreHelper.class)
@Singleton
public class XWikiDocumentStoreHelper
{
    /**
     * The condition excluding hidden documents, in the form the platform's {@code hidden/document} query filter
     * inserts: the column is not nullable, the null check is belt and braces.
     */
    static final String HIDDEN_CONDITION = "(doc.hidden <> true OR doc.hidden IS NULL)";

    private static final String CLOSING_PARENTHESIS = ")";

    /**
     * The separator in front of the locale in a document id, as the "withparameters" symbol scheme writes it.
     */
    private static final char PARAMETER_SEPARATOR = ';';

    /**
     * The escape symbol of the reference syntax.
     */
    private static final char ESCAPE = '\\';

    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    private EntityReferenceResolver<String> entityReferenceResolver;

    @Inject
    @Named("withparameters")
    private EntityReferenceSerializer<String> withParametersEntityReferenceSerializer;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("language")
    private QueryFilter languageFilter;

    @Inject
    @Named("count")
    private QueryFilter countFilter;

    @Inject
    private SpaceReferenceResolver<String> spaceReferenceResolver;

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * Resolve the given list of space references as strings taking the given collection reference as base. Filter
     * out any spaces that are in other wikis unless the wiki of the collection is the main wiki. This is for
     * security reasons to not allow admins of subwikis to define collections that contain documents they cannot access.
     *
     * @param spaces the spaces to resolve
     * @param collectionReference the reference of the collection that should be used as base
     * @return the list of resolved space references
     */
    public List<SpaceReference> resolveSpaceReferences(List<String> spaces, DocumentReference collectionReference)
    {
        Stream<SpaceReference> spaceReferenceStream = spaces.stream()
            .map(stringReference ->
                this.spaceReferenceResolver.resolve(stringReference, collectionReference));

        // Only collections in the main wiki can contain spaces from other wikis.
        String mainWiki = this.contextProvider.get().getMainXWiki();
        WikiReference collectionWiki = collectionReference.getWikiReference();
        if (!mainWiki.equals(collectionWiki.getName())) {
            spaceReferenceStream = spaceReferenceStream.filter(spaceReference ->
                collectionWiki.equals(spaceReference.getWikiReference()));
        }
        return spaceReferenceStream.toList();
    }

    /**
     * Resolve a document id of this store back to the page reference it was serialized from. The id is the
     * "withparameters" serialization of a page reference with its locale; the locale part is split off here because
     * the platform resolver misreads a name whose last character is an escaped backslash in front of the parameter
     * separator. An id without the locale part is not an id of this store.
     *
     * @param documentId the document id
     * @return the page reference, with its locale, or {@code null} when the id is not the exact id of a page
     * @since 0.11
     */
    public DocumentReference resolveDocumentId(String documentId)
    {
        int separatorIndex = findLocaleSeparator(documentId);
        if (separatorIndex < 0) {
            return null;
        }

        try {
            EntityReference pageReference = this.entityReferenceResolver.resolve(
                documentId.substring(0, separatorIndex), EntityType.DOCUMENT);
            Locale locale = LocaleUtils.toLocale(documentId.substring(separatorIndex + 1));
            DocumentReference documentReference = new DocumentReference(pageReference, locale);

            // The resolver fills missing parts with default values and the id may contain parameters other than
            // the locale: the id is accepted only when it is exactly the id of the resolved page in that locale.
            if (documentId.equals(this.withParametersEntityReferenceSerializer.serialize(documentReference))) {
                return documentReference;
            }
        } catch (IllegalArgumentException e) {
            // The locale part is not a locale or the reference cannot be parsed: the id is not an id of this store.
        }
        return null;
    }

    /**
     * @param documentId the document id, may be {@code null}
     * @return the index of the last parameter separator that is not escaped, i.e. preceded by an even number of
     *     escape symbols, or {@code -1} when the id has no such separator
     */
    private static int findLocaleSeparator(String documentId)
    {
        if (documentId == null) {
            return -1;
        }
        for (int i = documentId.length() - 1; i >= 0; --i) {
            if (documentId.charAt(i) == PARAMETER_SEPARATOR && countEscapesBefore(documentId, i) % 2 == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int countEscapesBefore(String documentId, int index)
    {
        int count = 0;
        for (int i = index - 1; i >= 0 && documentId.charAt(i) == ESCAPE; --i) {
            ++count;
        }
        return count;
    }

    /**
     * Get a list of the documents in the given spaces. The spaces can on several wikis.
     *
     * @param spaces the list of spaces
     * @param offset the offset of the first document to return
     * @param limit the maximum number of documents to return
     * @return the list of documents
     * @throws IndexException if an error occurs
     */
    public List<DocumentReference> getDocuments(List<SpaceReference> spaces, int offset, int limit)
        throws IndexException
    {
        if (spaces.isEmpty()) {
            return List.of();
        }

        Map<WikiReference, List<SpaceReference>> spacesPerWiki = spaces.stream()
            .collect(Collectors.groupingBy(SpaceReference::getWikiReference));

        ArrayList<DocumentReference> result = new ArrayList<>();
        // The number of items we skipped so far before reaching the offset.
        int numSkipped = 0;
        // The total number of items found so far.
        int numFound = 0;
        for (var iterator = spacesPerWiki.entrySet().iterator();
            iterator.hasNext() && (limit == -1 || numFound < limit);) {

            var entry = iterator.next();
            // Adjusted offset: the offset is decreased by the number of items we skipped so far from other wikis.
            int adjustedOffset = offset - numSkipped;
            // Adjusted limit: the limit is decreased by the number of items we found already in previous iterations of
            // the loop.
            int adjustedLimit = limit < 0 ? limit : limit - numFound;

            // Increase the number of items found so far by the number of items we will find in this iteration. As
            // this information is used only in the next iteration, there is no need to update it if the iteration is
            // the last one. This also simplifies the code for the likely case that there is a single iteration.
            if ((limit > -1 || offset > numSkipped) && iterator.hasNext()) {
                int count = countDocumentsFromWiki(entry.getKey(), entry.getValue());
                if (numSkipped + count <= offset) {
                    // In this iteration, we can't get above the offset -> continue with the next wiki.
                    numSkipped += count;
                    continue;
                } else {
                    // We have skipped enough.
                    numSkipped = offset;
                    // We will find up to count items starting with the adjusted offset.
                    numFound += count - adjustedOffset;
                }
            }

            result.addAll(getDocumentsFromWiki(entry.getKey(), entry.getValue(), adjustedOffset, adjustedLimit));
        }

        return result;

    }

    /**
     * Get the names of the collections the given document is part of.
     *
     * @param documentReference the reference of the document to get the collections for
     * @param wiki the wiki in which the collections should be found
     * @return the list of collections the document is part of
     */
    public List<String> getCollections(DocumentReference documentReference, WikiReference wiki) throws IndexException
    {
        // Check for all spaces of the document if they are referenced in the collection definition.
        List<String> spaces = new ArrayList<>();
        for (EntityReference currentReference = documentReference.getLastSpaceReference();
            currentReference != null && currentReference.getType() == EntityType.SPACE;
            currentReference = currentReference.getParent()) {
            SpaceReference spaceReference = new SpaceReference(currentReference);
            if (wiki.equals(spaceReference.getWikiReference())) {
                // Local space - don't expect a wiki name.
                spaces.add(this.localEntityReferenceSerializer.serialize(spaceReference));
            } else {
                // Space in a different wiki - needs a wiki name.
                spaces.add(this.entityReferenceSerializer.serialize(spaceReference));
            }
        }

        String spaceQuery =
            IntStream.range(0, spaces.size())
                .mapToObj(i -> "item=:space" + i)
                .collect(Collectors.joining(" or "));

        try {
            Query query = this.queryManager.createQuery(
                ", BaseObject as obj, DBStringListProperty as prop join prop.list item "
                    + "where obj.className='" + Collection.XCLASS_FULLNAME + "' and obj.name=doc.fullName "
                    + "and obj.id=prop.id.id and prop.id.name='" + DefaultCollection.DOCUMENT_SPACE_FIELDNAME + "' "
                    + "and (" + spaceQuery + CLOSING_PARENTHESIS,
                Query.HQL);
            for (int i = 0; i < spaces.size(); ++i) {
                query.bindValue("space" + i, spaces.get(i));
            }
            query.setWiki(wiki.getName());

            List<String> queryResult = query.execute();

            // Get the collection name out of the returned document references. The collection name is the name of the
            // space reference. Only the page of a collection is a collection: a collections object on any other page,
            // for instance on a copy of the collection page, is ignored. A page is returned once per matching space.
            return queryResult.stream()
                .map(reference -> this.documentReferenceResolver.resolve(reference, wiki))
                .filter(reference -> reference.equals(DefaultCollection.getDocumentReference(wiki.getName(),
                    reference.getLastSpaceReference().getName())))
                .map(DocumentReference::getLastSpaceReference)
                .map(SpaceReference::getName)
                .distinct()
                .toList();
        } catch (QueryException e) {
            throw new IndexException("Failed to get collections for document [%s]".formatted(documentReference), e);
        }
    }

    /**
     * Count the number of non-hidden documents in the given spaces.
     *
     * @param wiki the wiki of the spaces
     * @param wikiSpaces the spaces
     * @return the number of documents
     * @throws IndexException if the query fails
     */
    public int countDocumentsFromWiki(WikiReference wiki, List<SpaceReference> wikiSpaces) throws IndexException
    {
        Query query = buildQuery(wiki, wikiSpaces);
        query.addFilter(this.countFilter);

        try {
            // The count filter returns the count as a Long.
            return ((Number) query.execute().get(0)).intValue();
        } catch (Exception e) {
            throw new IndexException("Failed to count documents with query [%s]".formatted(query.getStatement()), e);
        }
    }

    /**
     * Get the list of documents in the given spaces that must be part of the given wiki.
     *
     * @param wiki the wiki of the spaces
     * @param wikiSpaces the list of spaces
     * @param offset the offset of the first document to return
     * @param limit the number of documents to return
     * @return the references of the found documents
     * @throws IndexException if the query fails
     */
    public List<DocumentReference> getDocumentsFromWiki(WikiReference wiki, List<SpaceReference> wikiSpaces, int offset,
        int limit)
        throws IndexException
    {
        Query query = buildQuery(wiki, wikiSpaces);
        query.setOffset(offset);
        if (limit > -1) {
            query.setLimit(limit);
        }

        try {
            List<Object[]> result = query.execute();
            return getDocumentReferences(wiki, result).toList();
        } catch (Exception e) {
            throw new IndexException("Failed to execute query [%s]".formatted(query.getStatement()), e);
        }
    }

    private Stream<DocumentReference> getDocumentReferences(WikiReference wiki, List<Object[]> result)
    {
        return result.stream()
            .map(item ->
            {
                // Create a proper localized document reference.
                DocumentReference documentReference =
                    this.documentReferenceResolver.resolve((String) item[0], wiki);
                Locale locale = LocaleUtils.toLocale((String) item[1], Locale.ROOT);
                return new DocumentReference(documentReference, locale);
            });
    }


    /**
     * Build the query listing the documents of the given spaces. The query is the same for listing and counting:
     * {@link #countDocumentsFromWiki(WikiReference, List)} only adds the {@code count} filter, which rewrites the
     * select clause and keeps the where clause.
     *
     * <p>Hidden documents are excluded by an explicit condition in the statement rather than by the
     * {@code hidden/document} query filter: that filter reads the "display hidden documents" preference of the user
     * who is current when the filter is instantiated, so injected into a singleton it would follow whichever user
     * first triggered the lookup instead of excluding hidden documents unconditionally.</p>
     */
    private Query buildQuery(WikiReference wiki, List<SpaceReference> wikiSpaces) throws IndexException
    {
        List<String> spacePrefixes = wikiSpaces.stream()
            .map(space -> this.localEntityReferenceSerializer.serialize(space))
            .toList();

        String queryString = IntStream.range(0, spacePrefixes.size())
            .mapToObj("(doc.fullName LIKE :space%d)"::formatted)
            .collect(Collectors.joining(" OR ", "WHERE " + HIDDEN_CONDITION + " AND (", CLOSING_PARENTHESIS));

        try {
            Query query = this.queryManager.createQuery(queryString, Query.XWQL);
            for (int i = 0; i < spacePrefixes.size(); ++i) {
                query.bindValue("space%d".formatted(i)).literal(spacePrefixes.get(i)).literal(".").anyChars();
            }
            query.setWiki(wiki.getName());
            query.addFilter(this.languageFilter);
            return query;
        } catch (Exception e) {
            throw new IndexException(
                "Failed to construct query for spaces [%s]".formatted(String.join(", ", spacePrefixes)), e);
        }
    }

}
