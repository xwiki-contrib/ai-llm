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
import java.util.Map;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.wiki.WikiComponent;
import org.xwiki.component.wiki.WikiComponentException;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link GPTAPIServerWikiObjectComponentBuilder}.
 *
 * @version $Id$
 */
@ComponentTest
class GPTAPIServerWikiObjectComponentBuilderTest
{
    private static final DocumentReference CONFIG_REFERENCE = new DocumentReference(
        GPTAPIServerWikiObjectComponentBuilder.AI_CONFIG_DOCUMENT, new WikiReference("xwiki"));

    private static final ObjectReference OBJECT_REFERENCE =
        new ObjectReference("AI.Code.AIConfigClass[0]", CONFIG_REFERENCE);

    private static final DocumentReference USER_REFERENCE = new DocumentReference("xwiki", "XWiki", "User");

    @InjectMockComponents
    private GPTAPIServerWikiObjectComponentBuilder builder;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    private ConfigurationPageAuthorChecker configurationPageAuthorChecker;

    @MockComponent
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    @MockComponent
    private GPTAPIConfigBuilder configBuilder;

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
        when(this.xwiki.getDocument(CONFIG_REFERENCE, this.context)).thenReturn(this.document);
    }

    @Test
    void untrustedAuthorStopsTheBuild() throws Exception
    {
        WikiComponentException rejection = new WikiComponentException("rejected");
        when(this.configurationPageAuthorChecker.requireAdminAuthor(this.document, OBJECT_REFERENCE)).thenThrow(rejection);

        WikiComponentException exception =
            assertThrows(WikiComponentException.class, () -> this.builder.buildComponents(OBJECT_REFERENCE));

        assertSame(rejection, exception);
        verify(this.document, never()).getXObject(any(ObjectReference.class));
        verify(this.configBuilder, never()).build(any());
    }

    @Test
    void adminAuthorIsPassedToTheServerComponent() throws Exception
    {
        when(this.configurationPageAuthorChecker.requireAdminAuthor(this.document, OBJECT_REFERENCE))
            .thenReturn(USER_REFERENCE);
        BaseObject xObject = new BaseObject();
        when(this.document.getXObject(OBJECT_REFERENCE)).thenReturn(xObject);
        GPTAPIConfig config = new GPTAPIConfig(Map.of("url", "https://llm.example/v1/"));
        when(this.configBuilder.build(xObject)).thenReturn(config);
        ComponentManager componentManager = mock(ComponentManager.class);
        when(this.componentManagerProvider.get()).thenReturn(componentManager);
        GPTAPIServerWikiComponent component = mock(GPTAPIServerWikiComponent.class);
        when(componentManager.getInstance(GPTAPIServerWikiComponent.class, "openai")).thenReturn(component);

        List<WikiComponent> components = this.builder.buildComponents(OBJECT_REFERENCE);

        assertEquals(List.of(component), components);
        verify(component).initialize(config, OBJECT_REFERENCE, USER_REFERENCE);
    }
}
