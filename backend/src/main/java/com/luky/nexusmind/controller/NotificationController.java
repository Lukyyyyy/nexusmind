package com.luky.nexusmind.controller;

import com.luky.nexusmind.handler.NotificationWebSocketHandler;
import com.luky.nexusmind.service.NotificationService;
import com.luky.nexusmind.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;
    private final NotificationWebSocketHandler socketHandler;
    private final JwtUtils jwtUtils;

    public NotificationController(NotificationService service, NotificationWebSocketHandler socketHandler, JwtUtils jwtUtils) {
        this.service = service;
        this.socketHandler = socketHandler;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String token,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ok(service.list(username(token), page, size));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> read(@RequestHeader("Authorization") String token, @PathVariable Long id) {
        service.markRead(username(token), id);
        return message("已读");
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> readAll(@RequestHeader("Authorization") String token) {
        service.markAllRead(username(token));
        return message("全部已读");
    }

    @PostMapping("/socket-ticket")
    public ResponseEntity<?> ticket(@RequestHeader("Authorization") String token) {
        return ok(Map.of("ticket", socketHandler.issueTicket(username(token))));
    }

    private String username(String token) { return jwtUtils.extractUsernameFromToken(token.replace("Bearer ", "")); }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "成功", "data", data)); }
    private ResponseEntity<?> message(String value) { return ResponseEntity.ok(Map.of("code", 200, "message", value)); }
}
