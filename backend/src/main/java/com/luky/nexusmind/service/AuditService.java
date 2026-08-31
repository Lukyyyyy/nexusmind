package com.luky.nexusmind.service;

import com.luky.nexusmind.model.AuditEvent;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuditService {
    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(User actor, String action, Long targetUserId, String targetOrgTag, String reason, String ip) {
        AuditEvent event = new AuditEvent();
        event.setActor(actor);
        event.setActorUsername(actor == null ? "system" : actor.getUsername());
        event.setActorRole(actor == null ? "SYSTEM" : actor.getRole().name());
        event.setAction(action);
        event.setTargetUserId(targetUserId);
        event.setTargetOrgTag(targetOrgTag);
        event.setReason(reason);
        event.setIpAddress(ip);
        repository.save(event);
    }

    public Map<String, Object> list(Long targetUserId, String targetOrgTag, int page, int size) {
        Page<AuditEvent> values = targetUserId != null
                ? repository.findByTargetUserIdOrderByCreatedAtDesc(targetUserId, PageRequest.of(page, size))
                : targetOrgTag != null && !targetOrgTag.isBlank()
                ? repository.findByTargetOrgTagOrderByCreatedAtDesc(targetOrgTag, PageRequest.of(page, size))
                : repository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return page(values.map(this::view));
    }

    private Map<String, Object> view(AuditEvent event) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", event.getId());
        value.put("actor", event.getActorUsername());
        value.put("actorRole", event.getActorRole());
        value.put("action", event.getAction());
        value.put("targetUserId", event.getTargetUserId());
        value.put("targetOrgTag", event.getTargetOrgTag());
        value.put("reason", event.getReason());
        value.put("ipAddress", event.getIpAddress());
        value.put("createdAt", event.getCreatedAt());
        return value;
    }

    private Map<String, Object> page(Page<?> values) {
        return Map.of("content", values.getContent(), "page", values.getNumber() + 1,
                "size", values.getSize(), "totalElements", values.getTotalElements());
    }
}
