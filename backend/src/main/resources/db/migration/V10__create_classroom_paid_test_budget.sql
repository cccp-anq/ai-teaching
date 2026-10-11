-- DEC-030 paid-test reservation ledger. Disabled until an internal caller explicitly reserves.
CREATE TABLE classroom_paid_test_budget (
    scope_key VARCHAR(32) PRIMARY KEY,
    reserved_cents BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_classroom_paid_test_budget_cap CHECK (reserved_cents BETWEEN 0 AND 2000)
);
INSERT INTO classroom_paid_test_budget(scope_key,reserved_cents) VALUES ('DEC030_TEST',0);

CREATE TABLE classroom_paid_test_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_sha256 CHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    subject_id BIGINT NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    reserved_cents BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    provider_request_id VARCHAR(200),
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    response_sha256 CHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_classroom_paid_test_amount CHECK (reserved_cents BETWEEN 1 AND 2000)
);
CREATE UNIQUE INDEX uq_classroom_paid_test_request ON classroom_paid_test_reservation (request_sha256);
CREATE INDEX idx_classroom_paid_test_subject ON classroom_paid_test_reservation (category,subject_id,id);
