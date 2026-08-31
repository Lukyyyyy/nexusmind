package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    Page<AuditEvent> findByTargetUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Page<AuditEvent> findByTargetOrgTagOrderByCreatedAtDesc(String orgTag, Pageable pageable);
}
