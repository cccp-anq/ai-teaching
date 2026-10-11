-- A validated AI draft is private and versioned by its immutable analysis run.
-- This does not expose the report or change the objective classroom_analysis_report row.
CREATE TABLE classroom_generated_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    reservation_id BIGINT NULL,
    input_sha256 CHAR(64) NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    response_sha256 CHAR(64) NOT NULL,
    report_version VARCHAR(16) NOT NULL,
    normalized_sha256 CHAR(64) NOT NULL,
    normalized_json LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_generated_report_run FOREIGN KEY (run_id) REFERENCES classroom_analysis_run(id),
    CONSTRAINT fk_generated_report_reservation FOREIGN KEY (reservation_id) REFERENCES classroom_paid_test_reservation(id)
);
CREATE UNIQUE INDEX uq_generated_report_run ON classroom_generated_report (run_id);
CREATE UNIQUE INDEX uq_generated_report_reservation ON classroom_generated_report (reservation_id);
