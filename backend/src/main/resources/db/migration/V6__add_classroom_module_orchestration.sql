-- Durable orchestration metadata and immutable module result snapshots.
ALTER TABLE classroom_module_job ADD COLUMN client_request_id VARCHAR(128) NULL;
ALTER TABLE classroom_module_job ADD COLUMN provider_job_id VARCHAR(128) NULL;
ALTER TABLE classroom_module_job ADD COLUMN attempt INT NOT NULL DEFAULT 1;
ALTER TABLE classroom_module_job ADD COLUMN next_poll_at DATETIME NULL;
ALTER TABLE classroom_module_job ADD COLUMN lease_owner VARCHAR(128) NULL;
ALTER TABLE classroom_module_job ADD COLUMN lease_until DATETIME NULL;
ALTER TABLE classroom_module_job ADD COLUMN last_integration_error VARCHAR(1000) NULL;

CREATE UNIQUE INDEX uq_classroom_job_client_request ON classroom_module_job (client_request_id);
CREATE INDEX idx_classroom_job_due ON classroom_module_job (status, next_poll_at, lease_until);

CREATE TABLE classroom_module_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    revision INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    envelope_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_classroom_result_job FOREIGN KEY (job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT uq_classroom_result_revision UNIQUE (job_id, revision)
);

CREATE TABLE classroom_processed_range (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    result_id BIGINT NOT NULL,
    range_index INT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    CONSTRAINT fk_classroom_range_result FOREIGN KEY (result_id) REFERENCES classroom_module_result(id),
    CONSTRAINT uq_classroom_range_index UNIQUE (result_id, range_index),
    CONSTRAINT ck_classroom_range_time CHECK (start_ms >= 0 AND end_ms > start_ms)
);

ALTER TABLE classroom_transcript_segment ADD COLUMN confidence DOUBLE NULL;
ALTER TABLE classroom_transcript_segment ADD COLUMN speaker_label VARCHAR(128) NULL;
ALTER TABLE classroom_head_up_window ADD COLUMN sample_at_ms BIGINT NULL;
ALTER TABLE classroom_head_up_window ADD COLUMN head_up_ratio DOUBLE NULL;
