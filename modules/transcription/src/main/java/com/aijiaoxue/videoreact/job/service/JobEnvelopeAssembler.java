package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.domain.TranscriptionJob;
import com.aijiaoxue.videoreact.job.wire.JobEnvelope;
import com.aijiaoxue.videoreact.job.wire.JobRequest;
import com.aijiaoxue.videoreact.job.wire.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 持久化任务 ↔ 契约信封 的转换。
 *
 * <p>成功载荷以 JSON 原样存取，而不是每次从结构化字段重新拼装：
 * 契约要求「终态不可变」，只有原样保存才能保证同一 job 的 GET 多次返回
 * 逐字节一致，也避免"读取时重新计算导致历史结果漂移"。
 */
public class JobEnvelopeAssembler {

    private static final Logger log = LoggerFactory.getLogger(JobEnvelopeAssembler.class);

    private final ObjectMapper objectMapper;

    public JobEnvelopeAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 由持久化任务构造信封。
     *
     * @param job 任务
     * @return 信封
     */
    public JobEnvelope toEnvelope(TranscriptionJob job) {
        JobStatus status = job.status();
        JobEnvelope.TranscriptionPayload payload = null;
        JobEnvelope.JobError error = null;

        if (status == JobStatus.SUCCEEDED) {
            payload = readPayload(job);
        } else if (status == JobStatus.FAILED) {
            error = new JobEnvelope.JobError(
                    job.errorCode() == null ? "PROCESSING_FAILED" : job.errorCode(),
                    job.errorMessage() == null ? "处理失败" : job.errorMessage(),
                    job.errorRetryable() == null ? Boolean.FALSE : job.errorRetryable());
        }

        return new JobEnvelope(
                job.schemaVersion(),
                job.clientRequestId(),
                job.jobId(),
                "TRANSCRIPTION",
                job.providerId(),
                job.moduleVersion(),
                job.classSessionId(),
                job.mediaId(),
                job.mediaSha256(),
                job.durationMs(),
                JobEnvelope.TIME_BASE,
                new JobRequest.AnalysisRange(job.analysisStartMs(), job.analysisEndMs()),
                status,
                job.revision(),
                job.createdAt(),
                job.updatedAt(),
                payload,
                error);
    }

    /**
     * 序列化成功载荷，供落库。
     *
     * @param payload 载荷
     * @return JSON 文本
     */
    public String writePayload(JobEnvelope.TranscriptionPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new IllegalStateException("成功载荷序列化失败: " + e.getMessage(), e);
        }
    }

    private JobEnvelope.TranscriptionPayload readPayload(TranscriptionJob job) {
        String json = job.payloadJson();
        if (json == null || json.isBlank()) {
            // 数据异常：SUCCEEDED 却没有载荷。不能伪造成功空数组（契约明确禁止），
            // 所以如实抛出让调用方看到问题，而不是返回一个"看起来成功"的空结果。
            throw new IllegalStateException(
                    "任务 " + job.jobId() + " 状态为 SUCCEEDED 但缺少载荷 JSON");
        }
        try {
            return objectMapper.readValue(json, JobEnvelope.TranscriptionPayload.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "任务 " + job.jobId() + " 的载荷 JSON 无法解析: " + e.getMessage(), e);
        }
    }

    /**
     * 校验信封是否满足契约的不变量，用于写入前自检。
     *
     * <p>这些不变量在 {@code verify_contract.py} 里同样被校验；
     * 在模块内先检查一遍，可以在<b>写库之前</b>拦住不合规结果，
     * 而不是把脏数据留给主项目去发现。
     *
     * @param envelope 信封
     * @return 不合规原因列表；为空表示通过
     */
    public static java.util.List<String> checkInvariants(JobEnvelope envelope) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        JobStatus status = envelope.status();
        int expectedRevision = status.isTerminal() ? 1 : 0;
        if (envelope.revision() == null || envelope.revision() != expectedRevision) {
            problems.add("revision 应为 " + expectedRevision + "，实际 " + envelope.revision());
        }
        if (status == JobStatus.SUCCEEDED) {
            if (envelope.payload() == null) {
                problems.add("SUCCEEDED 必须有 payload");
            }
            if (envelope.error() != null) {
                problems.add("SUCCEEDED 的 error 必须为 null");
            }
        } else if (status == JobStatus.FAILED) {
            if (envelope.error() == null) {
                problems.add("FAILED 必须有 error");
            }
            if (envelope.payload() != null) {
                problems.add("FAILED 的 payload 必须为 null");
            }
        } else {
            if (envelope.payload() != null || envelope.error() != null) {
                problems.add(status + " 的 payload 与 error 都必须为 null");
            }
        }
        if (!JobEnvelope.SCHEMA_VERSION.equals(envelope.schemaVersion())) {
            problems.add("schemaVersion 必须为 " + JobEnvelope.SCHEMA_VERSION);
        }
        if (!JobEnvelope.TIME_BASE.equals(envelope.timeBase())) {
            problems.add("timeBase 必须为 " + JobEnvelope.TIME_BASE);
        }
        if (!problems.isEmpty()) {
            log.error("信封不满足契约不变量: jobId={}, problems={}", envelope.jobId(), problems);
        }
        return problems;
    }
}
