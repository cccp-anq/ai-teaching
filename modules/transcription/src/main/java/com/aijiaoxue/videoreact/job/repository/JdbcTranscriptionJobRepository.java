package com.aijiaoxue.videoreact.job.repository;

import com.aijiaoxue.videoreact.job.domain.TranscriptionJob;
import com.aijiaoxue.videoreact.job.wire.JobStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link TranscriptionJobRepository} 的 Spring JDBC 实现。
 */
@Repository
public class JdbcTranscriptionJobRepository implements TranscriptionJobRepository {

    @Override public void setSpeakerLabels(String id,boolean enabled){jdbc.sql("UPDATE transcription_job SET speaker_labels=:enabled WHERE job_id=:id").param("enabled",enabled).param("id",id).update();}
    @Override public boolean speakerLabels(String id){return jdbc.sql("SELECT speaker_labels FROM transcription_job WHERE job_id=:id").param("id",id).query(Boolean.class).single();}
    @Override public Optional<String> stagingObjectKey(String id) {
        return jdbc.sql("SELECT staging_object_key FROM transcription_job WHERE job_id=:id")
                .param("id", id).query(String.class).optional().filter(value -> !value.isBlank());
    }
    @Override public void setStagingObjectKey(String id, String key) {
        jdbc.sql("UPDATE transcription_job SET staging_object_key=:key, updated_at=CURRENT_TIMESTAMP WHERE job_id=:id AND status IN ('QUEUED','RUNNING')")
                .param("key", key).param("id", id).update();
    }
    @Override public void clearStagingObjectKey(String id, String key) {
        jdbc.sql("UPDATE transcription_job SET staging_object_key=NULL, updated_at=CURRENT_TIMESTAMP WHERE job_id=:id AND staging_object_key=:key")
                .param("key", key).param("id", id).update();
    }
    private static final String COLUMNS = """
            id, job_id, client_request_id, request_hash, module_type, provider_id, module_version,
            schema_version, class_session_id, media_id, media_sha256, duration_ms, mime_type,
            source_url, url_expires_at, analysis_start_ms, analysis_end_ms, language,
            status, revision, payload_json, error_code, error_message, error_retryable,
            provider_task_id, engine_version, attempt_count, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public JdbcTranscriptionJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TranscriptionJob insert(TranscriptionJob job) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO transcription_job (
                            job_id, client_request_id, request_hash, module_type, provider_id,
                            module_version, schema_version, class_session_id, media_id, media_sha256,
                            duration_ms, mime_type, source_url, url_expires_at,
                            analysis_start_ms, analysis_end_ms, language, status, revision,
                            payload_json, error_code, error_message, error_retryable,
                            provider_task_id, engine_version, attempt_count, created_at, updated_at)
                        VALUES (
                            :jobId, :clientRequestId, :requestHash, :moduleType, :providerId,
                            :moduleVersion, :schemaVersion, :classSessionId, :mediaId, :mediaSha256,
                            :durationMs, :mimeType, :sourceUrl, :urlExpiresAt,
                            :analysisStartMs, :analysisEndMs, :language, :status, :revision,
                            :payloadJson, :errorCode, :errorMessage, :errorRetryable,
                            :providerTaskId, :engineVersion, :attemptCount, :createdAt, :updatedAt)
                        """)
                .param("jobId", job.jobId())
                .param("clientRequestId", job.clientRequestId())
                .param("requestHash", job.requestHash())
                .param("moduleType", "TRANSCRIPTION")
                .param("providerId", job.providerId())
                .param("moduleVersion", job.moduleVersion())
                .param("schemaVersion", job.schemaVersion())
                .param("classSessionId", job.classSessionId())
                .param("mediaId", job.mediaId())
                .param("mediaSha256", job.mediaSha256())
                .param("durationMs", job.durationMs())
                .param("mimeType", job.mimeType())
                .param("sourceUrl", job.sourceUrl())
                .param("urlExpiresAt", ts(job.urlExpiresAt()))
                .param("analysisStartMs", job.analysisStartMs())
                .param("analysisEndMs", job.analysisEndMs())
                .param("language", job.language())
                .param("status", job.status().name())
                .param("revision", job.revision())
                .param("payloadJson", job.payloadJson())
                .param("errorCode", job.errorCode())
                .param("errorMessage", job.errorMessage())
                .param("errorRetryable", job.errorRetryable())
                .param("providerTaskId", job.providerTaskId())
                .param("engineVersion", job.engineVersion())
                .param("attemptCount", job.attemptCount())
                .param("createdAt", ts(job.createdAt()))
                .param("updatedAt", ts(job.updatedAt()))
                .update(keys, "id");

        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("transcription_job 插入后未取回自增主键");
        }
        return new TranscriptionJob(
                key.longValue(), job.jobId(), job.clientRequestId(), job.requestHash(),
                job.schemaVersion(), job.providerId(), job.moduleVersion(), job.classSessionId(),
                job.mediaId(), job.mediaSha256(), job.durationMs(), job.mimeType(), job.sourceUrl(),
                job.urlExpiresAt(), job.analysisStartMs(), job.analysisEndMs(), job.language(),
                job.status(), job.revision(), job.payloadJson(), job.errorCode(), job.errorMessage(),
                job.errorRetryable(), job.providerTaskId(), job.engineVersion(), job.attemptCount(),
                job.createdAt(), job.updatedAt());
    }

    @Override
    public int update(TranscriptionJob job) {
        return jdbc.sql("""
                        UPDATE transcription_job SET
                            source_url      = :sourceUrl,
                            url_expires_at  = :urlExpiresAt,
                            status          = :status,
                            revision        = :revision,
                            payload_json    = :payloadJson,
                            error_code      = :errorCode,
                            error_message   = :errorMessage,
                            error_retryable = :errorRetryable,
                            provider_task_id = :providerTaskId,
                            engine_version  = :engineVersion,
                            attempt_count   = :attemptCount,
                            updated_at      = :updatedAt
                        WHERE id = :id AND status IN ('QUEUED','RUNNING')
                        """)
                .param("sourceUrl", job.sourceUrl())
                .param("urlExpiresAt", ts(job.urlExpiresAt()))
                .param("status", job.status().name())
                .param("revision", job.revision())
                .param("payloadJson", job.payloadJson())
                .param("errorCode", job.errorCode())
                .param("errorMessage", job.errorMessage())
                .param("errorRetryable", job.errorRetryable())
                .param("providerTaskId", job.providerTaskId())
                .param("engineVersion", job.engineVersion())
                .param("attemptCount", job.attemptCount())
                .param("updatedAt", ts(job.updatedAt()))
                .param("id", job.id())
                .update();
    }

    @Override
    public Optional<TranscriptionJob> findByJobId(String jobId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transcription_job WHERE job_id = :jobId")
                .param("jobId", jobId)
                .query(JdbcTranscriptionJobRepository::map)
                .optional();
    }

    @Override
    public Optional<TranscriptionJob> findByClientRequestId(String clientRequestId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM transcription_job WHERE client_request_id = :cid")
                .param("cid", clientRequestId)
                .query(JdbcTranscriptionJobRepository::map)
                .optional();
    }

    @Override
    public List<TranscriptionJob> findNonTerminal(int limit) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM transcription_job WHERE status IN ('QUEUED','RUNNING')"
                        + " ORDER BY created_at ASC LIMIT " + Math.max(1, limit))
                .query(JdbcTranscriptionJobRepository::map)
                .list();
    }

    @Override
    public int countByClassSessionId(String classSessionId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM transcription_job WHERE class_session_id = :sid")
                .param("sid", classSessionId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private static TranscriptionJob map(ResultSet rs, int rowNum) throws SQLException {
        return new TranscriptionJob(
                rs.getLong("id"),
                rs.getString("job_id"),
                rs.getString("client_request_id"),
                rs.getString("request_hash"),
                rs.getString("schema_version"),
                rs.getString("provider_id"),
                rs.getString("module_version"),
                rs.getString("class_session_id"),
                rs.getString("media_id"),
                rs.getString("media_sha256"),
                rs.getLong("duration_ms"),
                rs.getString("mime_type"),
                rs.getString("source_url"),
                instant(rs.getTimestamp("url_expires_at")),
                rs.getLong("analysis_start_ms"),
                rs.getLong("analysis_end_ms"),
                rs.getString("language"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getInt("revision"),
                rs.getString("payload_json"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                nullableBoolean(rs, "error_retryable"),
                rs.getString("provider_task_id"),
                rs.getString("engine_version"),
                rs.getInt("attempt_count"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
