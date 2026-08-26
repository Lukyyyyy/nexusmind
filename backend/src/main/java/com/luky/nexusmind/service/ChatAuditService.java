package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.ChatMessage;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.ChatMessageRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatAuditService {

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;

    public ChatAuditService(ChatMessageRepository chatMessageRepository, UserRepository userRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUserMessages(String username, String startDate, String endDate) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        return getMessages(user.getId(), startDate, endDate, false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMessages(Long userId, String startDate, String endDate) {
        return getMessages(userId, startDate, endDate, true);
    }

    private List<Map<String, Object>> getMessages(Long userId,
                                                   String startDate,
                                                   String endDate,
                                                   boolean includeUsername) {
        LocalDateTime start = parseStart(startDate);
        LocalDateTime endExclusive = parseEndExclusive(endDate);
        if (start != null && endExclusive != null && !start.isBefore(endExclusive)) {
            throw new CustomException("结束时间必须晚于起始时间", HttpStatus.BAD_REQUEST);
        }

        return chatMessageRepository.findAuditMessages(userId, start, endExclusive).stream()
                .map(message -> toAuditMessage(message, includeUsername))
                .toList();
    }

    private Map<String, Object> toAuditMessage(ChatMessage message, boolean includeUsername) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", message.getId());
        result.put("role", message.getRole());
        result.put("content", message.getContent());
        result.put("status", message.getStatus());
        result.put("agentTrace", message.getAgentTrace());
        result.put("thinkingDurationMs", message.getThinkingDurationMs());
        result.put("timestamp", message.getCreatedAt());
        result.put("sessionId", message.getSession().getId());
        result.put("scope", ChatScopeService.view(message.getSession()));
        if (includeUsername) {
            result.put("username", message.getSession().getUser().getUsername());
        }
        return result;
    }

    private LocalDateTime parseStart(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return value.length() == 10
                    ? LocalDate.parse(value).atStartOfDay()
                    : LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new CustomException("起始时间格式错误: " + value, HttpStatus.BAD_REQUEST);
        }
    }

    private LocalDateTime parseEndExclusive(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return value.length() == 10
                    ? LocalDate.parse(value).plusDays(1).atStartOfDay()
                    : LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new CustomException("结束时间格式错误: " + value, HttpStatus.BAD_REQUEST);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
