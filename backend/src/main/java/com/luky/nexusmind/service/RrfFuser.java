package com.luky.nexusmind.service;

import com.luky.nexusmind.entity.SearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：score(d) = Σ 1 / (k + rank_i(d))。
 * 按排名而非分数融合，量纲无关、无需归一化；两路都靠前的文档获得更高融合分。
 * 纯函数，无外部依赖，便于单元测试。
 */
public final class RrfFuser {

    private RrfFuser() {
    }

    /**
     * @param primary   第一路召回（如 kNN），须已按相关性降序排列
     * @param secondary 第二路召回（如 BM25），须已按相关性降序排列
     * @param k         平滑常数（业界默认 60，越大各排名差距越平缓）
     * @param limit     最多返回条数（<=0 表示不限制）
     */
    public static List<SearchResult> fuse(List<SearchResult> primary, List<SearchResult> secondary, int k, int limit) {
        List<SearchResult> first = primary == null ? List.of() : primary;
        List<SearchResult> second = secondary == null ? List.of() : secondary;
        if (first.isEmpty() && second.isEmpty()) {
            return List.of();
        }

        Map<String, Double> scores = new HashMap<>();
        Map<String, SearchResult> hits = new LinkedHashMap<>();
        accumulate(first, k, scores, hits);
        accumulate(second, k, scores, hits);

        List<String> orderedKeys = new ArrayList<>(hits.keySet());
        // List.sort 为稳定排序：融合分相同时保持插入顺序（先按第一路排名，再按第二路）
        orderedKeys.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        List<SearchResult> fused = new ArrayList<>();
        for (String key : orderedKeys) {
            if (limit > 0 && fused.size() >= limit) {
                break;
            }
            SearchResult hit = hits.get(key);
            fused.add(new SearchResult(
                    hit.getFileMd5(),
                    hit.getChunkId(),
                    hit.getTextContent(),
                    scores.get(key),
                    hit.getUserId(),
                    hit.getOrgTag(),
                    Boolean.TRUE.equals(hit.getIsPublic()),
                    hit.getFileName()));
        }
        return fused;
    }

    private static void accumulate(List<SearchResult> ranked, int k,
                                   Map<String, Double> scores, Map<String, SearchResult> hits) {
        for (int i = 0; i < ranked.size(); i++) {
            SearchResult hit = ranked.get(i);
            if (hit == null || hit.getFileMd5() == null) {
                continue;
            }
            String key = hit.getFileMd5() + ":" + hit.getChunkId();
            int rank = i + 1;
            scores.merge(key, 1.0d / (k + rank), Double::sum);
            hits.putIfAbsent(key, hit);
        }
    }
}
