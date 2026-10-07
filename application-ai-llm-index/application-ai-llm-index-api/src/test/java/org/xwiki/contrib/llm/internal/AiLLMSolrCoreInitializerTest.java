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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.FieldTypeRepresentation;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStoreReindexMarker;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.XWikiSolrCore;
import org.xwiki.search.solr.internal.SolrSchemaUtils;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the schema migration of {@link AiLLMSolrCoreInitializer}.
 *
 * @version $Id$
 */
@ComponentTest
class AiLLMSolrCoreInitializerTest
{
    private static final String XVERSION = "__xversion";

    private static final String CVERSION = "__cversion";

    private static final String NAME = "name";

    private static final String DEF_VAL = "defVal";

    private static final String CLEANUP_QUERY = "storeHint:xwiki";

    @InjectMockComponents
    private AiLLMSolrCoreInitializer initializer;

    @MockComponent
    private SolrSchemaUtils solrSchemaUtils;

    @MockComponent
    private SolrUtils solrUtils;

    @MockComponent
    private XWikiDocumentStoreReindexMarker marker;

    @Mock
    private XWikiSolrCore core;

    @Mock
    private SolrClient client;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.core.getClient()).thenReturn(this.client);
        when(this.solrUtils.toCompleteFilterQueryString("xwiki")).thenReturn("xwiki");
    }

    /**
     * @param coreVersion the stored core schema version, or {@code null} for a core that has no schema yet
     */
    private void setStoredVersions(Long coreVersion) throws Exception
    {
        Map<String, FieldTypeRepresentation> fieldTypes = new HashMap<>();
        fieldTypes.put(XVERSION, versionType(XVERSION, 121000000L));
        if (coreVersion != null) {
            fieldTypes.put(CVERSION, versionType(CVERSION, coreVersion));
        }
        when(this.solrSchemaUtils.getFieldTypes(this.core, false)).thenReturn(fieldTypes);
    }

    private void setEmptyIndex() throws Exception
    {
        QueryResponse response = mock();
        when(response.getResults()).thenReturn(new SolrDocumentList());
        when(this.client.query(any())).thenReturn(response);
    }

    private String getStoredCoreVersion(boolean add) throws Exception
    {
        ArgumentCaptor<FieldTypeDefinition> definition = ArgumentCaptor.forClass(FieldTypeDefinition.class);
        verify(this.solrSchemaUtils, atLeastOnce()).setFieldType(eq(this.core), definition.capture(), eq(add));
        return definition.getAllValues().stream()
            .filter(fieldType -> CVERSION.equals(fieldType.getAttributes().get(NAME)))
            .map(fieldType -> (String) fieldType.getAttributes().get(DEF_VAL))
            .findFirst()
            .orElse(null);
    }

    private static FieldTypeRepresentation versionType(String name, long version)
    {
        FieldTypeRepresentation representation = new FieldTypeRepresentation();
        representation.setAttributes(Map.of(NAME, name, DEF_VAL, String.valueOf(version)));
        return representation;
    }

    @Test
    void migrationFromContentIndexVersionDropsWikiPageChunksAndSetsTheMarker() throws Exception
    {
        setStoredVersions(121000006L);

        this.initializer.initialize(this.core);

        verify(this.client).deleteByQuery(CLEANUP_QUERY);
        verify(this.marker).set();
        verify(this.client, never()).query(any());

        // The stored core version is bumped to the current one.
        assertEquals("121000007", getStoredCoreVersion(false));
    }

    @Test
    void migrationFromStoreHintVersionDropsWikiPageChunksBeforeIndexingOldContent() throws Exception
    {
        setStoredVersions(121000005L);
        setEmptyIndex();

        this.initializer.initialize(this.core);

        var order = inOrder(this.client, this.marker);
        order.verify(this.client).deleteByQuery(CLEANUP_QUERY);
        order.verify(this.marker).set();
        order.verify(this.client).query(any());
        // The content_index field is set, the keyword index step is run and the version is bumped.
        verify(this.solrSchemaUtils).setField(eq(this.core), eq("content_index"), eq("text_general"), eq(false));
        assertEquals("121000007", getStoredCoreVersion(false));
    }

    @Test
    void freshCoreIsCreatedWithoutCleanup() throws Exception
    {
        setStoredVersions(null);

        this.initializer.initialize(this.core);

        verify(this.client, never()).deleteByQuery(anyString());
        verify(this.marker, never()).set();
        verify(this.client, never()).query(any());
        assertEquals("121000007", getStoredCoreVersion(true));
    }

    @Test
    void markerWriteFailureKeepsTheStoredVersion() throws Exception
    {
        setStoredVersions(121000006L);
        IOException failure = new IOException("read-only permanent directory");
        doThrow(failure).when(this.marker).set();

        SolrException exception =
            assertThrows(SolrException.class, () -> this.initializer.initialize(this.core));

        assertSame(failure, exception.getCause());
        verify(this.client).deleteByQuery(CLEANUP_QUERY);
        verify(this.solrSchemaUtils, never()).setFieldType(any(), any(), eq(false));
        verify(this.solrSchemaUtils, never()).commit(any());
    }

    @Test
    void currentVersionIsNotMigrated() throws Exception
    {
        setStoredVersions(121000007L);

        this.initializer.initialize(this.core);

        verify(this.client, never()).deleteByQuery(anyString());
        verify(this.marker, never()).set();
        verify(this.solrSchemaUtils, never()).setFieldType(any(), any(), eq(false));
    }
}
