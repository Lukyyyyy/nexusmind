package com.luky.nexusmind.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentContext {
    private final String userId;
    private final Long chatSessionId;
    private final String websocketSessionId;
    private final String traceUserId;
    private final Set<String> allowedSources = ConcurrentHashMap.newKeySet();

    public AgentContext(String userId, Long chatSessionId, String websocketSessionId, String traceUserId) {
        this.userId = userId;
        this.chatSessionId = chatSessionId;
        this.websocketSessionId = websocketSessionId;
        this.traceUserId = traceUserId;
    }

    public String userId() { return userId; }
    public Long chatSessionId() { return chatSessionId; }
    public String websocketSessionId() { return websocketSessionId; }
    public String traceUserId() { return traceUserId; }

    public void allowSource(String fileMd5, Integer chunkId) {
        if (fileMd5 != null && chunkId != null) allowedSources.add(sourceId(fileMd5, chunkId));
    }

    public boolean isSourceAllowed(String fileMd5, Integer chunkId) {
        return allowedSources.contains(sourceId(fileMd5, chunkId));
    }

    public static String sourceId(String fileMd5, Integer chunkId) {
        return "kb:" + fileMd5 + ":" + chunkId;
    }
}
