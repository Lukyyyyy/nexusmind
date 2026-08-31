package com.luky.nexusmind.service;

import com.luky.nexusmind.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFuserTest {

    private static SearchResult hit(String md5, int chunk) {
        return new SearchResult(md5, chunk, "content-" + md5 + "-" + chunk, 1.0d);
    }

    @Test
    void documentsAppearingInBothBranchesRankAboveSingleBranchLeaders() {
        List<SearchResult> knn = List.of(hit("a", 1), hit("b", 1), hit("c", 1));
        List<SearchResult> bm25 = List.of(hit("b", 1), hit("a", 1), hit("d", 1));

        List<SearchResult> fused = RrfFuser.fuse(knn, bm25, 60, 10);

        // a 与 b 两路都命中且总分相同（1/61+1/62），按稳定顺序保持 a 在前；
        // c 与 d 单路命中（1/63）并列，kNN 路的 c 在前
        assertEquals(List.of("a", "b", "c", "d"),
                fused.stream().map(SearchResult::getFileMd5).toList());
    }

    @Test
    void fusionScoreOverwritesOriginalScores() {
        List<SearchResult> knn = List.of(hit("a", 1));
        List<SearchResult> bm25 = List.of(hit("a", 1));

        List<SearchResult> fused = RrfFuser.fuse(knn, bm25, 60, 10);

        // 两路都是第 1 名：1/(60+1) + 1/(60+1)
        assertEquals(2.0d / 61, fused.get(0).getScore(), 1e-9);
    }

    @Test
    void singleBranchFallsBackToItsOwnRanking() {
        List<SearchResult> knn = List.of(hit("a", 1), hit("b", 1));

        List<SearchResult> fused = RrfFuser.fuse(knn, List.of(), 60, 10);

        assertEquals(List.of("a", "b"), fused.stream().map(SearchResult::getFileMd5).toList());
        assertEquals(1.0d / 61, fused.get(0).getScore(), 1e-9);
        assertEquals(1.0d / 62, fused.get(1).getScore(), 1e-9);
    }

    @Test
    void limitTruncatesFusedResults() {
        List<SearchResult> knn = List.of(hit("a", 1), hit("b", 1), hit("c", 1));
        List<SearchResult> bm25 = List.of(hit("d", 1), hit("e", 1));

        List<SearchResult> fused = RrfFuser.fuse(knn, bm25, 60, 2);

        // a 与 d 都是各路第 1 名（1/61）并列，按插入顺序 a 在前
        assertEquals(2, fused.size());
        assertEquals(List.of("a", "d"), fused.stream().map(SearchResult::getFileMd5).toList());
    }

    @Test
    void nullOrEmptyInputsYieldEmptyList() {
        assertTrue(RrfFuser.fuse(null, null, 60, 10).isEmpty());
        assertTrue(RrfFuser.fuse(List.of(), List.of(), 60, 10).isEmpty());
        assertEquals(List.of("a"),
                RrfFuser.fuse(null, List.of(hit("a", 1)), 60, 10)
                        .stream().map(SearchResult::getFileMd5).toList());
    }

    @Test
    void sameDocumentInBothBranchesCountsTwiceNotDuplicates() {
        List<SearchResult> knn = List.of(hit("a", 1));
        List<SearchResult> bm25 = List.of(hit("a", 1));

        List<SearchResult> fused = RrfFuser.fuse(knn, bm25, 60, 10);

        assertEquals(1, fused.size());
    }
}
