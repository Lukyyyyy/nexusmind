package com.luky.nexusmind.service;

import com.luky.nexusmind.entity.EsDocument;
import com.luky.nexusmind.model.DocumentVector;
import org.junit.jupiter.api.Test;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FileIndexRecoveryTest {
    @Test void sameCountDoesNotAcceptDifferentContentModelOrPermissions() {
        var chunk = new DocumentVector();
        chunk.setFileMd5("md5"); chunk.setChunkId(0); chunk.setTextContent("text");
        chunk.setUserId("user"); chunk.setOrgTag("org"); chunk.setPublic(true);
        var doc = new EsDocument("id", "md5", 0, "text", new float[]{1}, "model", "user", "org", true);
        assertTrue(ElasticsearchService.matchesChunk(doc, chunk, "model"));
        doc.setTextContent("old");
        assertFalse(ElasticsearchService.matchesChunk(doc, chunk, "model"));
        doc.setTextContent("text");
        assertFalse(ElasticsearchService.matchesChunk(doc, chunk, "new-model"));
        doc.setPublic(false);
        assertFalse(ElasticsearchService.matchesChunk(doc, chunk, "model"));
        doc.setPublic(true); doc.setVector(null);
        assertFalse(ElasticsearchService.matchesChunk(doc, chunk, "model"));
    }
    @Test void verifiesActualSearchResultsAndRejectsDuplicateChunkIds() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var service = new ElasticsearchService();
        ReflectionTestUtils.setField(service, "esClient", client);
        var chunk = new DocumentVector();
        chunk.setFileMd5("md5"); chunk.setChunkId(0); chunk.setTextContent("text");
        chunk.setUserId("user"); chunk.setOrgTag("org"); chunk.setPublic(true);
        var doc = new EsDocument("id", "md5", 0, "text", new float[]{1}, "model", "user", "org", true);
        @SuppressWarnings("unchecked")
        SearchResponse<EsDocument> response = mock(SearchResponse.class, RETURNS_DEEP_STUBS);
        when(response.shards().failed()).thenReturn(0);
        when(response.hits().hits()).thenReturn(List.of(Hit.of(h -> h.index("knowledge_base").id("id").source(doc))));
        when(client.search(any(SearchRequest.class), eq(EsDocument.class))).thenReturn(response);
        assertTrue(service.hasCompleteIndex("md5", List.of(chunk), "model"));
        doc.setTextContent("stale");
        assertFalse(service.hasCompleteIndex("md5", List.of(chunk), "model"));
        assertFalse(service.hasCompleteIndex("md5", List.of(chunk, chunk), "model"));
        doc.setTextContent("text");
        when(response.timedOut()).thenReturn(true);
        assertFalse(service.hasCompleteIndex("md5", List.of(chunk), "model"));
    }

    @Test void bulkWaitsForVisibilityAndPreservesPartialFailureDetails() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var service = new ElasticsearchService();
        ReflectionTestUtils.setField(service, "esClient", client);
        var traces = mock(AiTraceService.class);
        when(traces.startFileSpan(any(), any(), any(), any())).thenReturn(mock(AiTraceService.TraceSpan.class, RETURNS_SELF));
        ReflectionTestUtils.setField(service, "aiTraceService", traces);
        var response = mock(BulkResponse.class);
        var item = mock(BulkResponseItem.class);
        when(item.error()).thenReturn(ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("wrong vector dimensions")));
        when(response.errors()).thenReturn(true);
        when(response.items()).thenReturn(List.of(item));
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);
        var doc = new EsDocument("id", "md5", 0, "text", new float[]{1}, "model", "user", "org", true);
        var error = assertThrows(RuntimeException.class, () -> service.bulkIndex(List.of(doc)));
        assertTrue(error.getCause().getMessage().contains("wrong vector dimensions"));
        verify(client).bulk(argThat((BulkRequest request) -> request.refresh() == Refresh.WaitFor));
    }
}
