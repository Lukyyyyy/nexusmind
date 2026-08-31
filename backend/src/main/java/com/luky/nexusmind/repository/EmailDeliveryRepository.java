package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.EmailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, Long> {
    List<EmailDelivery> findTop50ByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(EmailDelivery.Status status, LocalDateTime due);
}
