-- Preserve terminal module attempts while allowing a supervisor to rebuild failed jobs.
CREATE UNIQUE INDEX uq_classroom_job_media_type_attempt
    ON classroom_module_job (media_id,module_type,attempt);
ALTER TABLE classroom_module_job DROP INDEX uq_classroom_job_media_type;

CREATE TABLE classroom_module_retry_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    generation INT NOT NULL,
    previous_transcript_job_id BIGINT NOT NULL,
    previous_head_up_job_id BIGINT NOT NULL,
    replacement_transcript_job_id BIGINT NULL,
    replacement_head_up_job_id BIGINT NULL,
    requested_by_user_id BIGINT NOT NULL,
    request_key_sha256 CHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_module_retry_report FOREIGN KEY (report_id) REFERENCES classroom_analysis_report(id),
    CONSTRAINT fk_module_retry_transcript_previous FOREIGN KEY (previous_transcript_job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT fk_module_retry_head_up_previous FOREIGN KEY (previous_head_up_job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT fk_module_retry_transcript_replacement FOREIGN KEY (replacement_transcript_job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT fk_module_retry_head_up_replacement FOREIGN KEY (replacement_head_up_job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT fk_module_retry_user FOREIGN KEY (requested_by_user_id) REFERENCES user_account(id),
    CONSTRAINT ck_module_retry_generation CHECK (generation > 0)
);
CREATE UNIQUE INDEX uq_module_retry_generation ON classroom_module_retry_request (report_id,generation);
CREATE UNIQUE INDEX uq_module_retry_key ON classroom_module_retry_request (report_id,request_key_sha256);
