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

import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.openai.Context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link ReciprocalRankFusion}.
 *
 * @version $Id$
 */
class ReciprocalRankFusionTest
{
    private static Context result(String content, double score)
    {
        return new Context("collection", "document-" + content, null, "url-" + content, content, score,
            List.of(0.1f));
    }

    @Test
    void topSemanticHitComesBeforeTopKeywordHitWhateverTheScores()
    {
        Context semantic = result("semantic", 0.8);
        Context keyword = result("keyword", 12.0);

        // Both are first in their list: they have the same rank and stay in the order in which they were met.
        assertEquals(List.of(semantic, keyword), ReciprocalRankFusion.fuse(List.of(semantic), List.of(keyword)));
    }

    @Test
    void resultFoundByBothSearchesComesFirstAndIsReturnedOnceAsTheSemanticInstance()
    {
        Context semanticA = result("A", 0.9);
        Context semanticB = result("B", 0.8);
        Context semanticShared = result("shared", 0.1);
        Context keywordC = result("C", 20.0);
        Context keywordD = result("D", 15.0);
        Context keywordShared = result("shared", 3.0);

        List<Context> fused = ReciprocalRankFusion.fuse(List.of(semanticA, semanticB, semanticShared),
            List.of(keywordC, keywordD, keywordShared));

        assertEquals(List.of(semanticShared, semanticA, keywordC, semanticB, keywordD), fused);
        assertSame(semanticShared, fused.get(0));
        assertEquals(0.1, fused.get(0).similarityScore());
    }

    @Test
    void emptyKeywordResultsKeepTheOrderOfTheSemanticResults()
    {
        // The scores are deliberately not in descending order: only the position counts.
        List<Context> semantic = List.of(result("A", 0.2), result("B", 0.9), result("C", 0.5));

        assertEquals(semantic, ReciprocalRankFusion.fuse(semantic, List.of()));
    }

    @Test
    void emptySemanticResultsKeepTheOrderOfTheKeywordResults()
    {
        List<Context> keyword = List.of(result("A", 2.0), result("B", 9.0), result("C", 5.0));

        assertEquals(keyword, ReciprocalRankFusion.fuse(List.of(), keyword));
    }

    @Test
    void emptyListsGiveAnEmptyList()
    {
        assertEquals(List.of(), ReciprocalRankFusion.fuse(List.of(), List.of()));
    }

    @Test
    void everyDistinctContentIsReturnedOnce()
    {
        List<Context> semantic = List.of(result("A", 0.9), result("B", 0.8), result("A", 0.7));
        List<Context> keyword = List.of(result("B", 9.0), result("C", 8.0), result("D", 7.0), result("C", 6.0));

        List<Context> fused = ReciprocalRankFusion.fuse(semantic, keyword);

        assertEquals(4, fused.size());
        assertEquals(List.of("B", "A", "C", "D"), fused.stream().map(Context::content).toList());
    }

    @Test
    void resultsAreOrderedByTheSumOfTheirReciprocalRanks()
    {
        Context semanticA = result("A", 0.82);
        Context semanticB = result("B", 0.80);
        Context keywordC = result("C", 14.0);
        Context keywordB = result("B", 9.0);

        // B: 1/62 + 1/62, A: 1/61, C: 1/61.
        assertEquals(List.of(semanticB, semanticA, keywordC),
            ReciprocalRankFusion.fuse(List.of(semanticA, semanticB), List.of(keywordC, keywordB)));
    }

    @Test
    void contentRepeatedInOneListCountsOnceAndDoesNotTakeARank()
    {
        Context repeated = result("repeated", 0.9);
        Context repeatedAgain = result("repeated", 0.85);
        Context semantic = result("semantic", 0.8);
        Context keyword = result("keyword", 12.0);
        Context both = result("both", 0.7);
        Context bothKeyword = result("both", 9.0);

        // Semantic: repeated (1), semantic (2), both (3). Keyword: keyword (1), both (2). Counting the repetition
        // twice would put "repeated" before "both", and letting it take a rank would put "keyword" before "semantic".
        List<Context> fused = ReciprocalRankFusion.fuse(List.of(repeated, repeatedAgain, semantic, both),
            List.of(keyword, bothKeyword));

        assertEquals(List.of(both, repeated, keyword, semantic), fused);
        assertSame(repeated, fused.get(1));
    }
}
