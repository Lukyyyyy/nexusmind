ALTER TABLE email_verification_tokens
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'EMAIL_CHANGE' AFTER email;

UPDATE email_verification_tokens
SET purpose = IF(user_id IS NULL, 'REGISTRATION', 'EMAIL_CHANGE');
