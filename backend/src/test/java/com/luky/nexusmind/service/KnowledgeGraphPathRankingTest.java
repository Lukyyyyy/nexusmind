package com.luky.nexusmind.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeGraphPathRankingTest {

    @Test
    void ranksRelevantCrossDocumentEndpointAheadOfUnrelatedOneHopPath() {
        KnowledgeGraphStoreService.GraphPath related = path(
                List.of(node("order", "订单系统"), node("service", "支付服务"), node("redis", "Redis")),
                List.of(
                        relation("claim-1", "order", "service", "调用", 7L, 0.92, 0.88),
                        relation("claim-2", "service", "redis", "依赖", 8L, 0.91, 0.90)
                )
        );
        KnowledgeGraphStoreService.GraphPath unrelated = path(
                List.of(node("order", "订单系统"), node("owner", "研发一组")),
                List.of(relation("claim-3", "order", "owner", "负责人", 7L, 0.98, 0.98))
        );

        List<KnowledgeGraphStoreService.GraphPath> ranked = KnowledgeGraphStoreService.rankPaths(
                "订单系统使用哪个 Redis 依赖", Map.of("order", 1.0), List.of(unrelated, related), 10);

        assertEquals(2, ranked.size());
        assertEquals("redis", ranked.get(0).nodes().get(2).get("key"));
        assertEquals(2, ranked.get(0).hops());
        assertTrue(ranked.get(0).crossDocument());
        assertTrue(ranked.get(0).inferred());
        assertEquals("RANKED_RELEVANCE", ranked.get(0).terminalReason());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    @Test
    void deduplicatesReverseTraversalByClaimEvidence() {
        KnowledgeGraphStoreService.GraphPath forward = path(
                List.of(node("a", "系统 A"), node("b", "系统 B"), node("c", "系统 C")),
                List.of(
                        relation("claim-10", "a", "b", "调用", 7L, 0.9, 0.9),
                        relation("claim-11", "b", "c", "依赖", 8L, 0.9, 0.9)
                )
        );
        KnowledgeGraphStoreService.GraphPath reverse = path(
                List.of(node("c", "系统 C"), node("b", "系统 B"), node("a", "系统 A")),
                List.of(
                        relation("claim-11", "b", "c", "依赖", 8L, 0.9, 0.9),
                        relation("claim-10", "a", "b", "调用", 7L, 0.9, 0.9)
                )
        );

        List<KnowledgeGraphStoreService.GraphPath> ranked = KnowledgeGraphStoreService.rankPaths(
                "系统 A 和系统 C 的关系", Map.of("a", 1.0, "c", 0.95), List.of(forward, reverse), 10);

        assertEquals(1, ranked.size());
        assertEquals(2, ranked.get(0).relations().size());
        assertTrue(ranked.get(0).crossDocument());
    }

    private KnowledgeGraphStoreService.GraphPath path(List<Map<String, Object>> nodes,
                                                       List<Map<String, Object>> relations) {
        return new KnowledgeGraphStoreService.GraphPath(nodes, relations);
    }

    private Map<String, Object> node(String key, String name) {
        return Map.of("key", key, "name", name, "type", "SYSTEM");
    }

    private Map<String, Object> relation(String claimKey, String source, String target,
                                         String predicate, Long fileId,
                                         double confidence, double valueScore) {
        return Map.of(
                "claimKey", claimKey,
                "source", source,
                "target", target,
                "predicate", predicate,
                "fileUploadId", fileId,
                "confidence", confidence,
                "valueScore", valueScore
        );
    }
}
