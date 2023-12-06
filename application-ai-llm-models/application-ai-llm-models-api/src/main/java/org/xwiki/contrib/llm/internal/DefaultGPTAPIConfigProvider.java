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
package org.xwiki.contrib.llm.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.stability.Unstable;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceSerializer;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.PropertyInterface;

/**
 * Default implementation of {@link GPTAPIConfigProvider}.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Unstable
@Singleton
public class DefaultGPTAPIConfigProvider implements GPTAPIConfigProvider 
{
    private static final List<String> AI_SPACE_NAMES = List.of("AI", "Code");

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    private GroupManager groupManager;

    @Override
    public Map<String, GPTAPIConfig> getConfigObjects(String currentWiki, UserReference userReference)
        throws GPTAPIException
    {
        XWikiContext context = contextProvider.get();
        com.xpn.xwiki.XWiki xwiki = context.getWiki();
        // Get the user using the Extension in the actual context.
        DocumentReference documentUserReference = userReferenceSerializer.serialize(userReference);
        try {
            Collection<DocumentReference> userGroups =
                this.groupManager.getGroups(documentUserReference, currentWiki, true);
            return getConfigFromDoc(xwiki, context, currentWiki, userGroups);
        } catch (GroupException e) {
            throw new GPTAPIException("Error while trying to access the user's groups.", e);
        }
    }

    /**
     * @param xwiki       The XWiki instance.
     * @param context     The current XWiki context.
     * @param currentWiki The current Wiki id.
     * @param userGroups  The list of groups containing the actual suser.
     * @return A map object of {@link #GPTAPIConfig}
     */
    private Map<String, GPTAPIConfig> getConfigFromDoc(com.xpn.xwiki.XWiki xwiki, XWikiContext context,
            String currentWiki, Collection<DocumentReference> userGroups) throws GPTAPIException
    {
        // Retrieve the LLM Configuration Objects
        Map<String, GPTAPIConfig> configProperties = new HashMap<>();
        try {
            DocumentReference configDocumentReference = new DocumentReference(currentWiki, AI_SPACE_NAMES, "AIConfig");
            DocumentReference configClassReference =
                new DocumentReference(currentWiki, AI_SPACE_NAMES, "AIConfigClass");
            XWikiDocument doc = xwiki.getDocument(configDocumentReference, context);
            List<BaseObject> configObjects = doc.getXObjects(configClassReference);
            // Build the Java configurationObject with a Map.
            for (BaseObject configObject : configObjects) {
                if (configObject == null) {
                    continue;
                }
                Map<String, Object> configObjMap = new HashMap<>();
                for (String fieldName : configObject.getPropertyList()) {
                    PropertyInterface field = configObject.getField(fieldName);
                    if (field instanceof BaseProperty) {
                        configObjMap.put(fieldName, ((BaseProperty<?>) field).getValue());
                    }
                }

                GPTAPIConfig res = new GPTAPIConfig(configObjMap);
                boolean allowed = Arrays.stream(StringUtils.split(res.getAllowedGroup(), ','))
                    .map(String::trim)
                    .map(this.documentReferenceResolver::resolve)
                    .anyMatch(userGroups::contains);
                if (allowed) {
                    configProperties.put(res.getName(), res);
                }
            }
            return configProperties;
        } catch (XWikiException e) {
            throw new GPTAPIException("Error while trying to access the configuration.", e);
        }
    }
}
