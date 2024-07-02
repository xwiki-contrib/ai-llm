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
    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("hidden/document")
    private QueryFilter hiddenDocumentFilter;

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
                .mapToObj(i -> ":space" + i + " member of collection." + DefaultCollection.DOCUMENT_SPACE_FIELDNAME)
                .collect(Collectors.joining(" or "));

        try {
            Query query = this.queryManager.createQuery(
                "from doc.object(" + Collection.XCLASS_FULLNAME + ") as collection where (" + spaceQuery + ")",
                Query.XWQL);
            for (int i = 0; i < spaces.size(); ++i) {
                query.bindValue("space" + i, spaces.get(i));
            }
            query.setWiki(wiki.getName());

            List<String> queryResult = query.execute();

            // Get the collection name out of the returned document references. The collection name is the name of the
            // space reference.
            return queryResult.stream()
                .map(reference -> this.documentReferenceResolver.resolve(reference, wiki))
                .map(DocumentReference::getLastSpaceReference)
                .map(SpaceReference::getName)
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
            return (int) query.execute().get(0);
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


    private Query buildQuery(WikiReference wiki, List<SpaceReference> wikiSpaces) throws IndexException
    {
        List<String> spacePrefixes = wikiSpaces.stream()
            .map(space -> this.localEntityReferenceSerializer.serialize(space))
            .toList();

        String conditions = IntStream.range(0, spacePrefixes.size())
            .mapToObj("(doc.fullName LIKE :space%d)"::formatted)
            .collect(Collectors.joining(" OR "));
        String queryString = "WHERE " + conditions;

        try {
            Query query = this.queryManager.createQuery(queryString, Query.XWQL);
            for (int i = 0; i < spacePrefixes.size(); ++i) {
                query.bindValue("space%d".formatted(i)).literal(spacePrefixes.get(i)).literal(".").anyChars();
            }
            query.setWiki(wiki.getName());
            query.addFilter(this.hiddenDocumentFilter);
            query.addFilter(this.languageFilter);
            return query;
        } catch (Exception e) {
            throw new IndexException(
                "Failed to construct query for spaces [%s]".formatted(String.join(", ", spacePrefixes)), e);
        }
    }

}
