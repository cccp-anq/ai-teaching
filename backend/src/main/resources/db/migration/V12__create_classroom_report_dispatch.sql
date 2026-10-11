-- Automatic M3 dispatch is persisted separately from the immutable objective report.
-- The worker is disabled unless explicitly configured. Historical READY rows are not backfilled.
CREATE TABLE classroom_report_dispatch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    objective_report_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    run_id BIGINT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    lease_owner VARCHAR(100) NULL,
    lease_until DATETIME NULL,
    error_code VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_dispatch_objective FOREIGN KEY (objective_report_id) REFERENCES classroom_analysis_report(id),
    CONSTRAINT fk_report_dispatch_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT fk_report_dispatch_run FOREIGN KEY (run_id) REFERENCES classroom_analysis_run(id)
);
CREATE UNIQUE INDEX uq_report_dispatch_objective ON classroom_report_dispatch (objective_report_id);
CREATE INDEX idx_report_dispatch_due ON classroom_report_dispatch (state,lease_until,id);

-- DEC-030's original 20 CNY test ceiling is now a configurable, explicit deployment guard.
-- Keep a finite DB-level ceiling (100 CNY) and a much smaller default configuration.
ALTER TABLE classroom_paid_test_budget DROP CONSTRAINT chk_classroom_paid_test_budget_cap;
ALTER TABLE classroom_paid_test_budget ADD CONSTRAINT chk_classroom_paid_test_budget_cap
    CHECK (reserved_cents BETWEEN 0 AND 10000);
ALTER TABLE classroom_paid_test_reservation DROP CONSTRAINT chk_classroom_paid_test_amount;
ALTER TABLE classroom_paid_test_reservation ADD CONSTRAINT chk_classroom_paid_test_amount
    CHECK (reserved_cents BETWEEN 1 AND 10000);
