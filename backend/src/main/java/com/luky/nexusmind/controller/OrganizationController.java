package com.luky.nexusmind.controller;

import com.luky.nexusmind.service.OrganizationService;
import com.luky.nexusmind.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final OrganizationService service;
    private final JwtUtils jwtUtils;

    public OrganizationController(OrganizationService service, JwtUtils jwtUtils) {
        this.service = service;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> overview(@RequestHeader("Authorization") String token,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return ok(service.overview(username(token), keyword, page, size));
    }

    @GetMapping("/requests")
    public ResponseEntity<?> requests(@RequestHeader("Authorization") String token,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return ok(service.myRequests(username(token), page, size));
    }

    @PostMapping("/{tagId}/requests")
    public ResponseEntity<?> apply(@RequestHeader("Authorization") String token, @PathVariable String tagId,
                                   @RequestBody ReasonRequest request, HttpServletRequest http) {
        service.apply(username(token), tagId, request.reason(), clientIp(http));
        return message("申请已提交");
    }

    @PostMapping("/requests/{id}/withdraw")
    public ResponseEntity<?> withdraw(@RequestHeader("Authorization") String token, @PathVariable Long id,
                                      HttpServletRequest http) {
        service.withdraw(username(token), id, clientIp(http));
        return message("申请已撤回");
    }

    @DeleteMapping("/{tagId}/membership")
    public ResponseEntity<?> exit(@RequestHeader("Authorization") String token, @PathVariable String tagId,
                                  HttpServletRequest http) {
        service.exit(username(token), tagId, clientIp(http));
        return message("已退出组织");
    }

    private String username(String token) { return jwtUtils.extractUsernameFromToken(token.replace("Bearer ", "")); }
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "成功", "data", data)); }
    private ResponseEntity<?> message(String value) { return ResponseEntity.ok(Map.of("code", 200, "message", value)); }
}

record ReasonRequest(String reason) {}
