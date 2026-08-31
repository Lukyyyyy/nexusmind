package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.EmailVerificationToken;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.EmailVerificationTokenRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.utils.PasswordUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class EmailVerificationService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository, UserRepository userRepository,
                                    MailService mailService) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.mailService = mailService;
    }

    @Transactional
    public void requestRegistration(String rawEmail) {
        String email = validateEmail(rawEmail);
        requireMail();
        if (userRepository.findByEmail(email).isPresent())
            throw new CustomException("该邮箱已注册，请直接登录", HttpStatus.CONFLICT);
        enforceRateLimit(email, null);
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.findByEmailAndUserIsNullAndPurposeAndUsedAtIsNull(
                email, EmailVerificationToken.Purpose.REGISTRATION).forEach(value -> value.setUsedAt(now));
        issue(null, email, EmailVerificationToken.Purpose.REGISTRATION);
    }

    @Transactional
    public void request(User user, String rawEmail) {
        String email = validateEmail(rawEmail);
        requireMail();
        userRepository.findByEmail(email).filter(found -> !found.getId().equals(user.getId()))
                .ifPresent(found -> { throw new CustomException("该邮箱已绑定其他账号", HttpStatus.CONFLICT); });
        enforceRateLimit(email, user.getId());
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.findByUserIdAndPurposeAndUsedAtIsNull(
                user.getId(), EmailVerificationToken.Purpose.EMAIL_CHANGE).forEach(value -> value.setUsedAt(now));
        issue(user, email, EmailVerificationToken.Purpose.EMAIL_CHANGE);
    }

    @Transactional
    public void requestPasswordReset(String rawEmail) {
        String email = validateEmail(rawEmail);
        requireMail();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return;
        enforceRateLimit(email, user.getId());
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.findByUserIdAndPurposeAndUsedAtIsNull(
                user.getId(), EmailVerificationToken.Purpose.PASSWORD_RESET).forEach(value -> value.setUsedAt(now));
        issue(user, email, EmailVerificationToken.Purpose.PASSWORD_RESET);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = CustomException.class)
    public Long verifyRegistrationCode(String rawEmail, String code) {
        String email = validateEmail(rawEmail);
        EmailVerificationToken value = tokenRepository
                .findTopByEmailAndUserIsNullAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
                        email, EmailVerificationToken.Purpose.REGISTRATION)
                .orElseThrow(() -> invalidCode());
        verifyCode(value, code);
        return value.getId();
    }

    @Transactional(noRollbackFor = CustomException.class)
    public User verify(User user, String rawEmail, String code) {
        String email = validateEmail(rawEmail);
        userRepository.findByEmail(email).filter(found -> !found.getId().equals(user.getId()))
                .ifPresent(found -> { throw new CustomException("该邮箱已绑定其他账号", HttpStatus.CONFLICT); });
        EmailVerificationToken value = tokenRepository
                .findTopByEmailAndUserIdAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
                        email, user.getId(), EmailVerificationToken.Purpose.EMAIL_CHANGE)
                .orElseThrow(() -> invalidCode());
        verifyCode(value, code);
        String oldEmail = user.getEmailVerifiedAt() == null ? null : user.getEmail();
        user.setEmail(email);
        user.setEmailVerifiedAt(LocalDateTime.now());
        value.setUsedAt(LocalDateTime.now());
        tokenRepository.save(value);
        User saved = userRepository.save(user);
        if (oldEmail != null && !oldEmail.equalsIgnoreCase(email)) {
            mailService.enqueueEmailChanged(oldEmail);
        }
        return saved;
    }

    public void consumeRegistrationCode(Long tokenId, String email) {
        EmailVerificationToken value = tokenRepository.findById(tokenId)
                .filter(token -> token.getUser() == null && token.getUsedAt() == null && token.getEmail().equals(email)
                        && token.getPurpose() == EmailVerificationToken.Purpose.REGISTRATION)
                .orElseThrow(() -> invalidCode());
        if (value.getExpiresAt().isBefore(LocalDateTime.now())) throw invalidCode();
        value.setUsedAt(LocalDateTime.now());
        tokenRepository.save(value);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = CustomException.class)
    public Long verifyPasswordResetCode(User user, String email, String code) {
        EmailVerificationToken value = tokenRepository
                .findTopByEmailAndUserIdAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
                        email, user.getId(), EmailVerificationToken.Purpose.PASSWORD_RESET)
                .orElseThrow(() -> invalidCode());
        verifyCode(value, code);
        return value.getId();
    }

    public void consumePasswordResetCode(Long tokenId, User user, String email) {
        EmailVerificationToken value = tokenRepository.findById(tokenId)
                .filter(token -> token.getUser() != null && token.getUser().getId().equals(user.getId())
                        && token.getUsedAt() == null && token.getEmail().equals(email)
                        && token.getPurpose() == EmailVerificationToken.Purpose.PASSWORD_RESET)
                .orElseThrow(() -> invalidCode());
        if (value.getExpiresAt().isBefore(LocalDateTime.now())) throw invalidCode();
        value.setUsedAt(LocalDateTime.now());
        tokenRepository.save(value);
    }

    public String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void issue(User user, String email, EmailVerificationToken.Purpose purpose) {
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        EmailVerificationToken value = new EmailVerificationToken();
        value.setUser(user);
        value.setEmail(email);
        value.setPurpose(purpose);
        value.setTokenHash(PasswordUtil.encode(code));
        value.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        tokenRepository.save(value);
        mailService.enqueueVerification(email, code);
    }

    private void enforceRateLimit(String email, Long userId) {
        LocalDateTime minuteAgo = LocalDateTime.now().minusMinutes(1);
        if ((userId != null && tokenRepository.existsByUserIdAndCreatedAtAfter(userId, minuteAgo))
                || tokenRepository.existsByEmailAndCreatedAtAfter(email, minuteAgo)) {
            throw new CustomException("操作频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
        }
        LocalDateTime hourAgo = LocalDateTime.now().minusHours(1);
        if ((userId != null && tokenRepository.countByUserIdAndCreatedAtAfter(userId, hourAgo) >= 5)
                || tokenRepository.countByEmailAndCreatedAtAfter(email, hourAgo) >= 5) {
            throw new CustomException("操作频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private void verifyCode(EmailVerificationToken value, String code) {
        if (value.getExpiresAt().isBefore(LocalDateTime.now()) || value.getFailedAttempts() >= 5) {
            value.setUsedAt(LocalDateTime.now());
            tokenRepository.save(value);
            throw invalidCode();
        }
        if (code == null || !code.matches("\\d{6}") || !PasswordUtil.matches(code, value.getTokenHash())) {
            value.setFailedAttempts(value.getFailedAttempts() + 1);
            if (value.getFailedAttempts() >= 5) value.setUsedAt(LocalDateTime.now());
            tokenRepository.save(value);
            throw invalidCode();
        }
    }

    private String validateEmail(String rawEmail) {
        String email = normalize(rawEmail);
        if (!EMAIL.matcher(email).matches() || email.length() > 320)
            throw new CustomException("邮箱格式不正确", HttpStatus.BAD_REQUEST);
        return email;
    }

    private void requireMail() {
        if (!mailService.isEnabled())
            throw new CustomException("邮件服务已停用", HttpStatus.SERVICE_UNAVAILABLE);
        if (!mailService.isConfigured())
            throw new CustomException("邮件服务尚未配置", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private CustomException invalidCode() {
        return new CustomException("验证码错误或已过期", HttpStatus.BAD_REQUEST);
    }

    @Deprecated
    @Transactional
    public void unbind(User user) {
        throw new CustomException("登录邮箱不能解绑，请改为更换邮箱", HttpStatus.CONFLICT);
    }
}
