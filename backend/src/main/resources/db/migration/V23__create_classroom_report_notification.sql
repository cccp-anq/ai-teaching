-- V23: Sprint 2 auto-notification when a supervisor publishes a confirmed report (S2-05).
-- One row per (media, recipient, review): re-confirming the same review can never duplicate a
-- notification. The recipient set is resolved from the offering's teaching relation, so a teacher
-- only learns about reports on courses they actually teach.
CREATE TABLE classroom_report_notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_id BIGINT NOT NULL,
    offering_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at DATETIME NULL,
    CONSTRAINT fk_report_notification_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT fk_report_notification_user FOREIGN KEY (recipient_user_id) REFERENCES user_account(id),
    CONSTRAINT fk_report_notification_review FOREIGN KEY (review_id) REFERENCES classroom_report_review(id)
);
CREATE UNIQUE INDEX uq_report_notification_target
    ON classroom_report_notification (media_id,recipient_user_id,review_id);
CREATE INDEX idx_report_notification_recipient
    ON classroom_report_notification (recipient_user_id,state,id);
