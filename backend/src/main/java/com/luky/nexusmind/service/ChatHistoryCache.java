package com.luky.nexusmind.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChatHistoryCache {

    int MAX_MESSAGES = 20;

    Optional<List<Map<String, String>>> getRecentHistory(Long sessionId, int limit);

    void putRecentHistory(Long sessionId, List<Map<String, String>> history);

    void appendExchange(Long sessionId, String userMessage, String assistantResponse, boolean seedIfMissing);

    void evict(Long sessionId);

    static ChatHistoryCache noop() {
        return new ChatHistoryCache() {
            @Override
            public Optional<List<Map<String, String>>> getRecentHistory(Long sessionId, int limit) {
                return Optional.empty();
            }

            @Override
            public void putRecentHistory(Long sessionId, List<Map<String, String>> history) {
            }

            @Override
            public void appendExchange(Long sessionId,
                                       String userMessage,
                                       String assistantResponse,
                                       boolean seedIfMissing) {
            }

            @Override
            public void evict(Long sessionId) {
            }
        };
    }
}
