-- V4: Sprint 3 coverage data foundation (require1 §4.2/§4.7; DEC gate K-07 confirmed
-- dedupe/filter/drilldown; C-01 status vocabulary stays open, so status is stored verbatim
-- as a non-empty VARCHAR and only 'COMPLETED' is interpreted by the coverage predicate).
-- Version assigned by actual creation order (V3 was the previous highest).
CREATE TABLE supervision_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_offering_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_supervision_offering FOREIGN KEY (course_offering_id) REFERENCES course_offering(id)
);
CREATE INDEX idx_supervision_offering ON supervision_evaluation (course_offering_id);
CREATE INDEX idx_supervision_status ON supervision_evaluation (status);
