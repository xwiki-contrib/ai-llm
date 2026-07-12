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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MCPObjectWriteSupport}: the corner cases the tool tests cannot reach against the real
 * store, chiefly the read-only object-existence validation ({@code requireObjectExists}) and the
 * version-skew coercion seam ({@code parseOrNull} absorbing a 17.10 {@code fromString} throw into the
 * teaching refusal). The main validation, coercion and wiring paths are covered by
 * {@link MCPWriteObjectToolTest} and {@link MCPDeleteObjectToolTest} end to end.
 *
 * @version $Id$
 */
class MCPObjectWriteSupportTest
{
    private static final String CLASS_NAME = "Blog.CommentClass";

    private static final String TEXT_FIELD = "text";

    private static final DocumentReference CLASS_REFERENCE =
        new DocumentReference("xwiki", "Blog", "CommentClass");

    @Test
    void requireObjectExistsPassesReadOnlyWhenTheObjectIsPresent()
    {
        XWikiDocument document = mock(XWikiDocument.class);
        BaseObject object = mock(BaseObject.class);
        when(document.getXObject(CLASS_REFERENCE, 3)).thenReturn(object);

        // The check reads the document only: it must neither throw nor mutate (no removeXObject, no clone).
        assertDoesNotThrow(
            () -> MCPObjectWriteSupport.requireObjectExists(document, CLASS_REFERENCE, CLASS_NAME, 3));
    }

    @Test
    void negativeNumberIsRefusedBeforeThePlatformLookup()
    {
        XWikiDocument document = mock(XWikiDocument.class);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> MCPObjectWriteSupport.requireObjectExists(document, CLASS_REFERENCE, CLASS_NAME, -1));

        // The refusal happens before getXObject, whose platform implementation throws
        // IndexOutOfBoundsException for a negative number.
        assertTrue(thrown.getMessage().contains("non-negative"), thrown.getMessage());
    }

    @Test
    void noObjectErrorListsOnlyTheNonNullSlots()
    {
        XWikiDocument document = mock(XWikiDocument.class);
        BaseObject survivor = mock(BaseObject.class);
        List<BaseObject> objects = new ArrayList<>();
        objects.add(null);
        objects.add(survivor);
        when(document.getXObject(CLASS_REFERENCE, 9)).thenReturn(null);
        when(document.getXObjects(CLASS_REFERENCE)).thenReturn(objects);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> MCPObjectWriteSupport.requireObjectExists(document, CLASS_REFERENCE, CLASS_NAME, 9));

        assertEquals("No object of class \"" + CLASS_NAME + "\" at number 9. Existing object numbers: 1.",
            thrown.getMessage());
    }

    @Test
    void writtenValueThrowingOnParseBecomesTheTeachingRefusal() throws Exception
    {
        // On the 17.10 runtime PropertyClass.fromString throws instead of returning null (XWIKI-20910);
        // the 17.4 build cannot reproduce that, so a stub throws in its place. A RuntimeException is used
        // because Mockito rejects thenThrow of a checked exception fromString does not declare here.
        PropertyClass property = mock(PropertyClass.class);
        when(property.getClassType()).thenReturn("String");
        when(property.getName()).thenReturn(TEXT_FIELD);
        when(property.fromString(anyString())).thenThrow(new RuntimeException("boom"));
        BaseClass xclass = mock(BaseClass.class);
        when(xclass.get(TEXT_FIELD)).thenReturn(property);
        when(xclass.getEnabledProperties()).thenReturn(List.of(property));
        XWikiDocument classDoc = mock(XWikiDocument.class);
        when(classDoc.getXClass()).thenReturn(xclass);

        // parseOrNull turns the throw into null, so validateAndCoerce raises the teaching refusal rather
        // than letting the exception escape as a save-failure.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> MCPObjectWriteSupport.applyFields(mock(XWikiDocument.class), classDoc, CLASS_REFERENCE,
                CLASS_NAME, 0, Map.of(TEXT_FIELD, "x"), null));

        assertTrue(thrown.getMessage().contains("invalid value \"x\""), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("field \"" + TEXT_FIELD + "\""), thrown.getMessage());
    }
}
