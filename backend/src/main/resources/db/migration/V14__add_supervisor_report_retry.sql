-- A supervisor may explicitly request a new, possibly chargeable report attempt.
-- Keep prior runs and reservations immutable; only the current dispatch pointer is requeued.
ALTER TABLE classroom_report_dispatch ADD COLUMN manual_generation INT NOT NULL DEFAULT 0;

CREATE TABLE classroom_report_retry_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id BIGINT NOT NULL,
    generation INT NOT NULL,
    previous_run_id BIGINT NULL,
    requested_by_user_id BIGINT NOT NULL,
    request_key_sha256 CHAR(64) NOT NULL,
    previous_error_code VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_retry_dispatch FOREIGN KEY (dispatch_id) REFERENCES classroom_report_dispatch(id),
    CONSTRAINT fk_report_retry_previous_run FOREIGN KEY (previous_run_id) REFERENCES classroom_analysis_run(id),
    CONSTRAINT fk_report_retry_user FOREIGN KEY (requested_by_user_id) REFERENCES user_account(id),
    CONSTRAINT ck_report_retry_generation CHECK (generation > 0)
);
CREATE UNIQUE INDEX uq_report_retry_generation ON classroom_report_retry_request (dispatch_id,generation);
CREATE UNIQUE INDEX uq_report_retry_key ON classroom_report_retry_request (dispatch_id,request_key_sha256);
