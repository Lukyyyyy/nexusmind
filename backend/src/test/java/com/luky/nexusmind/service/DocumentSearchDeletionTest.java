package com.luky.nexusmind.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentSearchDeletionTest {
    @Test
    void requiresCompleteDeletionAndRefreshesSearchVisibility() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchService service = new ElasticsearchService();
        ReflectionTestUtils.setField(service, "esClient", client);
        DeleteByQueryResponse response = mock(DeleteByQueryResponse.class);
        when(response.failures()).thenReturn(List.of());
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(response);
        service.deleteByFileMd5("file");
        verify(client).deleteByQuery(argThat((DeleteByQueryRequest request) ->
                Boolean.TRUE.equals(request.refresh()) && request.query().term().value().stringValue().equals("file")));
        when(response.timedOut()).thenReturn(true);
        assertThrows(RuntimeException.class, () -> service.deleteByFileMd5("file"));
        when(response.timedOut()).thenReturn(false);
        when(response.versionConflicts()).thenReturn(1L);
        assertThrows(RuntimeException.class, () -> service.deleteByFileMd5("file"));
    }

    @Test
    void onlyMissingIndexIsAnIdempotentSuccess() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchService service = new ElasticsearchService();
        ReflectionTestUtils.setField(service, "esClient", client);
        ElasticsearchException failure = mock(ElasticsearchException.class);
        when(failure.status()).thenReturn(404);
        when(failure.error()).thenReturn(ErrorCause.of(e -> e.type("index_not_found_exception")));
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenThrow(failure);
        assertDoesNotThrow(() -> service.deleteByFileMd5("file"));
        when(failure.status()).thenReturn(503);
        assertThrows(RuntimeException.class, () -> service.deleteByFileMd5("file"));
    }
}
