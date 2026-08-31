package com.luky.nexusmind.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NotificationWebSocketHandler extends TextWebSocketHandler implements MessageListener {
    private static final String TICKET_PREFIX = "notification:ticket:";
    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public NotificationWebSocketHandler(StringRedisTemplate redis, UserRepository userRepository, ObjectMapper objectMapper) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    public String issueTicket(String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(TICKET_PREFIX + ticket, username, Duration.ofSeconds(60));
        return ticket;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ticket = lastPathSegment(session);
        String username = redis.opsForValue().getAndDelete(TICKET_PREFIX + ticket);
        Long userId = username == null ? null : userRepository.findByUsername(username).map(user -> user.getId()).orElse(null);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put("notificationUserId", userId);
        sessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object userId = session.getAttributes().get("notificationUserId");
        if (userId instanceof Long id) {
            Set<WebSocketSession> values = sessions.get(id);
            if (values != null) {
                values.remove(session);
                if (values.isEmpty()) sessions.remove(id);
            }
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(payload);
            Set<WebSocketSession> targets = sessions.get(json.path("userId").asLong());
            if (targets == null) return;
            TextMessage outbound = new TextMessage(payload);
            for (WebSocketSession session : targets) {
                if (session.isOpen()) synchronized (session) { session.sendMessage(outbound); }
            }
        } catch (Exception ignored) {
            // Durable notification history repairs any missed real-time event.
        }
    }

    private String lastPathSegment(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
