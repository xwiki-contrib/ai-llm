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
import java.util.Arrays;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.query.QueryManager;
import org.xwiki.query.Query;
import org.xwiki.refactoring.event.DocumentRenamedEvent;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

/**
 * This class is responsible for handling renaming of a collection.
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

    @Inject
    protected Provider<XWikiContext> contextProvider;
 
    @Inject
    private Logger logger;

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;
    
    /**
     * Default constructor.
     */
    public Migrator()
    {
        super(NAME, new DocumentRenamedEvent());
    }
    
    @Override public void processLocalEvent(Event event, Object source, Object data)
    {
        try {
            this.logger.info("Event zedaDelta: {}", event);
            DocumentRenamedEvent documentRenamedEvent = (DocumentRenamedEvent) event;
            DocumentReference targetRef = documentRenamedEvent.getTargetReference();
            XWikiContext context = this.contextProvider.get();
            XWikiDocument xdocument =  this.contextProvider.get().getWiki().getDocument(targetRef, context);
            EntityReference documentClassReference = getCollectionObjectReference();
            BaseObject collectionObject = xdocument.getXObject(documentClassReference);
            if (collectionObject != null) {
                this.logger.info("Found collection object {}", collectionObject);
                String oldID = collectionObject.getStringValue(ID_FIELDNAME);
                String newIDRef = xdocument.getDocumentReference().getLastSpaceReference().toString();
                String newID = newIDRef.substring(newIDRef.lastIndexOf(Collection.SPACE_DELIMITER) + 1);
                collectionObject.setStringValue(ID_FIELDNAME, newID);
                context.getWiki().saveDocument(xdocument, COMMENT_STRING, true, context);
                String documentClass = Document.XCLASS_SPACE_STRING + Collection.SPACE_DELIMITER + Document.XCLASS_NAME;
                String templateDoc = Document.XCLASS_SPACE_STRING + ".DocumentsTemplate";
                List<String> documents = null;
                String hql = "select doc.fullName from XWikiDocument doc, BaseObject obj,"
                            + "StringProperty prop where doc.fullName = obj.name "
                            + "and obj.className='" + documentClass
                            + "' and doc.fullName <> '" + templateDoc
                            + "' and obj.id = prop.id.id "
                            + "and prop.id.name = '" + COLLECTION_FIELDNAME
                            + "' and prop.value = '" + oldID + "'";
                Query query = queryManager.createQuery(hql, Query.HQL);
                documents = query.execute();
                updateDocuments(documents, newID);
            }
        } catch (Exception e) {
            this.logger.error("Failure in migratior listener: [{}]", e.getMessage());
        }
    }

    private void updateDocuments(List<String> documents, String newID)
    {
        XWikiContext context = this.contextProvider.get();

        for (String documentName : documents) {
            try {
                DocumentReference docRef = getDocumentReference(documentName);
                XWikiDocument xdocument = context.getWiki().getDocument(docRef, context);
                BaseObject documentObject = xdocument.getXObject(getDocumentObjectReference());
                documentObject.setStringValue(COLLECTION_FIELDNAME, newID);
                context.getWiki().saveDocument(xdocument, COMMENT_STRING, true, context);
            } catch (XWikiException e) {
                // Handle exceptions appropriately
                this.logger.error("Failure to update document [{}] in migratior listener: [{}]",
                                 documentName, e.getMessage());
            }
        }
    }

    private DocumentReference getDocumentReference(String fullName)
    {
        XWikiContext context = this.contextProvider.get();
        String[] parts = fullName.split("\\.");
        String pageName = parts[parts.length - 1];
        List<String> spaceNames = Arrays.asList(parts).subList(0, parts.length - 1);
        return new DocumentReference(context.getWikiId(), spaceNames, pageName);
    }

    private EntityReference getCollectionObjectReference()
    {
        SpaceReference spaceRef = explicitStringSpaceRefResolver.resolve(Collection.XCLASS_SPACE_STRING);

        EntityReference collectionClassRef = new EntityReference(Collection.XCLASS_NAME,
                                    EntityType.DOCUMENT,
                                    spaceRef
                                );
        return new EntityReference(Collection.XCLASS_NAME, EntityType.OBJECT, collectionClassRef);
    }

    private EntityReference getDocumentObjectReference()
    {
        SpaceReference spaceRef = explicitStringSpaceRefResolver.resolve(Document.XCLASS_SPACE_STRING);

        EntityReference documentClassRef = new EntityReference(Document.XCLASS_NAME,
                                    EntityType.DOCUMENT,
                                    spaceRef
                                );
        return new EntityReference(Document.XCLASS_NAME, EntityType.OBJECT, documentClassRef);
    }
}
