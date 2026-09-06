ALTER TABLE file_processing_status
    ADD COLUMN attempt_id VARCHAR(36) NULL,
    ADD COLUMN last_successful_stage VARCHAR(32) NULL;

UPDATE file_processing_status
SET last_successful_stage = CASE
    WHEN current_stage = 'COMPLETED' AND state = 'SUCCEEDED' THEN 'COMPLETED'
    WHEN parsed_chunk_count > 0 AND current_stage IN ('CHUNKING', 'VECTORIZING', 'INDEXING', 'COMPLETED') THEN 'CHUNKING'
    ELSE NULL END;
