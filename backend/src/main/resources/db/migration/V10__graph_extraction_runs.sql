ALTER TABLE file_upload ADD COLUMN graph_batch_chars INT DEFAULT 3072,
    ADD COLUMN text_chunk_size INT DEFAULT 512,
    ADD COLUMN graph_run_token VARCHAR(64);
CREATE TABLE graph_extraction_run (
    file_id BIGINT NOT NULL PRIMARY KEY,
    token VARCHAR(64) NOT NULL,
    snapshot LONGTEXT NOT NULL,
    CONSTRAINT fk_graph_run_file FOREIGN KEY (file_id) REFERENCES file_upload(id) ON DELETE CASCADE
);
ALTER TABLE graph_candidates ADD COLUMN evidence_start INT, ADD COLUMN evidence_end INT;

-- Preserve the actual setting for existing documents, including historical larger chunks.
UPDATE file_upload f JOIN file_processing_status p
    ON p.file_md5 = f.file_md5 AND p.user_id = f.user_id
SET f.text_chunk_size = p.chunk_size,
    f.graph_batch_chars = GREATEST(3072, p.chunk_size)
WHERE p.chunk_size > 0;
