package com.aijiaoxue.videoreact.job.wire;

import java.time.Instant;
import java.util.List;

/**
 * 统一任务信封，对应契约 §4 的 JobEnvelope。
 *
 * <p>轮询、可选回调与 JSON 导入共用同一结构。关键不变量（由
 * {@code verify_contract.py} 与本地契约测试共同校验）：
 * <ul>
 *   <li>{@code revision}：非终态 0、终态 1；</li>
 *   <li>{@code SUCCEEDED} 必有 payload、{@code error} 必须为 null；</li>
 *   <li>{@code FAILED} 必有 error、payload 必须为 null；</li>
 *   <li>{@code QUEUED/RUNNING} 两者都为 null；</li>
 *   <li>时间一律 UTC 带 Z。</li>
 * </ul>
 *
 * @param schemaVersion   wire 版本，固定 1.0
 * @param clientRequestId 请求关联 ID
 * @param jobId           模块任务 ID
 * @param moduleType      固定 TRANSCRIPTION
 * @param providerId      本部署注册的供应者
 * @param moduleVersion   实现版本
 * @param classSessionId  课堂会话 ID
 * @param mediaId         媒体 ID
 * @param mediaSha256     原媒体 SHA-256（与主项目已分配任务一致）
 * @param durationMs      原媒体时长
 * @param timeBase        固定 MEDIA_START_MS
 * @param analysisRange   处理范围，必须与请求一致
 * @param status          任务状态
 * @param revision        非终态 0、终态 1
 * @param createdAt       创建时间（UTC Z）
 * @param updatedAt       最后状态时间（UTC Z）
 * @param payload         成功载荷；其他状态必须为 null
 * @param error           失败信息；其他状态必须为 null
 */
public record JobEnvelope(
        String schemaVersion,
        String clientRequestId,
        String jobId,
        String moduleType,
        String providerId,
        String moduleVersion,
        String classSessionId,
        String mediaId,
        String mediaSha256,
        Long durationMs,
        String timeBase,
        JobRequest.AnalysisRange analysisRange,
        JobStatus status,
        Integer revision,
        Instant createdAt,
        Instant updatedAt,
        TranscriptionPayload payload,
        JobError error) {

    /** 契约固定的 wire 版本。 */
    public static final String SCHEMA_VERSION = "1.0";

    /** 契约固定的时间原点。 */
    public static final String TIME_BASE = "MEDIA_START_MS";

    /**
     * 任务内错误（契约 §5）。
     *
     * <p>{@code message} 不含堆栈、密钥或媒体 URL。
     *
     * @param code      错误码，见契约 §5 的错误表
     * @param message   可读说明
     * @param retryable 是否可重试（决定主项目是否值得重试）
     */
    public record JobError(String code, String message, Boolean retryable) {
    }

    /**
     * 转写成功载荷（契约 §3）。
     *
     * @param language        实际识别语言，未知用 und
     * @param engineVersion   可复现的引擎版本标签，不含密钥
     * @param processedRanges 已完成音频处理的范围（排序、不重叠、在 analysisRange 内）
     * @param segments        转写片段（按 startMs 排序，id 在结果内唯一）
     * @param warnings        警告；部分处理含 PARTIAL_PROCESSING，无语音含 NO_SPEECH
     */
    public record TranscriptionPayload(
            String language,
            String engineVersion,
            List<Range> processedRanges,
            List<Segment> segments,
            List<String> warnings) {
    }

    /**
     * 时间区间，左闭右开。
     *
     * @param startMs 起始毫秒（含）
     * @param endMs   结束毫秒（不含）
     */
    public record Range(long startMs, long endMs) {
    }

    /**
     * 转写片段。
     *
     * @param id           结果内稳定局部 ID，如 seg-001
     * @param startMs      原媒体起始毫秒
     * @param endMs        原媒体结束毫秒
     * @param text         转写原文（trim 后不能为空）
     * @param confidence   置信度或 null；<b>引擎不提供就为 null，不能填 1</b>
     * @param speakerLabel 匿名说话人标签或 null；首版不支持时为 null
     */
    public record Segment(
            String id,
            long startMs,
            long endMs,
            String text,
            Double confidence,
            String speakerLabel) {
    }

    /**
     * 构造一个非终态信封（payload 与 error 都为 null，revision 为 0）。
     *
     * @param template 已有身份信息
     * @param status   QUEUED 或 RUNNING
     * @param updatedAt 更新时间
     * @return 信封
     */
    public static JobEnvelope inProgress(JobEnvelope template, JobStatus status, Instant updatedAt) {
        return new JobEnvelope(
                template.schemaVersion(), template.clientRequestId(), template.jobId(),
                template.moduleType(), template.providerId(), template.moduleVersion(),
                template.classSessionId(), template.mediaId(), template.mediaSha256(),
                template.durationMs(), template.timeBase(), template.analysisRange(),
                status, 0, template.createdAt(), updatedAt, null, null);
    }

    /**
     * 构造成功信封。
     *
     * @param template 已有身份信息
     * @param payload  成功载荷
     * @param updatedAt 更新时间
     * @return 信封
     */
    public static JobEnvelope succeeded(JobEnvelope template, TranscriptionPayload payload, Instant updatedAt) {
        return new JobEnvelope(
                template.schemaVersion(), template.clientRequestId(), template.jobId(),
                template.moduleType(), template.providerId(), template.moduleVersion(),
                template.classSessionId(), template.mediaId(), template.mediaSha256(),
                template.durationMs(), template.timeBase(), template.analysisRange(),
                JobStatus.SUCCEEDED, 1, template.createdAt(), updatedAt, payload, null);
    }

    /**
     * 构造失败信封。
     *
     * @param template 已有身份信息
     * @param error    失败信息
     * @param updatedAt 更新时间
     * @return 信封
     */
    public static JobEnvelope failed(JobEnvelope template, JobError error, Instant updatedAt) {
        return new JobEnvelope(
                template.schemaVersion(), template.clientRequestId(), template.jobId(),
                template.moduleType(), template.providerId(), template.moduleVersion(),
                template.classSessionId(), template.mediaId(), template.mediaSha256(),
                template.durationMs(), template.timeBase(), template.analysisRange(),
                JobStatus.FAILED, 1, template.createdAt(), updatedAt, null, error);
    }
}
