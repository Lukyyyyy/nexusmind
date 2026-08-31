package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.SystemNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface SystemNotificationRepository extends JpaRepository<SystemNotification, Long> {
    Page<SystemNotification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId, Pageable pageable);
    long countByRecipientIdAndReadAtIsNull(Long recipientId);
    void deleteByCreatedAtBefore(LocalDateTime cutoff);
}
