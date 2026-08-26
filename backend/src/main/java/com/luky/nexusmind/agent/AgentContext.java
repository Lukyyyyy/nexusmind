package com.luky.nexusmind.agent;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentContext {
    private static final Pattern INCOMPLETE_SOURCE = Pattern.compile("kb:([a-fA-F\\d]{32,64})(?!:\\d)");
    private final String userId;
    private final Long chatSessionId;
    private final String websocketSessionId;
    private final String traceUserId;
    private final List<Long> scopeFileIds;
    private final Set<String> scopeFileMd5s;
    private final Set<String> allowedSources = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Integer> firstChunkByFile = new ConcurrentHashMap<>();

    public AgentContext(String userId, Long chatSessionId, String websocketSessionId, String traceUserId) {
        this(userId, chatSessionId, websocketSessionId, traceUserId, List.of(), Set.of());
    }

    public AgentContext(String userId, Long chatSessionId, String websocketSessionId, String traceUserId,
                        Collection<Long> scopeFileIds, Collection<String> scopeFileMd5s) {
        this.userId = userId;
        this.chatSessionId = chatSessionId;
        this.websocketSessionId = websocketSessionId;
        this.traceUserId = traceUserId;
        this.scopeFileIds = scopeFileIds == null ? List.of() : scopeFileIds.stream().distinct().toList();
        this.scopeFileMd5s = scopeFileMd5s == null ? Set.of() : Set.copyOf(scopeFileMd5s);
    }

    public String userId() { return userId; }
    public Long chatSessionId() { return chatSessionId; }
    public String websocketSessionId() { return websocketSessionId; }
    public String traceUserId() { return traceUserId; }
    public List<Long> scopeFileIds() { return scopeFileIds; }
    public Set<String> scopeFileMd5s() { return scopeFileMd5s; }

    public boolean isFileInScope(String fileMd5) {
        return fileMd5 != null && scopeFileMd5s.contains(fileMd5);
    }

    public void allowSource(String fileMd5, Integer chunkId) {
        if (fileMd5 != null && chunkId != null) {
            allowedSources.add(sourceId(fileMd5, chunkId));
            firstChunkByFile.putIfAbsent(fileMd5.toLowerCase(), chunkId);
        }
    }

    public boolean isSourceAllowed(String fileMd5, Integer chunkId) {
        return allowedSources.contains(sourceId(fileMd5, chunkId));
    }

    public int allowedSourceCount() {
        return allowedSources.size();
    }

    public static String sourceId(String fileMd5, Integer chunkId) {
        return "kb:" + fileMd5 + ":" + chunkId;
    }

    public String repairIncompleteSourceIds(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher matcher = INCOMPLETE_SOURCE.matcher(text);
        StringBuffer repaired = new StringBuffer();
        while (matcher.find()) {
            String fileMd5 = matcher.group(1);
            Integer chunkId = firstChunkByFile.get(fileMd5.toLowerCase());
            // ponytail: malformed citations use the first-ranked retrieved chunk; use structured citations if exact claim mapping is required.
            String replacement = chunkId == null ? matcher.group() : sourceId(fileMd5, chunkId);
            matcher.appendReplacement(repaired, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(repaired).toString();
    }
}
