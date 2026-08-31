ALTER TABLE users
    MODIFY organization_email_enabled BIT NOT NULL DEFAULT 1;

UPDATE users SET organization_email_enabled = 1;
