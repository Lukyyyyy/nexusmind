package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.EmailVerificationService;
import com.luky.nexusmind.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/email")
public class EmailController {
    private final EmailVerificationService service;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;

    public EmailController(EmailVerificationService service, UserRepository userRepository, JwtUtils jwtUtils) {
        this.service = service;
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping("/profile")
    public ResponseEntity<?> profile(@RequestHeader("Authorization") String token) {
        User user = user(token);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("email", user.getEmail());
        value.put("verified", user.getEmailVerifiedAt() != null);
        value.put("organizationEmailEnabled", user.isOrganizationEmailEnabled());
        return ok(value);
    }

    @PostMapping("/verification")
    public ResponseEntity<?> request(@RequestHeader("Authorization") String token, @RequestBody EmailRequest request) {
        service.request(user(token), request.email());
        return message("验证码已发送");
    }

    @PostMapping("/verification/confirm")
    public ResponseEntity<?> verify(@RequestHeader("Authorization") String token, @RequestBody EmailCodeRequest request) {
        service.verify(user(token), request.email(), request.verificationCode());
        return message("邮箱验证成功");
    }

    @DeleteMapping
    public ResponseEntity<?> unbind(@RequestHeader("Authorization") String token) {
        service.unbind(user(token));
        return message("邮箱已解绑");
    }

    @PutMapping("/preferences")
    public ResponseEntity<?> preferences(@RequestHeader("Authorization") String token, @RequestBody EmailPreferenceRequest request) {
        User user = user(token);
        user.setOrganizationEmailEnabled(request.organizationEmailEnabled());
        userRepository.save(user);
        return message("通知偏好已保存");
    }

    private User user(String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        return userRepository.findByUsername(username).orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
    }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "成功", "data", data)); }
    private ResponseEntity<?> message(String value) { return ResponseEntity.ok(Map.of("code", 200, "message", value)); }
}

record EmailRequest(String email) {}
record EmailCodeRequest(String email, String verificationCode) {}
record EmailPreferenceRequest(boolean organizationEmailEnabled) {}
