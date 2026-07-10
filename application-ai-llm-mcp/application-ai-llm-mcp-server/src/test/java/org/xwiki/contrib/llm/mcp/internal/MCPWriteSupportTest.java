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
package org.xwiki.contrib.llm.mcp.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MCPWriteSupport}: the version-comment construction (the {@code [AI] } prefix is a
 * stable contract, pinned literally here) and the minor-edit policy. The wiki-switch scaffold and the
 * shared message fragments are covered through the write tools' tests.
 *
 * @version $Id$
 */
class MCPWriteSupportTest
{
    /**
     * An update description whose appearance in the built comment would mean the agent comment or the
     * creation default was wrongly ignored.
     */
    private static final String UNUSED_DESCRIPTION = "unused description";

    @Test
    void buildCommentPrefixesAgentComment()
    {
        assertEquals("[AI] fix typo", MCPWriteSupport.buildComment("fix typo", false, UNUSED_DESCRIPTION));
    }

    @Test
    void buildCommentAgentCommentWinsOverCreationDefault()
    {
        assertEquals("[AI] initial import", MCPWriteSupport.buildComment("initial import", true,
            UNUSED_DESCRIPTION));
    }

    @Test
    void buildCommentBlankAgentCommentFallsBackToUpdateDescription()
    {
        assertEquals("[AI] 2 edits", MCPWriteSupport.buildComment("   ", false, "2 edits"));
    }

    @Test
    void buildCommentNullAgentCommentOnCreationUsesCreatedDefault()
    {
        assertEquals("[AI] Created document", MCPWriteSupport.buildComment(null, true, UNUSED_DESCRIPTION));
    }

    @Test
    void buildCommentAbbreviatesAtOneThousandCharactersKeepingPrefix()
    {
        String comment = MCPWriteSupport.buildComment("c".repeat(2000), false, UNUSED_DESCRIPTION);

        assertEquals(1000, comment.length());
        assertTrue(comment.startsWith("[AI] cc"), comment);
        assertTrue(comment.endsWith("..."), comment);
    }

    @Test
    void isMinorEditOnlyForNonMajorSaveOfExistingDocument()
    {
        assertFalse(MCPWriteSupport.isMinorEdit(true, false));
        assertFalse(MCPWriteSupport.isMinorEdit(true, true));
        assertFalse(MCPWriteSupport.isMinorEdit(false, true));
        assertTrue(MCPWriteSupport.isMinorEdit(false, false));
    }
}
