package com.luky.nexusmind.service;

import static org.junit.jupiter.api.Assertions.*;

import com.luky.nexusmind.model.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class GraphBatchPlanTest {
    private DocumentVector chunk(int id, String text) {
        var c = new DocumentVector();
        c.setChunkId(id);
        c.setTextContent(text);
        return c;
    }

    @Test
    void coversEveryCharacterOnceWithinLimitAndPreservesCoordinates() {
        String text = "这是第一句。".repeat(900);
        var batches = GraphBatchPlan.create(List.of(chunk(2, "末尾"), chunk(1, text)), 3072);
        var parts =
                batches.stream()
                        .flatMap(b -> b.parts().stream())
                        .filter(p -> p.chunkId() == 1)
                        .toList();
        assertEquals(
                text, parts.stream().map(GraphBatchPlan.Part::text).reduce("", String::concat));
        parts.forEach(p -> assertEquals(p.text(), text.substring(p.start(), p.end())));
        batches.forEach(
                b -> {
                    assertTrue(b.parts().stream().mapToInt(p -> p.text().length()).sum() <= 3072);
                    assertTrue(b.before().length() <= 1000);
                    assertTrue(b.after().length() <= 1000);
                });
    }

    @Test
    void splitRetryPreservesOriginalBatchAndExactRanges() {
        var batch =
                GraphBatchPlan.create(List.of(chunk(1, "abcdef"), chunk(2, "ghijk")), 20).get(0);
        var halves = GraphBatchPlan.halve(batch);
        assertEquals(2, halves.size());
        assertEquals(
                "abcdefghijk",
                halves.stream()
                        .flatMap(b -> b.parts().stream())
                        .map(GraphBatchPlan.Part::text)
                        .reduce("", String::concat));
        assertTrue(halves.stream().allMatch(b -> b.index() == batch.index()));
        assertEquals(5, halves.get(1).parts().get(0).start());
    }

    @Test
    void validatesBatchAgainstStoredChunkSize() {
        var file = new FileUpload();
        file.setTextChunkSize(1024);
        assertThrows(RuntimeException.class, () -> GraphExtractionEngine.validateBatch(file, 512));
        GraphExtractionEngine.validateBatch(file, 3072);
        assertEquals(3072, file.getGraphBatchChars());
    }
}
