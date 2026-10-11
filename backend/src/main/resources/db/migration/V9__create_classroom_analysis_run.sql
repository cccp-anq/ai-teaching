-- Local-only M3 preparation. No model worker or public API is enabled by this migration.
CREATE TABLE classroom_analysis_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    objective_report_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    transcript_result_id BIGINT NOT NULL,
    head_up_result_id BIGINT NOT NULL,
    request_key_sha256 CHAR(64) NOT NULL,
    input_version VARCHAR(64) NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    input_json LONGTEXT NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_analysis_run_objective_report FOREIGN KEY (objective_report_id) REFERENCES classroom_analysis_report(id),
    CONSTRAINT fk_analysis_run_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT fk_analysis_run_transcript_result FOREIGN KEY (transcript_result_id) REFERENCES classroom_module_result(id),
    CONSTRAINT fk_analysis_run_head_up_result FOREIGN KEY (head_up_result_id) REFERENCES classroom_module_result(id)
);
CREATE UNIQUE INDEX uq_analysis_run_request ON classroom_analysis_run (objective_report_id, request_key_sha256);
CREATE INDEX idx_analysis_run_media_created ON classroom_analysis_run (media_id, id);
