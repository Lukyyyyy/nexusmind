UPDATE users SET email = LOWER(TRIM(email)) WHERE email IS NOT NULL;

ALTER TABLE users ADD UNIQUE INDEX ux_users_email (email);
ALTER TABLE email_verification_tokens
    MODIFY user_id BIGINT NULL,
    ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0;
