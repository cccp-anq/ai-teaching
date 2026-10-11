-- Prevent duplicate classroom/media/module creation when a video upload is retried.
ALTER TABLE class_session ADD COLUMN upload_idempotency_key_sha256 CHAR(64) NULL;
ALTER TABLE class_session ADD COLUMN upload_request_sha256 CHAR(64) NULL;

CREATE UNIQUE INDEX uq_class_session_upload_request
    ON class_session (created_by, upload_idempotency_key_sha256);
