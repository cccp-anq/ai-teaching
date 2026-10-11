-- ============================================================
--  转写模块建表：契约 JobEnvelope 的持久化
--  取代早期的 asr_task / transcript_segment（那两张表是"沙盒自定接口"时代的产物，
--  字段与《三项目公共接口契约 v0.1》不一致）。
--
--  设计依据：
--   1. (client_request_id) 唯一 —— 契约 §3 的提交幂等键；
--   2. request_hash —— 同键不同内容要返回 409 IDEMPOTENCY_CONFLICT，
--      所以必须把"业务指纹"存下来做比对（不含可续期的 sourceUrl/urlExpiresAt）；
--   3. 终态不可变 —— 一旦 SUCCEEDED/FAILED 就不再改写 payload/error；
--   4. 时间一律存原媒体毫秒轴（MEDIA_START_MS），不存音频片段轴。
--
--  兼容性：H2(MODE=MySQL) 与 MySQL 8 均可执行。
-- ============================================================

CREATE TABLE transcription_job (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    job_id            VARCHAR(64)   NOT NULL,
    client_request_id VARCHAR(128)  NOT NULL,
    request_hash      VARCHAR(64)   NOT NULL,
    module_type       VARCHAR(32)   NOT NULL,
    provider_id       VARCHAR(64)   NOT NULL,
    module_version    VARCHAR(64)   NOT NULL,
    schema_version    VARCHAR(16)   NOT NULL,
    class_session_id  VARCHAR(128)  NOT NULL,
    media_id          VARCHAR(128)  NOT NULL,
    media_sha256      VARCHAR(64)   NOT NULL,
    duration_ms       BIGINT        NOT NULL,
    mime_type         VARCHAR(128)  NULL,
    -- 下载凭据：仅用于续期重提，不参与业务指纹
    source_url        VARCHAR(2048) NULL,
    url_expires_at    TIMESTAMP     NULL,
    analysis_start_ms BIGINT        NOT NULL,
    analysis_end_ms   BIGINT        NOT NULL,
    language          VARCHAR(32)   NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    revision          INT           NOT NULL DEFAULT 0,
    -- 成功载荷：processedRanges / segments / warnings 以 JSON 原样保存，
    -- 保证 GET 返回与首次写入逐字节一致（终态不可变）
    payload_json      CLOB          NULL,
    -- 失败信息
    error_code        VARCHAR(64)   NULL,
    error_message     VARCHAR(1000) NULL,
    error_retryable   BOOLEAN       NULL,
    -- 排错用：提供方任务号与引擎版本，不参与契约字段
    provider_task_id  VARCHAR(128)  NULL,
    engine_version    VARCHAR(128)  NULL,
    attempt_count     INT           NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL,
    CONSTRAINT pk_transcription_job PRIMARY KEY (id),
    CONSTRAINT uk_transcription_job_job_id UNIQUE (job_id),
    CONSTRAINT uk_transcription_job_client_request UNIQUE (client_request_id)
);

-- 轮询兜底扫描：只捞非终态任务
CREATE INDEX idx_transcription_job_status ON transcription_job (status);

-- 按媒体/课堂排查
CREATE INDEX idx_transcription_job_media ON transcription_job (media_id, id);
