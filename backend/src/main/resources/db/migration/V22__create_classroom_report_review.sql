-- V22: Sprint 2 supervisor review of an AI report (S2-03 标注并记录说明, S2-04 提交时固定复核快照).
-- One review per generated report version. Before confirmation it is an editable draft;
-- confirming freezes the reviewed report into snapshot_json, so a later AI re-generation
-- cannot change what the teacher already received (AUD2-01 追溯).
CREATE TABLE classroom_report_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    confirmed_at DATETIME NULL,
    snapshot_sha256 CHAR(64) NULL,
    snapshot_json LONGTEXT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_review_report FOREIGN KEY (report_id) REFERENCES classroom_generated_report(id),
    CONSTRAINT fk_report_review_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT fk_report_review_user FOREIGN KEY (reviewer_user_id) REFERENCES user_account(id)
);
CREATE UNIQUE INDEX uq_report_review_report ON classroom_report_review (report_id);
CREATE INDEX idx_report_review_media ON classroom_report_review (media_id);

-- A note is mandatory on every annotation: an unexplained verdict is not a review record.
CREATE TABLE classroom_report_review_annotation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_id BIGINT NOT NULL,
    target_kind VARCHAR(24) NOT NULL,
    target_index INT NOT NULL,
    verdict VARCHAR(24) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_review_annotation_review FOREIGN KEY (review_id)
        REFERENCES classroom_report_review(id)
);
CREATE UNIQUE INDEX uq_report_review_annotation_target
    ON classroom_report_review_annotation (review_id,target_kind,target_index);
