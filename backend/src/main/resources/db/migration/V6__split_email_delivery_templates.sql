UPDATE email_deliveries
SET status = 'FAILED', last_error = '旧通用邮件模板已停用'
WHERE template_kind = 'NOTIFICATION' AND status = 'PENDING';

UPDATE email_deliveries SET template_kind = 'TEST' WHERE template_kind = 'NOTIFICATION';

ALTER TABLE email_deliveries
    MODIFY COLUMN template_kind VARCHAR(32) NOT NULL DEFAULT 'TEST';
