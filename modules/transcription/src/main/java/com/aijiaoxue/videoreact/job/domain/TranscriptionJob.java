package com.aijiaoxue.videoreact.job.domain;

import com.aijiaoxue.videoreact.job.wire.JobStatus;

import java.time.Instant;

/**
 * 转写任务的持久化模型。
 *
 * <p>与 {@code JobEnvelope} 的对应关系：本类是存储形态（含下载凭据、指纹、排错字段），
 * 信封是 wire 形态。两者分离是因为契约明确要求
 * 「{@code sourceUrl} 不输出到日志或结果」，把它挡在信封之外最省心。
 *
 * @param id              自增主键
 * @param jobId           对外任务 ID（契约字段）
 * @param clientRequestId 提交幂等键
 * @param requestHash     业务指纹（不含 sourceUrl/urlExpiresAt）
 * @param schemaVersion   wire 版本
 * @param providerId      供应者
 * @param moduleVersion   实现版本
 * @param classSessionId  课堂会话
 * @param mediaId         媒体
 * @param mediaSha256     原媒体 SHA-256
 * @param durationMs      原媒体时长
 * @param mimeType        媒体类型
 * @param sourceUrl       受控下载地址（<b>不得出现在日志/响应</b>）
 * @param urlExpiresAt    下载地址过期时间
 * @param analysisStartMs 处理范围起始
 * @param analysisEndMs   处理范围结束
 * @param language        请求语言标签
 * @param status          状态
 * @param revision        0 非终态 / 1 终态
 * @param payloadJson     成功载荷 JSON（原样返回，保证终态不可变）
 * @param errorCode       失败码
 * @param errorMessage    失败说明
 * @param errorRetryable  是否可重试
 * @param providerTaskId  提供方任务号（排错）
 * @param engineVersion   引擎版本标签（排错 + payload.engineVersion）
 * @param attemptCount    处理尝试次数
 * @param createdAt       创建时间
 * @param updatedAt       最后状态时间
 */
public record TranscriptionJob(
        Long id,
        String jobId,
        String clientRequestId,
        String requestHash,
        String schemaVersion,
        String providerId,
        String moduleVersion,
        String classSessionId,
        String mediaId,
        String mediaSha256,
        long durationMs,
        String mimeType,
        String sourceUrl,
        Instant urlExpiresAt,
        long analysisStartMs,
        long analysisEndMs,
        String language,
        JobStatus status,
        int revision,
        String payloadJson,
        String errorCode,
        String errorMessage,
        Boolean errorRetryable,
        String providerTaskId,
        String engineVersion,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt) {

    /** 是否终态（终态不可变）。 */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * 生成带新状态的信封身份信息之外的更新副本。
     *
     * @param newStatus    新状态
     * @param newPayload   成功载荷 JSON（非成功时传 null）
     * @param newErrorCode 失败码（非失败时传 null）
     * @param newMessage   失败说明
     * @param retryable    是否可重试
     * @param now          更新时间
     * @return 更新后的任务
     */
    public TranscriptionJob withOutcome(
            JobStatus newStatus,
            String newPayload,
            String newErrorCode,
            String newMessage,
            Boolean retryable,
            Instant now) {
        return new TranscriptionJob(
                id, jobId, clientRequestId, requestHash, schemaVersion, providerId, moduleVersion,
                classSessionId, mediaId, mediaSha256, durationMs, mimeType, sourceUrl, urlExpiresAt,
                analysisStartMs, analysisEndMs, language, newStatus, newStatus.revision(),
                newPayload, newErrorCode, newMessage, retryable, providerTaskId, engineVersion,
                attemptCount, createdAt, now);
    }

    /**
     * 生成"开始处理"的副本（RUNNING，revision 仍为 0）。
     *
     * @param providerTask 提供方任务号
     * @param engine       引擎版本
     * @param now          更新时间
     * @return 更新后的任务
     */
    public TranscriptionJob withRunning(String providerTask, String engine, Instant now) {
        return new TranscriptionJob(
                id, jobId, clientRequestId, requestHash, schemaVersion, providerId, moduleVersion,
                classSessionId, mediaId, mediaSha256, durationMs, mimeType, sourceUrl, urlExpiresAt,
                analysisStartMs, analysisEndMs, language, JobStatus.RUNNING, 0,
                null, null, null, null, providerTask, engine, attemptCount + 1, createdAt, now);
    }

    /**
     * 仅更新下载凭据（契约 §3：尚未终态时可用同键续处理）。
     *
     * @param newSourceUrl 新下载地址
     * @param newExpiresAt 新过期时间
     * @return 更新后的任务
     */
    public TranscriptionJob withRefreshedDownload(String newSourceUrl, Instant newExpiresAt) {
        return new TranscriptionJob(
                id, jobId, clientRequestId, requestHash, schemaVersion, providerId, moduleVersion,
                classSessionId, mediaId, mediaSha256, durationMs, mimeType, newSourceUrl, newExpiresAt,
                analysisStartMs, analysisEndMs, language, status, revision,
                payloadJson, errorCode, errorMessage, errorRetryable, providerTaskId, engineVersion,
                attemptCount, createdAt, updatedAt);
    }
}
