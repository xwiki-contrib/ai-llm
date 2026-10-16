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

import java.util.List;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.component.wiki.WikiComponentException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ModelWikiObjectComponentBuilder}.
 *
 * @version $Id$
 */
@ComponentTest
class ModelWikiObjectComponentBuilderTest
{
    private static final DocumentReference MODEL_REFERENCE =
        new DocumentReference("xwiki", List.of("AI", "Models"), "MyModel");

    private static final ObjectReference OBJECT_REFERENCE =
        new ObjectReference("AI.Models.Code.ModelsClass[0]", MODEL_REFERENCE);

    @InjectMockComponents
    private ModelWikiObjectComponentBuilder builder;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private ConfigurationPageAuthorChecker configurationPageAuthorChecker;

    @Mock
    private XWikiContext context;

    @Mock
    private XWiki xwiki;

    @Mock
    private XWikiDocument document;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.context.getWiki()).thenReturn(this.xwiki);
        when(this.xwiki.getDocument(MODEL_REFERENCE, this.context)).thenReturn(this.document);
    }

    @Test
    void untrustedAuthorStopsTheBuild() throws Exception
    {
        WikiComponentException rejection = new WikiComponentException("rejected");
        when(this.configurationPageAuthorChecker.requireAdminAuthor(this.document, OBJECT_REFERENCE)).thenThrow(rejection);

        WikiComponentException exception =
            assertThrows(WikiComponentException.class, () -> this.builder.buildComponents(OBJECT_REFERENCE));

        assertSame(rejection, exception);
        // The model object is never read when the page is not trusted.
        verify(this.document, never()).getXObject(any(ObjectReference.class));
    }
}
