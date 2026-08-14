package com.luky.nexusmind.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RedisChatHistoryCache implements ChatHistoryCache {

    private static final Logger logger = LoggerFactory.getLogger(RedisChatHistoryCache.class);
    private static final String KEY_PREFIX = "chat:session:";
    private static final String KEY_SUFFIX = ":recent";
    private static final TypeReference<List<Map<String, String>>> HISTORY_TYPE = new TypeReference<>() {};
    private static final DefaultRedisScript<Long> APPEND_EXCHANGE_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            local history
            if not existing then
                if ARGV[5] ~= '1' then
                    return 0
                end
                history = {}
            else
                local ok, decoded = pcall(cjson.decode, existing)
                if not ok or type(decoded) ~= 'table' then
                    redis.call('DEL', KEYS[1])
                    return -1
                end
                history = decoded
            end

            local userMessage = cjson.decode(ARGV[1])
            local assistantMessage = cjson.decode(ARGV[2])
            table.insert(history, userMessage)
            table.insert(history, assistantMessage)

            local maxMessages = tonumber(ARGV[3])
            while #history > maxMessages do
                table.remove(history, 1)
            end

            redis.call('SET', KEYS[1], cjson.encode(history), 'EX', tonumber(ARGV[4]))
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisChatHistoryCache(StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${chat.history-cache.ttl:2h}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public Optional<List<Map<String, String>>> getRecentHistory(Long sessionId, int limit) {
        String key = key(sessionId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            List<Map<String, String>> history = normalize(objectMapper.readValue(json, HISTORY_TYPE));
            redisTemplate.expire(key, ttl);
            return Optional.of(tail(history, limit));
        } catch (Exception exception) {
            logger.warn("读取聊天历史缓存失败，回源数据库: sessionId={}", sessionId, exception);
            safeEvict(key);
            return Optional.empty();
        }
    }

    @Override
    public void putRecentHistory(Long sessionId, List<Map<String, String>> history) {
        String key = key(sessionId);
        try {
            List<Map<String, String>> normalized = tail(normalize(history), MAX_MESSAGES);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(normalized), ttl);
        } catch (Exception exception) {
            logger.warn("写入聊天历史缓存失败: sessionId={}", sessionId, exception);
            safeEvict(key);
        }
    }

    @Override
    public void appendExchange(Long sessionId,
                               String userMessage,
                               String assistantResponse,
                               boolean seedIfMissing) {
        String key = key(sessionId);
        try {
            Long result = redisTemplate.execute(
                    APPEND_EXCHANGE_SCRIPT,
                    List.of(key),
                    objectMapper.writeValueAsString(message("user", userMessage)),
                    objectMapper.writeValueAsString(message("assistant", assistantResponse)),
                    String.valueOf(MAX_MESSAGES),
                    String.valueOf(Math.max(1L, ttl.toSeconds())),
                    seedIfMissing ? "1" : "0");
            if (result != null && result < 0) {
                logger.warn("聊天历史缓存内容损坏，已清理: sessionId={}", sessionId);
            }
        } catch (Exception exception) {
            logger.warn("追加聊天历史缓存失败: sessionId={}", sessionId, exception);
            safeEvict(key);
        }
    }

    @Override
    public void evict(Long sessionId) {
        safeEvict(key(sessionId));
    }

    private List<Map<String, String>> normalize(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> item : history) {
            if (item == null || !item.containsKey("role") || !item.containsKey("content")) {
                throw new IllegalArgumentException("聊天历史缓存格式无效");
            }
            result.add(message(item.get("role"), item.get("content")));
        }
        return List.copyOf(result);
    }

    private List<Map<String, String>> tail(List<Map<String, String>> history, int limit) {
        int normalizedLimit = Math.min(Math.max(0, limit), MAX_MESSAGES);
        int fromIndex = Math.max(0, history.size() - normalizedLimit);
        return List.copyOf(history.subList(fromIndex, history.size()));
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private String key(Long sessionId) {
        return KEY_PREFIX + sessionId + KEY_SUFFIX;
    }

    private void safeEvict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception evictException) {
            logger.debug("清理聊天历史缓存失败: key={}", key, evictException);
        }
    }
}
