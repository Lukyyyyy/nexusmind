package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);
    List<EmailVerificationToken> findByUserIdAndPurposeAndUsedAtIsNull(Long userId, EmailVerificationToken.Purpose purpose);
    List<EmailVerificationToken> findByEmailAndUserIsNullAndPurposeAndUsedAtIsNull(String email, EmailVerificationToken.Purpose purpose);
    Optional<EmailVerificationToken> findTopByEmailAndUserIsNullAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
            String email, EmailVerificationToken.Purpose purpose);
    Optional<EmailVerificationToken> findTopByEmailAndUserIdAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
            String email, Long userId, EmailVerificationToken.Purpose purpose);
    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);
    long countByEmailAndCreatedAtAfter(String email, LocalDateTime after);
    boolean existsByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);
    boolean existsByEmailAndCreatedAtAfter(String email, LocalDateTime after);
}
