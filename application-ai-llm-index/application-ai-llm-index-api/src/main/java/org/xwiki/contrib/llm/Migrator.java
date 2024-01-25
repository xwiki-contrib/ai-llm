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

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.refactoring.event.DocumentRenamedEvent;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

/**
 * This class is responsible for handling the document queue,
 * chunking the documents from the queue, computing the embeddings and storing them in solr.
 * 
 * @version $Id$
 */
@Component
@Named("Migrator")
@Singleton
public class Migrator implements EventListener
{
    @Inject
    protected Provider<XWikiContext> contextProvider;
 
    @Inject
    private Logger logger;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    @Named("current")
    private SpaceReferenceResolver<String> explicitStringSpaceRefResolver;
    
    @Override public String getName()
    {
        return "Migrator";
    }
    
    @Override public List<Event> getEvents()
    {
        return Arrays.<Event>asList(new DocumentRenamedEvent());
    }

    @Override public void onEvent(Event event, Object source, Object data)
    {
        EntityReference documentClassReference = getCollectionObjectReference();
        this.logger.info("Event: {}", event);
        XWikiDocument xdocument = (XWikiDocument) source;
        BaseObject collectionObject = xdocument.getXObject(documentClassReference);
        this.logger.info("Document ref on event: {}", xdocument.getDocumentReference());
        if (collectionObject != null) {
            try {
                String newID = xdocument.getDocumentReference().getLastSpaceReference().toString();
                collectionObject.setStringValue("id", newID);
                XWikiContext context = this.contextProvider.get();
                context.getWiki().saveDocument(xdocument, "Migrating collection ID", true, context);
                Collection collection = this.collectionManager.getCollection(newID);
                //for each document in the collection update the document object with the new collection ID
                for (String documentName : collection.getDocuments()) {
                    Document document = collection.getDocument(documentName);
                    document.setCollection(newID);
                    document.save();
                }
                
                
            } catch (Exception e) {
                this.logger.error("Failure in indexWorker listener", e);
            }

        }
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
}
