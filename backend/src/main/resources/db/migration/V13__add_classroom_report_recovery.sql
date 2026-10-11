-- Separate retryable local/pre-submission failures from possibly charged submissions.
ALTER TABLE classroom_report_dispatch
    ADD COLUMN pre_submit_failures INT NOT NULL DEFAULT 0;
ALTER TABLE classroom_report_dispatch
    ADD COLUMN next_attempt_at DATETIME NULL;
CREATE INDEX idx_report_dispatch_retry ON classroom_report_dispatch (state,next_attempt_at,id);
