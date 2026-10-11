-- Single-class-session media and analysis foundation (DEC-027).
CREATE TABLE class_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    offering_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    session_date DATE NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_class_session_offering FOREIGN KEY (offering_id) REFERENCES course_offering(id),
    CONSTRAINT fk_class_session_schedule FOREIGN KEY (schedule_id, offering_id) REFERENCES class_schedule(id, offering_id),
    CONSTRAINT fk_class_session_creator FOREIGN KEY (created_by) REFERENCES user_account(id)
);
CREATE INDEX idx_class_session_offering_date ON class_session (offering_id, session_date, id);

CREATE TABLE classroom_media (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_session_id BIGINT NOT NULL,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    duration_ms BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_classroom_media_session FOREIGN KEY (class_session_id) REFERENCES class_session(id),
    CONSTRAINT ck_classroom_media_size CHECK (byte_size > 0),
    CONSTRAINT ck_classroom_media_duration CHECK (duration_ms > 0)
);
CREATE INDEX idx_classroom_media_session ON classroom_media (class_session_id, id);

CREATE TABLE classroom_module_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_id BIGINT NOT NULL,
    module_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_id VARCHAR(128) NOT NULL,
    module_version VARCHAR(128) NULL,
    result_revision INT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_classroom_job_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT uq_classroom_job_media_type UNIQUE (media_id, module_type)
);

CREATE TABLE classroom_transcript_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    segment_key VARCHAR(128) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    text_content TEXT NOT NULL,
    CONSTRAINT fk_transcript_job FOREIGN KEY (job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT uq_transcript_job_key UNIQUE (job_id, segment_key),
    CONSTRAINT ck_transcript_time CHECK (start_ms >= 0 AND end_ms > start_ms)
);

CREATE TABLE classroom_head_up_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    window_key VARCHAR(128) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    quality VARCHAR(32) NOT NULL,
    quality_reason VARCHAR(255) NULL,
    head_up_count INT NULL,
    valid_observed_count INT NULL,
    CONSTRAINT fk_head_up_job FOREIGN KEY (job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT uq_head_up_job_key UNIQUE (job_id, window_key),
    CONSTRAINT ck_head_up_time CHECK (start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT ck_head_up_counts CHECK (head_up_count IS NULL OR (head_up_count >= 0 AND valid_observed_count >= head_up_count))
);

CREATE TABLE classroom_analysis_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_id BIGINT NOT NULL,
    transcript_job_id BIGINT NOT NULL,
    head_up_job_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    data_mode VARCHAR(32) NOT NULL,
    mean_head_up_rate DOUBLE NULL,
    valid_coverage_rate DOUBLE NULL,
    summary_text TEXT NULL,
    analysis_text TEXT NULL,
    evidence_start_ms BIGINT NULL,
    evidence_end_ms BIGINT NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_analysis_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT fk_analysis_transcript_job FOREIGN KEY (transcript_job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT fk_analysis_head_up_job FOREIGN KEY (head_up_job_id) REFERENCES classroom_module_job(id),
    CONSTRAINT uq_analysis_input UNIQUE (media_id, transcript_job_id, head_up_job_id)
);
CREATE INDEX idx_analysis_media ON classroom_analysis_report (media_id, id);
