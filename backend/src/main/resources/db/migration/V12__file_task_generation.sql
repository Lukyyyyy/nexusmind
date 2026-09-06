-- 保留删除代次，拒绝迟到的分片、合并和旧处理结果。删除后重新上传使用新代次。
CREATE TABLE file_task_generation (
    file_md5 VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    deleting BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (file_md5, user_id)
);
