package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.service.ChatAuditService;
import com.luky.nexusmind.utils.JwtUtils;
import com.luky.nexusmind.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/conversation")
public class ConversationController {

    private final JwtUtils jwtUtils;
    private final ChatAuditService chatAuditService;

    public ConversationController(JwtUtils jwtUtils, ChatAuditService chatAuditService) {
        this.jwtUtils = jwtUtils;
        this.chatAuditService = chatAuditService;
    }

    @GetMapping
    public ResponseEntity<?> getConversations(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_CONVERSATIONS");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isBlank()) {
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            var conversations = chatAuditService.getUserMessages(username, start_date, end_date);
            LogUtils.logBusiness("GET_CONVERSATIONS", username, "从数据库获取到 %d 条对话记录", conversations.size());
            LogUtils.logUserOperation(username, "GET_CONVERSATIONS", "conversation_history", "SUCCESS");
            monitor.end("获取对话历史成功");
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "获取对话历史成功",
                    "data", conversations));
        } catch (CustomException exception) {
            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "获取对话历史失败: %s", exception, exception.getMessage());
            monitor.end("获取对话历史失败: " + exception.getMessage());
            return ResponseEntity.status(exception.getStatus()).body(Map.of(
                    "code", exception.getStatus().value(),
                    "message", exception.getMessage()));
        } catch (Exception exception) {
            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "获取对话历史异常: %s", exception, exception.getMessage());
            monitor.end("获取对话历史异常: " + exception.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 500,
                    "message", "服务器内部错误: " + exception.getMessage()));
        }
    }
}
