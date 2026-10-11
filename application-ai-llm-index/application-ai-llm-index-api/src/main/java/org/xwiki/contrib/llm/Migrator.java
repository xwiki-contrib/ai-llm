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
package org.xwiki.contrib.llm;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.contrib.llm.internal.InternalDocumentStore;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.refactoring.event.DocumentRenamedEvent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Updates the id of a collection and the collection field of its documents when the page of the collection is
 * renamed.
 *
 * @version $Id$
 */
@Component
@Named(Migrator.NAME)
@Singleton
public class Migrator extends AbstractLocalEventListener
{
    /**
     * The name of this event listener.
     */
    public static final String NAME = "org.xwiki.contrib.llm.Migrator";

    private static final String ID_FIELDNAME = "id";

    private static final String COLLECTION_FIELDNAME = "collection";

    private static final String COMMENT_STRING = "Migrating collection ID";

    private static final String DOCUMENT_CLASS =
        Document.XCLASS_SPACE_STRING + Collection.SPACE_DELIMITER + Document.XCLASS_NAME;

    /**
     * The documents of the renamed collection: the pages of its documents space that carry a documents object
     * still referring to the old collection id. The query is bound to the Documents space of the new collection
     * page: the refactoring job renames the children of a space before its WebHome
     * ({@code AbstractEntityJob.visitDocumentAncestor} walks {@code EntityReferenceTreeNode.getChildren()}, a
     * TreeMap in name order, so {@code Documents} is visited before {@code WebHome}), so the documents already sit
     * under the new space when the event of the collection page arrives. A non-deep rename that leaves the
     * children behind is deliberately not followed.
     */
    private static final String DOCUMENTS_QUERY = "select doc.fullName from XWikiDocument doc, BaseObject obj, "
        + "StringProperty prop where doc.fullName = obj.name and obj.className = :documentClass "
        + "and doc.space = :space and obj.id = prop.id.id and prop.id.name = '" + COLLECTION_FIELDNAME + "' "
        + "and prop.value = :oldId";

    @Inject
    protected Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    /**
     * Default constructor.
     */
    public Migrator()
    {
        super(NAME, new DocumentRenamedEvent());
    }

    @Override
    public void processLocalEvent(Event event, Object source, Object data)
    {
        DocumentReference targetRef = ((DocumentRenamedEvent) event).getTargetReference();
        WikiReference wikiReference = targetRef.getWikiReference();
        String newID = targetRef.getLastSpaceReference().getName();

        // Only the page of a collection is a collection: a collections object on any other page is ignored.
        if (!targetRef.equals(DefaultCollection.getDocumentReference(wikiReference.getName(), newID))) {
            return;
        }

        try {
            XWikiContext context = this.contextProvider.get();
            // Never modify the cached instance of a document.
            XWikiDocument xdocument = context.getWiki().getDocument(targetRef, context).clone();
            BaseObject collectionObject = xdocument.getXObject(Collection.XCLASS_REFERENCE);
            if (collectionObject == null) {
                return;
            }

            String oldID = collectionObject.getStringValue(ID_FIELDNAME);
            collectionObject.setStringValue(ID_FIELDNAME, newID);
            context.getWiki().saveDocument(xdocument, COMMENT_STRING, true, context);

            Query query = this.queryManager.createQuery(DOCUMENTS_QUERY, Query.HQL);
            query.bindValue("documentClass", DOCUMENT_CLASS);
            query.bindValue("space", this.localEntityReferenceSerializer.serialize(
                InternalDocumentStore.getDocumentsSpaceReference(wikiReference.getName(), newID)));
            query.bindValue("oldId", oldID);
            query.setWiki(wikiReference.getName());
            updateDocuments(query.execute(), wikiReference, newID);
        } catch (Exception e) {
            this.logger.error("Failure in migrator listener for [{}]", targetRef, e);
        }
    }

    private void updateDocuments(List<String> documents, WikiReference wikiReference, String newID)
    {
        XWikiContext context = this.contextProvider.get();

        for (String documentName : documents) {
            try {
                DocumentReference docRef = this.documentReferenceResolver.resolve(documentName, wikiReference);
                XWikiDocument xdocument = context.getWiki().getDocument(docRef, context).clone();
                BaseObject documentObject = xdocument.getXObject(Document.XCLASS_REFERENCE);
                if (documentObject != null) {
                    documentObject.setStringValue(COLLECTION_FIELDNAME, newID);
                    context.getWiki().saveDocument(xdocument, COMMENT_STRING, true, context);
                }
            } catch (XWikiException e) {
                this.logger.error("Failed to update document [{}] in migrator listener", documentName, e);
            }
        }
    }
}
