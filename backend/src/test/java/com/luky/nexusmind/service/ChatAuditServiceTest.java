package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.ChatMessage;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.ChatMessageRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatAuditServiceTest {

    private User repositoryUser;
    private List<ChatMessage> repositoryMessages = List.of();
    private Object[] auditQueryArguments;
    private ChatAuditService chatAuditService;

    @BeforeEach
    void setUp() {
        UserRepository users = proxy(UserRepository.class, (method, args) -> switch (method) {
            case "findByUsername" -> Optional.ofNullable(repositoryUser);
            default -> defaultValue(method);
        });
        ChatMessageRepository messages = proxy(ChatMessageRepository.class, (method, args) -> switch (method) {
            case "findAuditMessages" -> {
                auditQueryArguments = args;
                yield repositoryMessages;
            }
            default -> defaultValue(method);
        });
        chatAuditService = new ChatAuditService(messages, users);
    }

    @Test
    void queriesDatabaseAndIncludesTheWholeEndDate() {
        repositoryUser = user(7L, "admin");
        repositoryMessages = List.of(message(repositoryUser, LocalDateTime.of(2026, 8, 13, 15, 30)));

        var result = chatAuditService.getUserMessages("admin", "2026-08-06", "2026-08-14");

        assertEquals(1, result.size());
        assertEquals("hello", result.get(0).get("content"));
        assertEquals(LocalDateTime.of(2026, 8, 13, 15, 30), result.get(0).get("timestamp"));
        assertEquals(7L, auditQueryArguments[0]);
        assertEquals(LocalDateTime.of(2026, 8, 6, 0, 0), auditQueryArguments[1]);
        assertEquals(LocalDateTime.of(2026, 8, 15, 0, 0), auditQueryArguments[2]);
    }

    @Test
    void adminResultContainsUsernameAndSupportsAllUsers() {
        User user = user(8L, "alice");
        repositoryMessages = List.of(message(user, LocalDateTime.of(2026, 8, 13, 9, 0)));

        var result = chatAuditService.getMessages(null, null, null);

        assertEquals("alice", result.get(0).get("username"));
        assertEquals(null, auditQueryArguments[0]);
        assertEquals(null, auditQueryArguments[1]);
        assertEquals(null, auditQueryArguments[2]);
    }

    @Test
    void rejectsAnInvalidDateRange() {
        CustomException exception = assertThrows(CustomException.class,
                () -> chatAuditService.getMessages(1L, "2026-08-14", "2026-08-13"));

        assertEquals("结束时间必须晚于起始时间", exception.getMessage());
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private ChatMessage message(User user, LocalDateTime createdAt) {
        ChatSession session = new ChatSession();
        session.setUser(user);
        ChatMessage message = new ChatMessage();
        message.setId(11L);
        message.setSession(session);
        message.setRole("user");
        message.setContent("hello");
        message.setStatus("finished");
        message.setCreatedAt(createdAt);
        return message;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }

    private Object defaultValue(String method) {
        if ("toString".equals(method)) return "repository-proxy";
        if ("hashCode".equals(method)) return System.identityHashCode(this);
        if ("equals".equals(method)) return false;
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }
}
