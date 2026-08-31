ALTER TABLE users ADD COLUMN display_name VARCHAR(32) NULL COMMENT '昵称，用于展示' AFTER username;
UPDATE users SET display_name = username WHERE display_name IS NULL OR TRIM(display_name) = '';
ALTER TABLE users MODIFY display_name VARCHAR(32) NOT NULL COMMENT '昵称，用于展示';
