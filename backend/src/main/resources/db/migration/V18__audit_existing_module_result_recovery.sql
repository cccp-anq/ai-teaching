-- An authorized recovery may attach a new local attempt to an existing provider job.
-- The original failed local attempt and its client request ID remain unchanged.
CREATE UNIQUE INDEX uq_classroom_job_client_request_attempt
    ON classroom_module_job (client_request_id,attempt);
ALTER TABLE classroom_module_job DROP INDEX uq_classroom_job_client_request;
ALTER TABLE classroom_module_job ADD COLUMN recovery_source_job_id BIGINT NULL;
ALTER TABLE classroom_module_job ADD CONSTRAINT fk_classroom_job_recovery_source
    FOREIGN KEY (recovery_source_job_id) REFERENCES classroom_module_job(id);
CREATE UNIQUE INDEX uq_classroom_job_recovery_source
    ON classroom_module_job (recovery_source_job_id);
ALTER TABLE classroom_module_retry_request
    ADD COLUMN operation_type VARCHAR(16) NOT NULL DEFAULT 'REBUILD';
