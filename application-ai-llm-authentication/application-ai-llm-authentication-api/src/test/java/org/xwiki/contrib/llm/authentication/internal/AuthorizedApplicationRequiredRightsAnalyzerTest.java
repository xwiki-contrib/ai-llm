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
package org.xwiki.contrib.llm.authentication.internal;

import java.util.List;
import java.util.function.Supplier;

import javax.inject.Named;

import org.junit.jupiter.api.Test;
import org.xwiki.model.EntityType;
import org.xwiki.platform.security.requiredrights.RequiredRight;
import org.xwiki.platform.security.requiredrights.RequiredRightAnalysisResult;
import org.xwiki.platform.security.requiredrights.RequiredRightsException;
import org.xwiki.platform.security.requiredrights.internal.provider.BlockSupplierProvider;
import org.xwiki.rendering.block.Block;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseObjectReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthorizedApplicationRequiredRightsAnalyzer}.
 */
@ComponentTest
class AuthorizedApplicationRequiredRightsAnalyzerTest
{
    @InjectMockComponents
    private AuthorizedApplicationRequiredRightsAnalyzer analyzer;

    @MockComponent
    @Named("translation")
    private BlockSupplierProvider<String> translationBlockSupplierProvider;

    @MockComponent
    private BlockSupplierProvider<BaseObject> xObjectBlockSupplierProvider;

    @Test
    void analyze() throws RequiredRightsException
    {
        BaseObject object = mock();
        BaseObjectReference reference = mock();
        when(object.getReference()).thenReturn(reference);

        Supplier<Block> translationBlockSupplier = mock();
        when(this.translationBlockSupplierProvider.get("aiLLM.authentication.api.applicationRequiredAdminRight"))
            .thenReturn(translationBlockSupplier);
        Block translationBlock = mock();
        when(translationBlockSupplier.get()).thenReturn(translationBlock);

        Supplier<Block> xObjectBlockSupplier = mock();
        when(this.xObjectBlockSupplierProvider.get(object)).thenReturn(xObjectBlockSupplier);
        Block xObjectBlock = mock();
        when(xObjectBlockSupplier.get()).thenReturn(xObjectBlock);

        List<RequiredRightAnalysisResult> results = this.analyzer.analyze(object);
        assertEquals(1, results.size());
        RequiredRightAnalysisResult result = results.get(0);
        assertEquals(reference, result.getEntityReference());
        assertEquals(translationBlock, result.getSummaryMessage());
        assertEquals(xObjectBlock, result.getDetailedMessage());
        assertEquals(1, result.getRequiredRights().size());
        RequiredRight requiredRight = result.getRequiredRights().get(0);
        assertEquals(Right.ADMIN, requiredRight.getRight());
        assertFalse(requiredRight.isManualReviewNeeded());
        assertEquals(EntityType.WIKI, requiredRight.getEntityType());
    }
}
