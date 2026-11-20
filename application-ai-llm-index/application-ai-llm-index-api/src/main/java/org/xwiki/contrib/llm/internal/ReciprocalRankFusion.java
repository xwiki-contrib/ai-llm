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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xwiki.contrib.llm.openai.Context;

/**
 * Merges the results of a semantic search and of a keyword search into a single ranked list with Reciprocal Rank
 * Fusion.
 * <p>
 * The scores of the two searches are on unrelated scales: the score of a semantic result is the similarity of two
 * vectors, between 0 and 1, while the score of a keyword result has no upper bound. They cannot be compared, so the
 * merge ignores them and only uses the position of each result in its own list: a result gets
 * {@code 1 / (K + rank)} from each list it appears in, the rank of the first result of a list being 1, and the
 * results are ordered by the sum of what they got. A result found by both searches therefore ranks before a result
 * found at the same position by only one of them.
 *
 * @version $Id$
 * @since 1.0.1
 */
public final class ReciprocalRankFusion
{
    /**
     * The constant added to the rank of a result. The larger it is, the less the first positions of a list weigh
     * compared to the following ones.
     */
    private static final int K = 60;

    private ReciprocalRankFusion()
    {
        // Utility class.
    }

    /**
     * Merges two ranked lists of results. Two results are the same result when their content is equal: the result
     * is then returned once, as the first instance met when going through the semantic results and then through the
     * keyword results. A content that one list holds several times only counts once in that list, at its first
     * position. The results are returned as received, with the score given by the search that found them, and no
     * distinct content is left out.
     *
     * @param semanticResults the results of the semantic search, the best one first
     * @param keywordResults the results of the keyword search, the best one first
     * @return the distinct results of both lists, the best one first; results of equal rank are in the order in
     *     which they were first met, so when one list is empty the other one is returned in its own order
     */
    public static List<Context> fuse(List<Context> semanticResults, List<Context> keywordResults)
    {
        Map<String, Context> resultByContent = new LinkedHashMap<>();
        Map<String, Double> scoreByContent = new LinkedHashMap<>();
        for (List<Context> rankedResults : List.of(semanticResults, keywordResults)) {
            // A content that a list holds several times, for instance a text repeated on several pages, counts once
            // in that list, at its first position, and the results that follow it move up.
            Set<String> contentsOfList = new HashSet<>();
            for (Context result : rankedResults) {
                if (contentsOfList.add(result.content())) {
                    resultByContent.putIfAbsent(result.content(), result);
                    scoreByContent.merge(result.content(), 1.0 / (K + contentsOfList.size()), Double::sum);
                }
            }
        }

        List<Context> fusedResults = new ArrayList<>(resultByContent.values());
        // The sort is stable: results with the same fused score stay in the order in which they were first met.
        fusedResults.sort((first, second) ->
            Double.compare(scoreByContent.get(second.content()), scoreByContent.get(first.content())));
        return fusedResults;
    }
}
