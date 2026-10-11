-- V24: Sprint 2 supervisor correction of the AI's segment division (S2-10 调整片段标题与边界).
-- A generated report is an immutable fact, so a corrected title or boundary is never written back
-- into it: the correction is stored as an auditable override bound to (report_id, segment_key).
-- Binding to one report version is deliberate -- a later AI re-generation produces a new report_id,
-- so a division nobody reviewed is never inherited from an older one. revision/edited_by_user_id/
-- updated_at are the 留痕 (who changed what, and when), because an override with no author is not
-- an auditable record.
CREATE TABLE classroom_segment_override (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    segment_key VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    edited_by_user_id BIGINT NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_segment_override_report FOREIGN KEY (report_id) REFERENCES classroom_generated_report(id),
    CONSTRAINT fk_segment_override_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT fk_segment_override_user FOREIGN KEY (edited_by_user_id) REFERENCES user_account(id),
    CONSTRAINT ck_segment_override_start CHECK (start_ms >= 0),
    CONSTRAINT ck_segment_override_range CHECK (end_ms > start_ms)
);
CREATE UNIQUE INDEX uq_segment_override_target ON classroom_segment_override (report_id,segment_key);
CREATE INDEX idx_segment_override_media ON classroom_segment_override (media_id,report_id);
