SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'email') = 0,
    'ALTER TABLE users ADD COLUMN email VARCHAR(320) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'email_verified_at') = 0,
    'ALTER TABLE users ADD COLUMN email_verified_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'organization_email_enabled') = 0,
    'ALTER TABLE users ADD COLUMN organization_email_enabled BIT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE users MODIFY COLUMN role ENUM('USER', 'ADMIN', 'SUPER_ADMIN') NOT NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_tags' AND COLUMN_NAME = 'joinable') = 0,
    'ALTER TABLE organization_tags ADD COLUMN joinable BIT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_tags' AND COLUMN_NAME = 'archived_at') = 0,
    'ALTER TABLE organization_tags ADD COLUMN archived_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_tags' AND COLUMN_NAME = 'archive_reason') = 0,
    'ALTER TABLE organization_tags ADD COLUMN archive_reason VARCHAR(200) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE organization_tags SET joinable = 0 WHERE tag_id IN ('default', 'admin') OR tag_id LIKE 'PRIVATE\\_%';

CREATE TABLE IF NOT EXISTS organization_memberships (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    org_tag_id VARCHAR(255) NOT NULL,
    source VARCHAR(24) NOT NULL,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_membership_user_org UNIQUE (user_id, org_tag_id),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_membership_org FOREIGN KEY (org_tag_id) REFERENCES organization_tags(tag_id)
);

CREATE TABLE IF NOT EXISTS organization_join_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    org_tag_id VARCHAR(255) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL,
    handled_by BIGINT NULL,
    decision_reason VARCHAR(200) NULL,
    handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_join_request_status (status, created_at),
    INDEX idx_join_request_user_org (user_id, org_tag_id),
    CONSTRAINT fk_join_request_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_join_request_org FOREIGN KEY (org_tag_id) REFERENCES organization_tags(tag_id),
    CONSTRAINT fk_join_request_handler FOREIGN KEY (handled_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS system_notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    type VARCHAR(48) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content VARCHAR(500) NOT NULL,
    link VARCHAR(300) NULL,
    read_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notification_recipient (recipient_id, read_at, created_at),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS audit_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NULL,
    actor_username VARCHAR(255) NOT NULL,
    actor_role VARCHAR(24) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_user_id BIGINT NULL,
    target_org_tag VARCHAR(255) NULL,
    reason VARCHAR(200) NULL,
    ip_address VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_actor (actor_id, created_at),
    INDEX idx_audit_target_user (target_user_id, created_at),
    INDEX idx_audit_target_org (target_org_tag, created_at),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    email VARCHAR(320) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_email_token_hash (token_hash),
    INDEX idx_email_token_target (user_id, email, created_at),
    CONSTRAINT fk_email_token_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS smtp_settings (
    id BIGINT PRIMARY KEY,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password VARCHAR(1000) NOT NULL,
    from_address VARCHAR(320) NOT NULL,
    ssl_enabled BIT NOT NULL DEFAULT 1,
    enabled BIT NOT NULL DEFAULT 1,
    updated_at DATETIME NULL
);

CREATE TABLE IF NOT EXISTS email_deliveries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipient VARCHAR(320) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_email_delivery_due (status, next_attempt_at)
);
