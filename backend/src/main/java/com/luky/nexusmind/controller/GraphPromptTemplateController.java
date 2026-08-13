package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.service.GraphPromptTemplateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/graph-prompt-templates")
public class GraphPromptTemplateController {
    private final GraphPromptTemplateService service;

    public GraphPromptTemplateController(GraphPromptTemplateService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<?> list(Authentication authentication) { return ok(service.list(authentication.getName())); }
    @PostMapping
    public ResponseEntity<?> create(Authentication authentication, @RequestBody GraphPromptTemplateService.TemplateRequest request) {
        return ok(service.save(authentication.getName(), null, request));
    }
    @PutMapping("/{id}")
    public ResponseEntity<?> update(Authentication authentication, @PathVariable Long id,
                                    @RequestBody GraphPromptTemplateService.TemplateRequest request) {
        return ok(service.save(authentication.getName(), id, request));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(Authentication authentication, @PathVariable Long id) {
        service.delete(authentication.getName(), id); return ok(Map.of());
    }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data)); }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<?> custom(CustomException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "code", exception.getStatus().value(), "message", exception.getMessage(), "data", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> error(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500, "message", exception.getMessage() == null ? "提示词模板操作失败" : exception.getMessage(),
                "data", Map.of()));
    }
}
