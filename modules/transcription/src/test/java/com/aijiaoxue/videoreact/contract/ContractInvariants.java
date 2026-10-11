package com.aijiaoxue.videoreact.contract;

import com.aijiaoxue.videoreact.job.wire.JobEnvelope;
import com.aijiaoxue.videoreact.job.wire.JobRequest;
import com.aijiaoxue.videoreact.job.wire.JobStatus;
import com.aijiaoxue.videoreact.job.wire.ModuleType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 契约不自变量校验器。
 *
 * <p><b>这个类是干什么的</b>：主项目给的 {@code verify_contract.py} 是权威校验脚本，
 * 但它校验的是"JSON 文件"，跑不到我们的运行时。本类把那套不变量用 Java 复现，
 * 于是可以在<b>写库之前</b>、在<b>每次 GET 返回之前</b>自检，
 * 避免把不合规信封交给主项目去发现。
 *
 * <p>为什么值得复现一遍而不是"相信实现"：契约的不变量很容易在重构中悄悄破坏
 * （例如有人加了"部分处理"分支却忘了加 PARTIAL_PROCESSING 警告），
 * 而这类问题一旦流到主项目，会表现为"导入成功但统计对不上"，排查成本极高。
 *
 * <p>与官方脚本保持同一套判定：
 * <ul>
 *   <li>revision 与 status 必须一致；</li>
 *   <li>payload/error 的存在性由 status 决定；</li>
 *   <li>processedRanges 非空、有序、不重叠、在 analysisRange 内；</li>
 *   <li>processedRanges 未覆盖整个 analysisRange 时必须有 PARTIAL_PROCESSING；</li>
 *   <li>segments 为空时必须有 NO_SPEECH；</li>
 *   <li>每个 segment 必须完整落在 processedRanges ∪ analysisRange 内；</li>
 *   <li>id 唯一、text 非空、confidence 在 [0,1] 或 null。</li>
 * </ul>
 */
public final class ContractInvariants {

    private ContractInvariants() {
    }

    /**
     * 全面校验一个信封。
     *
     * @param envelope 信封（可为 null）
     * @return 违规说明列表；为空表示合规
     */
    public static List<String> check(JobEnvelope envelope) {
        List<String> problems = new ArrayList<>();
        if (envelope == null) {
            problems.add("信封为 null");
            return problems;
        }

        // ---- 固定字段 ----
        if (!JobEnvelope.SCHEMA_VERSION.equals(envelope.schemaVersion())) {
            problems.add("schemaVersion 必须为 " + JobEnvelope.SCHEMA_VERSION
                    + "，实际 " + envelope.schemaVersion());
        }
        if (!JobEnvelope.TIME_BASE.equals(envelope.timeBase())) {
            problems.add("timeBase 必须为 " + JobEnvelope.TIME_BASE);
        }
        ModuleType moduleType = ModuleType.parse(envelope.moduleType());
        if (moduleType != ModuleType.TRANSCRIPTION) {
            problems.add("moduleType 必须为 TRANSCRIPTION，实际 " + envelope.moduleType());
        }

        // ---- ID 与版本：1～128 字符 ----
        checkId(problems, "clientRequestId", envelope.clientRequestId());
        checkId(problems, "jobId", envelope.jobId());
        checkId(problems, "providerId", envelope.providerId());
        checkId(problems, "moduleVersion", envelope.moduleVersion());
        checkId(problems, "classSessionId", envelope.classSessionId());
        checkId(problems, "mediaId", envelope.mediaId());

        // ---- hash ----
        String sha = envelope.mediaSha256();
        if (sha == null || !sha.matches("[0-9a-f]{64}")) {
            problems.add("mediaSha256 必须是 64 位小写十六进制");
        }

        // ---- 时长与范围 ----
        Long duration = envelope.durationMs();
        if (duration == null || duration <= 0) {
            problems.add("durationMs 必须为正整数");
            return problems;
        }
        JobRequest.AnalysisRange range = envelope.analysisRange();
        if (range == null || range.startMs() == null || range.endMs() == null) {
            problems.add("analysisRange 必填");
            return problems;
        }
        long lo = range.startMs();
        long hi = range.endMs();
        if (!(0 <= lo && lo < hi && hi <= duration)) {
            problems.add("analysisRange 必须满足 0 <= startMs < endMs <= durationMs，实际 ["
                    + lo + "," + hi + ") duration=" + duration);
            return problems;
        }

        // ---- 时间格式 ----
        checkUtc(problems, "createdAt", envelope.createdAt());
        checkUtc(problems, "updatedAt", envelope.updatedAt());

        // ---- 状态与 revision ----
        JobStatus status = envelope.status();
        if (status == null) {
            problems.add("status 必填");
            return problems;
        }
        int expectedRevision = status.isTerminal() ? 1 : 0;
        if (envelope.revision() == null || envelope.revision() != expectedRevision) {
            problems.add("status=" + status + " 时 revision 必须为 " + expectedRevision
                    + "，实际 " + envelope.revision());
        }

        if (status == JobStatus.SUCCEEDED) {
            if (envelope.error() != null) {
                problems.add("SUCCEEDED 的 error 必须为 null");
            }
            if (envelope.payload() == null) {
                problems.add("SUCCEEDED 必须有 payload");
            } else {
                checkPayload(problems, envelope.payload(), lo, hi);
            }
        } else if (status == JobStatus.FAILED) {
            if (envelope.payload() != null) {
                problems.add("FAILED 的 payload 必须为 null");
            }
            JobEnvelope.JobError error = envelope.error();
            if (error == null) {
                problems.add("FAILED 必须有 error");
            } else {
                if (isBlank(error.code())) {
                    problems.add("error.code 不能为空");
                }
                if (isBlank(error.message())) {
                    problems.add("error.message 不能为空");
                }
                if (error.retryable() == null) {
                    problems.add("error.retryable 必须是布尔值");
                }
            }
        } else {
            if (envelope.payload() != null) {
                problems.add(status + " 的 payload 必须为 null");
            }
            if (envelope.error() != null) {
                problems.add(status + " 的 error 必须为 null");
            }
        }
        return problems;
    }

    /**
     * 校验转写载荷。
     *
     * @param problems 收集违规
     * @param payload  载荷
     * @param rangeStart analysisRange 起点
     * @param rangeEnd   analysisRange 终点
     */
    private static void checkPayload(
            List<String> problems, JobEnvelope.TranscriptionPayload payload,
            long rangeStart, long rangeEnd) {

        if (isBlank(payload.language())) {
            problems.add("payload.language 不能为空（未知应为 und）");
        }
        if (isBlank(payload.engineVersion())) {
            problems.add("payload.engineVersion 不能为空");
        }
        if (payload.warnings() == null) {
            problems.add("payload.warnings 必须存在（可为空数组）");
        }

        // ---- processedRanges ----
        List<JobEnvelope.Range> processed = payload.processedRanges();
        if (processed == null || processed.isEmpty()) {
            problems.add("processedRanges 不能为空（没有任何成功处理区间应判 FAILED）");
            return;
        }
        long previousEnd = Long.MIN_VALUE;
        long unionMs = 0;
        for (JobEnvelope.Range r : processed) {
            if (r.startMs() < rangeStart || r.endMs() > rangeEnd || r.startMs() >= r.endMs()) {
                problems.add("processedRange [" + r.startMs() + "," + r.endMs()
                        + ") 必须满足 0 <= 起 < 止 且落在 analysisRange ["
                        + rangeStart + "," + rangeEnd + ") 内");
            }
            if (r.startMs() < previousEnd) {
                problems.add("processedRanges 必须有序且不重叠，违规区间 [" + r.startMs() + "," + r.endMs() + ")");
            }
            unionMs += Math.max(0, r.endMs() - Math.max(r.startMs(), previousEnd));
            previousEnd = Math.max(previousEnd, r.endMs());
        }

        // ---- 部分处理必须显式声明 ----
        boolean partial = unionMs < (rangeEnd - rangeStart);
        List<String> warnings = payload.warnings() == null ? List.of() : payload.warnings();
        if (partial && !warnings.contains("PARTIAL_PROCESSING")) {
            problems.add("processedRanges 未覆盖完整 analysisRange，warnings 必须包含 PARTIAL_PROCESSING");
        }

        // ---- segments ----
        List<JobEnvelope.Segment> segments = payload.segments();
        if (segments == null) {
            problems.add("payload.segments 必须存在（可为空数组）");
            return;
        }
        if (segments.isEmpty() && !warnings.contains("NO_SPEECH")) {
            problems.add("segments 为空时必须带 NO_SPEECH 警告（区别于解码失败被包装成静音）");
        }

        java.util.Set<String> ids = new java.util.HashSet<>();
        long previousStart = Long.MIN_VALUE;
        for (JobEnvelope.Segment s : segments) {
            if (isBlank(s.id())) {
                problems.add("segment.id 不能为空");
            } else if (!ids.add(s.id())) {
                problems.add("segment.id 重复: " + s.id());
            }
            if (s.text() == null || s.text().trim().isEmpty()) {
                problems.add("segment " + s.id() + " 的 text trim 后不能为空");
            }
            if (s.startMs() < 0 || s.endMs() <= s.startMs() || s.endMs() > rangeEnd) {
                problems.add("segment " + s.id() + " 时间非法 [" + s.startMs() + "," + s.endMs()
                        + ")，要求 0 <= 起 < 止 <= " + rangeEnd);
            }
            if (s.startMs() < previousStart) {
                problems.add("segments 必须按 startMs 排序，违规于 " + s.id());
            }
            previousStart = s.startMs();
            // 每个片段必须完整落在某个 processedRange 之内
            boolean covered = processed.stream().anyMatch(
                    r -> s.startMs() >= r.startMs() && s.endMs() <= r.endMs());
            if (!covered) {
                problems.add("segment " + s.id() + " [" + s.startMs() + "," + s.endMs()
                        + ") 未完整落在任何 processedRange 内");
            }
            Double confidence = s.confidence();
            if (confidence != null && (confidence < 0 || confidence > 1)) {
                problems.add("segment " + s.id() + " 的 confidence 必须在 [0,1] 或为 null");
            }
        }
    }

    private static void checkId(List<String> problems, String field, String value) {
        if (isBlank(value)) {
            problems.add(field + " 必填且不能为空");
        } else if (value.length() > 128) {
            problems.add(field + " 长度不能超过 128");
        }
    }

    private static void checkUtc(List<String> problems, String field, Instant value) {
        if (value == null) {
            problems.add(field + " 必填（UTC 带 Z）");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
