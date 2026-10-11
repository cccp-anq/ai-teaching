package com.aijiaoxue.videoreact.job.wire;

import com.aijiaoxue.videoreact.job.service.JobEnvelopeAssembler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JobEnvelope 契约不变量测试：把官方校验脚本 {@code verify_contract.py} 对信封的断言
 * 用 Java 复现一遍。
 *
 * <p><b>为什么要在 Java 侧再写一遍</b>：脚本校验的是 JSON fixture，而模块真正产出的是
 * Java record。两者之间只靠人工比对迟早会漂移。例如脚本第 96 行要求
 * {@code sum(overlap(span, processed)) == span[1] - span[0]}，即片段必须<b>完整落在
 * processedRanges 之内</b>，而不只是落在 analysisRange 内；只要 fixture 恰好没有
 * "片段跨过 processedRanges 空洞"的场景，实现漏掉这条也不会被发现。
 * 把断言搬进单元测试后，任何一侧的规则变化都会在这里立刻失败。
 *
 * <p>{@link #validate(JobEnvelope)} 是脚本 {@code envelope()} 函数的直译：
 * 校验顺序、边界（{@code 0 <= startMs < endMs <= durationMs}）以及
 * "遇到第一个违反就抛错"的行为都与脚本一致，便于与脚本逐行对照。
 *
 * <p><b>范围说明</b>：脚本里 HEAD_UP 载荷（windows / quality / headUpRatio）的断言
 * 属于抬头模块；本部署在请求校验阶段就以 403 FORBIDDEN 拒绝 HEAD_UP
 * （见 {@code JobRequestValidator}），且 {@link JobEnvelope.TranscriptionPayload}
 * 根本没有窗口结构，因此这里只复现信封级不变量与 TRANSCRIPTION 载荷断言，
 * 遇到 HEAD_UP 载荷会显式判为"不适用"。
 *
 * <p>全部用例都是内存对象断言：不需要网络、凭证、数据库或 ffmpeg。
 */
class JobEnvelopeInvariantsTest {

    /** 固定时间点，避免用例依赖当前时间。 */
    private static final Instant CREATED_AT = Instant.parse("2026-09-13T10:00:00Z");

    /** 固定时间点，且晚于 {@link #CREATED_AT}。 */
    private static final Instant UPDATED_AT = Instant.parse("2026-09-13T10:05:00Z");

    /** 与 fixture 量级一致的媒体时长。 */
    private static final long DURATION_MS = 60_000L;

    /** 合法的 64 位小写十六进制 hash。 */
    private static final String SHA256 = "a".repeat(64);

    /** 覆盖整个 analysisRange 的已处理范围。 */
    private static final List<JobEnvelope.Range> FULL_RANGE =
            List.of(new JobEnvelope.Range(0L, DURATION_MS));

    // ==================================================================
    //  契约不变量：verify_contract.py envelope() 的 Java 直译
    // ==================================================================

    /** 违反契约不变量时抛出；与脚本里 {@code ValueError} 的角色一致。 */
    private static final class ContractViolation extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ContractViolation(String message) {
            super(message);
        }
    }

    /**
     * 校验一个信封是否满足契约不变量，违反则抛 {@link ContractViolation}。
     *
     * <p>对应脚本 {@code verify_contract.py} 的 {@code envelope(value)}。
     *
     * @param envelope 待校验信封
     */
    private static void validate(JobEnvelope envelope) {
        require(envelope != null, "信封不能为 null");

        // ---- 版本与必填标识（脚本第 43~46 行） ----
        require(JobEnvelope.SCHEMA_VERSION.equals(envelope.schemaVersion()),
                "schemaVersion 必须为 " + JobEnvelope.SCHEMA_VERSION);
        String[] idNames = {"clientRequestId", "jobId", "providerId", "moduleVersion",
                "classSessionId", "mediaId"};
        String[] idValues = {envelope.clientRequestId(), envelope.jobId(), envelope.providerId(),
                envelope.moduleVersion(), envelope.classSessionId(), envelope.mediaId()};
        for (int i = 0; i < idNames.length; i++) {
            String value = idValues[i];
            require(value != null && !value.isEmpty() && value.length() <= 128,
                    idNames[i] + " 必须为 1~128 字符的非空字符串");
        }
        require("TRANSCRIPTION".equals(envelope.moduleType()) || "HEAD_UP".equals(envelope.moduleType()),
                "moduleType 必须为 TRANSCRIPTION 或 HEAD_UP");
        require(envelope.durationMs() != null && envelope.durationMs() > 0,
                "durationMs 必须为正整数毫秒");
        require(isLowerHex64(envelope.mediaSha256()),
                "mediaSha256 必须为 64 位小写十六进制");
        require(JobEnvelope.TIME_BASE.equals(envelope.timeBase()),
                "timeBase 必须为 " + JobEnvelope.TIME_BASE);

        // Java 侧用 Instant 承载时间，序列化必然是 UTC 带 Z；这里仍保留"非 null 且 Z 结尾"的断言，
        // 一是与脚本一一对应，二是防止将来改成 String 字段后漏检。
        require(isUtcZ(envelope.createdAt()), "createdAt 必须是 UTC 带 Z 的时间");
        require(isUtcZ(envelope.updatedAt()), "updatedAt 必须是 UTC 带 Z 的时间");

        // ---- analysisRange 必须落在 [0, durationMs] 内（脚本第 52 行） ----
        JobRequest.AnalysisRange analysis = envelope.analysisRange();
        require(analysis != null, "缺少 analysisRange");
        long[] bounds = interval(analysis.startMs(), analysis.endMs(), 0L, envelope.durationMs(),
                "analysisRange");

        // ---- 状态与 revision（脚本第 53~55 行） ----
        JobStatus status = envelope.status();
        require(status != null, "status 必填");
        int expectedRevision = status.isTerminal() ? 1 : 0;
        require(envelope.revision() != null && envelope.revision() == expectedRevision,
                "revision 必须为 " + expectedRevision + "（状态 " + status + "）");

        // ---- 非成功状态：payload 必须为 null（脚本第 56~64 行） ----
        if (status != JobStatus.SUCCEEDED) {
            require(envelope.payload() == null, "非成功状态的 payload 必须为 null");
            if (status == JobStatus.FAILED) {
                JobEnvelope.JobError error = envelope.error();
                require(error != null, "FAILED 必须有 error");
                require(error.code() != null && !error.code().isBlank(), "error.code 不能为空");
                require(error.message() != null && !error.message().isBlank(), "error.message 不能为空");
                require(error.retryable() != null, "error.retryable 必填");
            } else {
                require(envelope.error() == null, status + " 的 error 必须为 null");
            }
            return;
        }

        // ---- SUCCEEDED：error 为 null，payload 必填（脚本第 65~68 行） ----
        require(envelope.error() == null, "SUCCEEDED 的 error 必须为 null");
        JobEnvelope.TranscriptionPayload payload = envelope.payload();
        require(payload != null, "SUCCEEDED 必须有 payload");
        require(payload.warnings() != null, "payload.warnings 必填");
        for (String warning : payload.warnings()) {
            require(warning != null, "payload.warnings 的元素必须是非 null 字符串");
        }

        // ---- processedRanges：非空、有序、不重叠、在 analysisRange 内（脚本第 69~74 行） ----
        require(payload.processedRanges() != null && !payload.processedRanges().isEmpty(),
                "payload.processedRanges 必须非空");
        List<long[]> processed = new ArrayList<>();
        for (JobEnvelope.Range range : payload.processedRanges()) {
            require(range != null, "processedRanges 的元素不能为 null");
            processed.add(interval(range.startMs(), range.endMs(), bounds[0], bounds[1], "processedRanges"));
        }
        require(isSorted(processed), "processedRanges 必须按 startMs 排序");
        for (int i = 1; i < processed.size(); i++) {
            require(processed.get(i - 1)[1] <= processed.get(i)[0],
                    "processedRanges 之间不得重叠（允许相接）");
        }
        if (unionSize(processed) < bounds[1] - bounds[0]) {
            require(payload.warnings().contains("PARTIAL_PROCESSING"),
                    "未覆盖完整 analysisRange 时必须带 PARTIAL_PROCESSING");
        }

        // ---- TRANSCRIPTION 载荷：language / engineVersion / segments（脚本第 75~103 行） ----
        // 脚本在这一步按 moduleType 二分支；本部署不产出 HEAD_UP 载荷，故显式判为不适用。
        require("TRANSCRIPTION".equals(envelope.moduleType()),
                "本部署只产出 TRANSCRIPTION 载荷，HEAD_UP 断言属于抬头模块");
        require(payload.language() != null && !payload.language().isBlank(),
                "payload.language 不能为空");
        require(payload.engineVersion() != null && !payload.engineVersion().isBlank(),
                "payload.engineVersion 不能为空");
        require(payload.segments() != null, "payload.segments 必填");
        if (payload.segments().isEmpty()) {
            require(payload.warnings().contains("NO_SPEECH"),
                    "segments 为空时必须带 NO_SPEECH（合法静音与解码失败必须可区分）");
        }

        Set<String> seenIds = new HashSet<>();
        List<long[]> spans = new ArrayList<>();
        for (JobEnvelope.Segment row : payload.segments()) {
            require(row != null, "segments 的元素不能为 null");
            require(row.id() != null && !row.id().isBlank(), "segment.id 必须非空");
            require(seenIds.add(row.id()), "segment.id 在结果内必须唯一: " + row.id());

            long[] span = interval(row.startMs(), row.endMs(), bounds[0], bounds[1],
                    "segment " + row.id());
            long covered = 0;
            for (long[] range : processed) {
                covered += overlap(span, range);
            }
            require(covered == span[1] - span[0],
                    "segment " + row.id() + " 必须完整落在 processedRanges 之内");
            spans.add(span);

            require(row.text() != null && !row.text().strip().isEmpty(),
                    "segment " + row.id() + " 的 text trim 后不能为空");
            Double confidence = row.confidence();
            require(confidence == null
                            || (Double.isFinite(confidence) && confidence >= 0 && confidence <= 1),
                    "segment " + row.id() + " 的 confidence 必须为 null 或 [0,1]");
            // speakerLabel 是 optional：契约允许 null，也允许引擎给出匿名标签，脚本不做额外断言。
        }
        require(isSorted(spans), "segments 必须按 startMs 排序（重叠语音也不得乱序）");
    }

    /** 断言信封满足全部不变量（脚本里"合法 fixture 必须通过"的一侧）。 */
    private static void assertValid(String why, JobEnvelope envelope) {
        assertThatCode(() -> validate(envelope))
                .as("合法信封应通过不变量校验: %s", why)
                .doesNotThrowAnyException();
    }

    /** 断言信封被拒绝（脚本里 {@code reject()} 的一侧：非法 fixture 必须抛错）。 */
    private static void assertRejected(String why, JobEnvelope envelope) {
        assertThatThrownBy(() -> validate(envelope))
                .as("非法信封必须被拒绝: %s", why)
                .isInstanceOf(ContractViolation.class);
    }

    // ==================================================================
    //  信封级不变量
    // ==================================================================

    /**
     * 合法信封：四种状态各自的合法形态都必须通过。
     *
     * <p>顺带验证三个工厂方法（{@code inProgress/succeeded/failed}）产出的就是合法形态。
     */
    @Test
    void all_four_states_have_a_valid_shape() {
        JobEnvelope template = envelope().build();

        assertValid("QUEUED", envelope().status(JobStatus.QUEUED).revision(0).build());
        assertValid("RUNNING", envelope().status(JobStatus.RUNNING).revision(0).build());
        assertValid("SUCCEEDED", succeededEnvelope());
        assertValid("FAILED", failedEnvelope());

        assertValid("工厂 inProgress(QUEUED)",
                JobEnvelope.inProgress(template, JobStatus.QUEUED, UPDATED_AT));
        assertValid("工厂 inProgress(RUNNING)",
                JobEnvelope.inProgress(template, JobStatus.RUNNING, UPDATED_AT));
        assertValid("工厂 succeeded",
                JobEnvelope.succeeded(template, defaultPayload(), UPDATED_AT));
        assertValid("工厂 failed",
                JobEnvelope.failed(template, validError(), UPDATED_AT));

        assertThat(JobEnvelope.inProgress(template, JobStatus.RUNNING, UPDATED_AT).revision()).isZero();
        assertThat(JobEnvelope.succeeded(template, defaultPayload(), UPDATED_AT).revision()).isEqualTo(1);
        assertThat(JobEnvelope.failed(template, validError(), UPDATED_AT).revision()).isEqualTo(1);
    }

    /**
     * revision：非终态必须为 0。
     *
     * <p>契约 §4「非终态为0，终态为1；本版不支持同job修改终态」。
     */
    @Test
    void revision_must_be_zero_for_non_terminal_states() {
        assertValid("QUEUED + 0", envelope().status(JobStatus.QUEUED).revision(0).build());
        assertValid("RUNNING + 0", envelope().status(JobStatus.RUNNING).revision(0).build());

        assertRejected("QUEUED + 1", envelope().status(JobStatus.QUEUED).revision(1).build());
        assertRejected("RUNNING + 1", envelope().status(JobStatus.RUNNING).revision(1).build());
        assertRejected("QUEUED + revision 缺失", envelope().status(JobStatus.QUEUED).revision(null).build());
    }

    /**
     * revision：终态必须为 1。
     */
    @Test
    void revision_must_be_one_for_terminal_states() {
        assertValid("SUCCEEDED + 1", succeededEnvelope());
        assertValid("FAILED + 1", failedEnvelope());

        assertRejected("SUCCEEDED + 0", envelope().status(JobStatus.SUCCEEDED).revision(0)
                .payload(defaultPayload()).build());
        assertRejected("FAILED + 0", envelope().status(JobStatus.FAILED).revision(0)
                .error(validError()).build());
        assertRejected("SUCCEEDED + 2（同 job 改终态不被支持）",
                envelope().status(JobStatus.SUCCEEDED).revision(2).payload(defaultPayload()).build());
    }

    /**
     * SUCCEEDED：必须有 payload，且 error 必须为 null。
     */
    @Test
    void succeeded_requires_payload_and_forbids_error() {
        assertValid("有 payload 且无 error", succeededEnvelope());

        assertRejected("缺 payload", envelope().status(JobStatus.SUCCEEDED).revision(1).build());
        assertRejected("error 非 null", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(defaultPayload()).error(validError()).build());
    }

    /**
     * FAILED：必须有 error（含 code/message/retryable），且 payload 必须为 null。
     */
    @Test
    void failed_requires_error_and_forbids_payload() {
        assertValid("有 error 且无 payload", failedEnvelope());

        assertRejected("缺 error", envelope().status(JobStatus.FAILED).revision(1).build());
        assertRejected("payload 非 null", envelope().status(JobStatus.FAILED).revision(1)
                .payload(defaultPayload()).error(validError()).build());
        assertRejected("error.code 为空", envelope().status(JobStatus.FAILED).revision(1)
                .error(new JobEnvelope.JobError(" ", "处理失败", true)).build());
        assertRejected("error.message 为空", envelope().status(JobStatus.FAILED).revision(1)
                .error(new JobEnvelope.JobError("PROCESSING_FAILED", "  ", true)).build());
        assertRejected("error.retryable 缺失", envelope().status(JobStatus.FAILED).revision(1)
                .error(new JobEnvelope.JobError("PROCESSING_FAILED", "处理失败", null)).build());
    }

    /**
     * QUEUED/RUNNING：payload 与 error 都必须为 null。
     */
    @Test
    void queued_and_running_must_have_null_payload_and_error() {
        assertValid("QUEUED 全 null", envelope().status(JobStatus.QUEUED).revision(0).build());
        assertValid("RUNNING 全 null", envelope().status(JobStatus.RUNNING).revision(0).build());

        assertRejected("QUEUED 带 payload", envelope().status(JobStatus.QUEUED).revision(0)
                .payload(defaultPayload()).build());
        assertRejected("RUNNING 带 payload", envelope().status(JobStatus.RUNNING).revision(0)
                .payload(defaultPayload()).build());
        assertRejected("QUEUED 带 error", envelope().status(JobStatus.QUEUED).revision(0)
                .error(validError()).build());
        assertRejected("RUNNING 带 error", envelope().status(JobStatus.RUNNING).revision(0)
                .error(validError()).build());
    }

    /**
     * schemaVersion 必须为 "1.0"。
     */
    @Test
    void schema_version_must_be_exactly_one_zero() {
        assertValid("1.0", envelope().build());

        assertRejected("2.0", envelope().schemaVersion("2.0").build());
        assertRejected("缺失", envelope().schemaVersion(null).build());
        assertRejected("空串", envelope().schemaVersion("").build());
    }

    /**
     * timeBase 必须为 "MEDIA_START_MS"。
     */
    @Test
    void time_base_must_be_media_start_ms() {
        assertValid("MEDIA_START_MS", envelope().build());

        assertRejected("缺失", envelope().timeBase(null).build());
        assertRejected("CLIP_START_MS", envelope().timeBase("CLIP_START_MS").build());
    }

    /**
     * 必填标识：不能为空、不能超过 128 字符。
     */
    @Test
    void identity_fields_must_be_non_blank_and_within_128_chars() {
        assertValid("128 字符边界", envelope().clientRequestId("c".repeat(128)).build());

        assertRejected("clientRequestId 缺失", envelope().clientRequestId(null).build());
        assertRejected("clientRequestId 空串", envelope().clientRequestId("").build());
        assertRejected("clientRequestId 超长", envelope().clientRequestId("c".repeat(129)).build());
        assertRejected("jobId 缺失", envelope().jobId(null).build());
        assertRejected("providerId 缺失", envelope().providerId(null).build());
        assertRejected("moduleVersion 缺失", envelope().moduleVersion(null).build());
        assertRejected("classSessionId 超长", envelope().classSessionId("c".repeat(129)).build());
        assertRejected("mediaId 空串", envelope().mediaId("").build());
    }

    /**
     * durationMs 必须为正整数；mediaSha256 必须为 64 位小写十六进制。
     */
    @Test
    void duration_and_hash_follow_contract_format() {
        assertValid("正整数时长 + 小写 hash", envelope().build());

        assertRejected("durationMs 为 0", envelope().durationMs(0L).build());
        assertRejected("durationMs 为负", envelope().durationMs(-1L).build());
        assertRejected("durationMs 缺失", envelope().durationMs(null).build());
        assertRejected("hash 大写", envelope().mediaSha256(SHA256.toUpperCase()).build());
        assertRejected("hash 长度不足", envelope().mediaSha256("a".repeat(63)).build());
        assertRejected("hash 含非十六进制字符", envelope().mediaSha256("g".repeat(64)).build());
        assertRejected("hash 缺失", envelope().mediaSha256(null).build());
    }

    /**
     * createdAt/updatedAt 必须是带 Z 的 UTC 时间（Java 侧不允许 null）。
     */
    @Test
    void timestamps_must_be_utc_with_z_suffix() {
        assertValid("两个时间都带 Z", envelope().build());
        assertThat(CREATED_AT.toString()).endsWith("Z");

        assertRejected("createdAt 缺失", envelope().createdAt(null).build());
        assertRejected("updatedAt 缺失", envelope().updatedAt(null).build());
    }

    /**
     * analysisRange 必须满足 {@code 0 <= startMs < endMs <= durationMs}。
     */
    @Test
    void analysis_range_must_be_half_open_and_inside_media() {
        assertValid("整条媒体", envelope().build());
        assertValid("首尾相接的合法子区间",
                envelope().analysisRange(new JobRequest.AnalysisRange(10_000L, 20_000L))
                        .payload(payload(List.of(new JobEnvelope.Range(10_000L, 20_000L)),
                                List.of(seg("seg-001", 10_000L, 12_000L, "裁剪后的识别结果")), List.of()))
                        .status(JobStatus.SUCCEEDED).revision(1).build());

        assertRejected("startMs 为负",
                envelope().analysisRange(new JobRequest.AnalysisRange(-1L, 1000L)).build());
        assertRejected("endMs <= startMs（空区间）",
                envelope().analysisRange(new JobRequest.AnalysisRange(1000L, 1000L)).build());
        assertRejected("endMs > durationMs",
                envelope().analysisRange(new JobRequest.AnalysisRange(0L, DURATION_MS + 1)).build());
        assertRejected("analysisRange 缺失", envelope().analysisRange(null).build());
        assertRejected("startMs 缺失",
                envelope().analysisRange(new JobRequest.AnalysisRange(null, 1000L)).build());
    }

    // ==================================================================
    //  SUCCEEDED payload：processedRanges
    // ==================================================================

    /**
     * processedRanges 必须非空。
     *
     * <p>契约：没有任何成功处理区间应 FAILED，不能给出空 processedRanges 的"成功"。
     */
    @Test
    void processed_ranges_must_not_be_empty() {
        assertValid("单个覆盖全范围", succeededEnvelope());

        assertRejected("空数组", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(List.of(), defaultSegments(), List.of())).build());
        assertRejected("processedRanges 缺失", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(null, defaultSegments(), List.of())).build());
    }

    /**
     * processedRanges 必须按 startMs 排序。
     */
    @Test
    void processed_ranges_must_be_sorted() {
        assertValid("升序", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(List.of(new JobEnvelope.Range(0L, 2000L),
                                new JobEnvelope.Range(2000L, 5000L)),
                        defaultSegments(), List.of("PARTIAL_PROCESSING"))).build());

        assertRejected("逆序", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(List.of(new JobEnvelope.Range(2000L, 5000L),
                                new JobEnvelope.Range(0L, 2000L)),
                        defaultSegments(), List.of("PARTIAL_PROCESSING"))).build());
    }

    /**
     * processedRanges 之间不得重叠；相接（{@code a.end == b.start}）是允许的。
     */
    @Test
    void processed_ranges_must_not_overlap_but_may_touch() {
        assertValid("相接", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(List.of(new JobEnvelope.Range(0L, 2000L),
                                new JobEnvelope.Range(2000L, 5000L)),
                        defaultSegments(), List.of("PARTIAL_PROCESSING"))).build());

        assertRejected("重叠 1ms", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(List.of(new JobEnvelope.Range(0L, 2001L),
                                new JobEnvelope.Range(2000L, 5000L)),
                        defaultSegments(), List.of("PARTIAL_PROCESSING"))).build());
    }

    /**
     * processedRanges 必须落在 analysisRange 内。
     */
    @Test
    void processed_ranges_must_stay_inside_analysis_range() {
        assertValid("恰好等于 analysisRange", succeededEnvelope());

        assertRejected("起点早于 analysisRange.startMs",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .analysisRange(new JobRequest.AnalysisRange(100L, DURATION_MS))
                        .payload(payload(List.of(new JobEnvelope.Range(0L, 3000L)),
                                List.of(seg("seg-001", 100L, 3000L, "文本")),
                                List.of("PARTIAL_PROCESSING"))).build());
        assertRejected("终点晚于 analysisRange.endMs",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .payload(payload(List.of(new JobEnvelope.Range(0L, DURATION_MS + 1)),
                                defaultSegments(), List.of())).build());
        assertRejected("零长度区间（startMs == endMs）",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .payload(payload(List.of(new JobEnvelope.Range(1000L, 1000L)),
                                List.of(), List.of("PARTIAL_PROCESSING", "NO_SPEECH"))).build());
    }

    /**
     * 部分处理必须显式带 PARTIAL_PROCESSING，不能冒充处理完整课堂。
     */
    @Test
    void partial_coverage_requires_partial_processing_warning() {
        List<JobEnvelope.Range> partial = List.of(new JobEnvelope.Range(0L, 30_000L));

        assertValid("部分覆盖 + 警告", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(partial, defaultSegments(), List.of("PARTIAL_PROCESSING"))).build());
        assertValid("全覆盖无需警告", succeededEnvelope());

        assertRejected("部分覆盖但缺警告", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(partial, defaultSegments(), List.of())).build());
    }

    /**
     * warnings 的元素必须是字符串（Java 侧即不允许 null 元素）。
     */
    @Test
    void warnings_must_be_string_list() {
        assertValid("空数组", succeededEnvelope());
        assertValid("含警告", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, defaultSegments(),
                        List.of("DURATION_MISMATCH:registered=60000,actual=60100"))).build());

        assertRejected("warnings 缺失", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, defaultSegments(), null)).build());
        assertRejected("warnings 含 null 元素", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, defaultSegments(), Arrays.asList("NO_SPEECH", null))).build());
    }

    // ==================================================================
    //  SUCCEEDED payload：language / engineVersion / segments
    // ==================================================================

    /**
     * language 与 engineVersion 必须非空。
     */
    @Test
    void language_and_engine_version_must_be_present() {
        assertValid("zh-CN + 引擎版本", succeededEnvelope());

        assertRejected("language 为空", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(new JobEnvelope.TranscriptionPayload(" ", "wand-asr-v1",
                        FULL_RANGE, defaultSegments(), List.of())).build());
        assertRejected("engineVersion 为空", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(new JobEnvelope.TranscriptionPayload("zh-CN", "",
                        FULL_RANGE, defaultSegments(), List.of())).build());
    }

    /**
     * segments 为空（合法静音）时必须带 NO_SPEECH，以区别于"解码失败被包装成静音"。
     */
    @Test
    void empty_segments_requires_no_speech_warning() {
        assertValid("空 segments + NO_SPEECH", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(), List.of("NO_SPEECH"))).build());

        assertRejected("空 segments 却没有 NO_SPEECH", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(), List.of())).build());
        assertRejected("segments 缺失", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, null, List.of("NO_SPEECH"))).build());
    }

    /**
     * segment.id 必须非空且在结果内唯一。
     */
    @Test
    void segment_ids_must_be_unique_and_non_blank() {
        assertValid("seg-001 / seg-002", succeededEnvelope());

        assertRejected("重复 id", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", 0L, 1000L, "第一句"),
                        seg("seg-001", 1000L, 2000L, "第二句")), List.of())).build());
        assertRejected("空 id", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg(" ", 0L, 1000L, "第一句")), List.of())).build());
    }

    /**
     * segment.text trim 后必须非空（空文本片段应被省略，而不是原样返回）。
     */
    @Test
    void segment_text_must_not_be_blank_after_trim() {
        assertValid("正常文本", succeededEnvelope());
        assertValid("前后空白但有内容", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", 0L, 1000L, "  有内容  ")),
                        List.of())).build());

        assertRejected("纯空格", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", 0L, 1000L, "   ")), List.of())).build());
        assertRejected("text 缺失", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", 0L, 1000L, null)), List.of())).build());
    }

    /**
     * segment 时间必须满足 {@code 0 <= startMs < endMs <= durationMs}。
     */
    @Test
    void segment_times_must_be_valid_intervals_inside_media() {
        assertValid("整条媒体内的片段", succeededEnvelope());
        assertValid("贴着末尾的片段", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", DURATION_MS - 1, DURATION_MS, "末尾")),
                        List.of())).build());

        assertRejected("startMs 为负", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", -1L, 1000L, "越界")), List.of())).build());
        assertRejected("endMs 超过 durationMs", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", 0L, DURATION_MS + 1, "越界")),
                        List.of())).build());
        assertRejected("endMs == startMs（零长度）", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", 1000L, 1000L, "零长度")),
                        List.of())).build());
        assertRejected("endMs < startMs", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", 2000L, 1000L, "倒序")),
                        List.of())).build());
    }

    /**
     * 每个 segment 必须<b>完整落在 processedRanges 之内</b>——只落在 analysisRange 内是不够的。
     *
     * <p>这条对应脚本第 96 行 {@code check(sum(overlap(span, p) for p in processed) == span[1] - span[0])}，
     * 是最容易被"只校验 analysisRange"的实现漏掉的一条。
     */
    @Test
    void every_segment_must_fall_entirely_inside_processed_ranges() {
        assertValid("片段落在唯一已处理范围内", succeededEnvelope());

        // 已处理范围中间有空洞 [2000,5000)
        List<JobEnvelope.Range> gapped = List.of(new JobEnvelope.Range(0L, 2000L),
                new JobEnvelope.Range(5000L, DURATION_MS));

        assertValid("空心区间内、但不跨越空洞的片段",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .payload(payload(gapped, List.of(seg("seg-001", 5000L, 6000L, "空洞之后")),
                                List.of("PARTIAL_PROCESSING"))).build());
        assertRejected("片段跨越空洞",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .payload(payload(gapped, List.of(seg("seg-001", 1000L, 6000L, "跨空洞")),
                                List.of("PARTIAL_PROCESSING"))).build());
        assertRejected("片段完整落在空洞里",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .payload(payload(gapped, List.of(seg("seg-001", 2000L, 4000L, "空洞里")),
                                List.of("PARTIAL_PROCESSING"))).build());
    }

    /**
     * segments 必须按 startMs 排序（允许语音重叠，但顺序必须稳定）。
     */
    @Test
    void segments_must_be_sorted_by_start() {
        assertValid("升序", succeededEnvelope());
        assertValid("起点相同、终点递增（重叠语音）",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .payload(payload(FULL_RANGE, List.of(seg("seg-001", 1000L, 2000L, "短"),
                                seg("seg-002", 1000L, 3000L, "长")), List.of())).build());

        assertRejected("逆序", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE, List.of(seg("seg-001", 3000L, 4000L, "后"),
                        seg("seg-002", 1000L, 2000L, "前")), List.of())).build());
        assertRejected("起点相同但终点递减（脚本按元组排序）",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .payload(payload(FULL_RANGE, List.of(seg("seg-001", 1000L, 3000L, "长"),
                                seg("seg-002", 1000L, 2000L, "短")), List.of())).build());
    }

    /**
     * confidence 为 null 或 [0,1]；引擎不提供就必须是 null，不能填 1 冒充。
     */
    @Test
    void confidence_must_be_null_or_within_unit_interval() {
        assertValid("null（引擎未提供置信度）", succeededEnvelope());
        assertValid("0.0", withConfidence(0.0));
        assertValid("1.0", withConfidence(1.0));
        assertValid("0.87", withConfidence(0.87));

        assertRejected("负数", withConfidence(-0.01));
        assertRejected("大于 1", withConfidence(1.01));
        assertRejected("NaN", withConfidence(Double.NaN));
    }

    /**
     * speakerLabel 允许为 null（首版不支持说话人分离时不得编造标签）。
     */
    @Test
    void speaker_label_may_be_null() {
        assertValid("speakerLabel = null", succeededEnvelope());
        assertValid("speakerLabel = 匿名标签（契约允许 optional 提供）",
                envelope().status(JobStatus.SUCCEEDED).revision(1)
                        .payload(payload(FULL_RANGE,
                                List.of(new JobEnvelope.Segment("seg-001", 0L, 2000L, "你好",
                                        null, "speaker-1")), List.of())).build());
        assertThat(defaultSegments().get(0).speakerLabel()).isNull();
        assertThat(defaultSegments().get(0).confidence()).isNull();
    }

    /**
     * moduleType 只能是契约定义的两个值；HEAD_UP 载荷不适用本部署。
     */
    @Test
    void module_type_must_be_one_of_two_contract_values() {
        assertValid("TRANSCRIPTION", envelope().build());

        assertRejected("未知类型", envelope().moduleType("SUMMARY").build());
        assertRejected("缺失", envelope().moduleType(null).build());
        assertRejected("HEAD_UP 载荷不适用本部署",
                envelope().moduleType("HEAD_UP").status(JobStatus.SUCCEEDED).revision(1)
                        .payload(defaultPayload()).build());
    }

    // ==================================================================
    //  与主代码自检的交叉验证（防止两处规则各自漂移）
    // ==================================================================

    /**
     * 信封级不变量：测试内复刻的脚本规则与主代码 {@link JobEnvelopeAssembler#checkInvariants}
     * 必须给出一致结论。
     *
     * <p>两处规则只要有一处放宽（或收紧），本用例立刻失败——这正是"本地实现与官方校验脚本
     * 漂移"最容易发生的方向。
     */
    @Test
    void envelope_level_invariants_agree_with_production_self_check() {
        // 合法：两边都接受
        assertBothAccept("QUEUED", envelope().status(JobStatus.QUEUED).revision(0).build());
        assertBothAccept("RUNNING", envelope().status(JobStatus.RUNNING).revision(0).build());
        assertBothAccept("SUCCEEDED", succeededEnvelope());
        assertBothAccept("FAILED", failedEnvelope());

        // 非法：两边都拒绝
        assertBothReject("QUEUED + revision 1", envelope().status(JobStatus.QUEUED).revision(1).build());
        assertBothReject("RUNNING + revision 缺失",
                envelope().status(JobStatus.RUNNING).revision(null).build());
        assertBothReject("SUCCEEDED 缺 payload",
                envelope().status(JobStatus.SUCCEEDED).revision(1).build());
        assertBothReject("SUCCEEDED 带 error", envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(defaultPayload()).error(validError()).build());
        assertBothReject("FAILED 缺 error", envelope().status(JobStatus.FAILED).revision(1).build());
        assertBothReject("FAILED 带 payload", envelope().status(JobStatus.FAILED).revision(1)
                .payload(defaultPayload()).error(validError()).build());
        assertBothReject("QUEUED 带 error", envelope().status(JobStatus.QUEUED).revision(0)
                .error(validError()).build());
        assertBothReject("schemaVersion 2.0", envelope().schemaVersion("2.0").build());
        assertBothReject("timeBase 错误", envelope().timeBase("CLIP_START_MS").build());
    }

    /** 工厂方法误传终态会产出非法信封：契约要求调用方用对应的工厂，不能把终态塞进 inProgress。 */
    @Test
    void in_progress_factory_rejects_terminal_status_by_invariants() {
        JobEnvelope template = envelope().build();

        assertRejected("inProgress(SUCCEEDED)",
                JobEnvelope.inProgress(template, JobStatus.SUCCEEDED, UPDATED_AT));
        assertRejected("inProgress(FAILED)",
                JobEnvelope.inProgress(template, JobStatus.FAILED, UPDATED_AT));
    }

    private static void assertBothAccept(String why, JobEnvelope envelope) {
        assertValid(why, envelope);
        assertThat(JobEnvelopeAssembler.checkInvariants(envelope))
                .as("主代码自检也应接受: %s", why)
                .isEmpty();
    }

    private static void assertBothReject(String why, JobEnvelope envelope) {
        assertRejected(why, envelope);
        assertThat(JobEnvelopeAssembler.checkInvariants(envelope))
                .as("主代码自检也应拒绝: %s", why)
                .isNotEmpty();
    }

    // ==================================================================
    //  测试夹具
    // ==================================================================

    /** 合法成功信封：全范围处理 + 两条片段。 */
    private static JobEnvelope succeededEnvelope() {
        return envelope().status(JobStatus.SUCCEEDED).revision(1).payload(defaultPayload()).build();
    }

    /** 合法失败信封。 */
    private static JobEnvelope failedEnvelope() {
        return envelope().status(JobStatus.FAILED).revision(1).error(validError()).build();
    }

    /** 契约 §5 里合法的任务内 error。 */
    private static JobEnvelope.JobError validError() {
        return new JobEnvelope.JobError("PROCESSING_FAILED", "识别引擎返回失败", false);
    }

    /** 默认成功载荷：覆盖整个 analysisRange，两条片段，无警告。 */
    private static JobEnvelope.TranscriptionPayload defaultPayload() {
        return payload(FULL_RANGE, defaultSegments(), List.of());
    }

    /** 构造成功载荷。 */
    private static JobEnvelope.TranscriptionPayload payload(
            List<JobEnvelope.Range> processedRanges,
            List<JobEnvelope.Segment> segments,
            List<String> warnings) {
        return new JobEnvelope.TranscriptionPayload("zh-CN", "wand-asr-v1",
                processedRanges, segments, warnings);
    }

    /** 默认片段：时间在 [0,5000) 内，confidence/speakerLabel 都为 null。 */
    private static List<JobEnvelope.Segment> defaultSegments() {
        return List.of(
                seg("seg-001", 0L, 2000L, "大家好"),
                seg("seg-002", 2000L, 5000L, "今天我们讲傅里叶变换"));
    }

    /** 构造片段，confidence 与 speakerLabel 为 null。 */
    private static JobEnvelope.Segment seg(String id, long startMs, long endMs, String text) {
        return new JobEnvelope.Segment(id, startMs, endMs, text, null, null);
    }

    /** 只有一条片段且带置信度的成功信封。 */
    private static JobEnvelope withConfidence(Double confidence) {
        return envelope().status(JobStatus.SUCCEEDED).revision(1)
                .payload(payload(FULL_RANGE,
                        List.of(new JobEnvelope.Segment("seg-001", 0L, 2000L, "大家好",
                                confidence, null)),
                        List.of()))
                .build();
    }

    /** 便于逐字段替换的构造器：避免每个非法用例都重复写 18 个参数。 */
    private static EnvelopeBuilder envelope() {
        return new EnvelopeBuilder();
    }

    /** 信封构造器，默认产出合法的 QUEUED 信封。 */
    private static final class EnvelopeBuilder {

        private String schemaVersion = JobEnvelope.SCHEMA_VERSION;
        private String clientRequestId = "req-001";
        private String jobId = "job-0001";
        private String moduleType = "TRANSCRIPTION";
        private String providerId = "transcription-local";
        private String moduleVersion = "transcription-1.0.0";
        private String classSessionId = "cs-001";
        private String mediaId = "media-001";
        private String mediaSha256 = SHA256;
        private Long durationMs = DURATION_MS;
        private String timeBase = JobEnvelope.TIME_BASE;
        private JobRequest.AnalysisRange analysisRange = new JobRequest.AnalysisRange(0L, DURATION_MS);
        private JobStatus status = JobStatus.QUEUED;
        private Integer revision = 0;
        private Instant createdAt = CREATED_AT;
        private Instant updatedAt = UPDATED_AT;
        private JobEnvelope.TranscriptionPayload payload;
        private JobEnvelope.JobError error;

        EnvelopeBuilder schemaVersion(String value) {
            this.schemaVersion = value;
            return this;
        }

        EnvelopeBuilder clientRequestId(String value) {
            this.clientRequestId = value;
            return this;
        }

        EnvelopeBuilder jobId(String value) {
            this.jobId = value;
            return this;
        }

        EnvelopeBuilder moduleType(String value) {
            this.moduleType = value;
            return this;
        }

        EnvelopeBuilder providerId(String value) {
            this.providerId = value;
            return this;
        }

        EnvelopeBuilder moduleVersion(String value) {
            this.moduleVersion = value;
            return this;
        }

        EnvelopeBuilder classSessionId(String value) {
            this.classSessionId = value;
            return this;
        }

        EnvelopeBuilder mediaId(String value) {
            this.mediaId = value;
            return this;
        }

        EnvelopeBuilder mediaSha256(String value) {
            this.mediaSha256 = value;
            return this;
        }

        EnvelopeBuilder durationMs(Long value) {
            this.durationMs = value;
            return this;
        }

        EnvelopeBuilder timeBase(String value) {
            this.timeBase = value;
            return this;
        }

        EnvelopeBuilder analysisRange(JobRequest.AnalysisRange value) {
            this.analysisRange = value;
            return this;
        }

        EnvelopeBuilder status(JobStatus value) {
            this.status = value;
            return this;
        }

        EnvelopeBuilder revision(Integer value) {
            this.revision = value;
            return this;
        }

        EnvelopeBuilder createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        EnvelopeBuilder updatedAt(Instant value) {
            this.updatedAt = value;
            return this;
        }

        EnvelopeBuilder payload(JobEnvelope.TranscriptionPayload value) {
            this.payload = value;
            return this;
        }

        EnvelopeBuilder error(JobEnvelope.JobError value) {
            this.error = value;
            return this;
        }

        JobEnvelope build() {
            return new JobEnvelope(schemaVersion, clientRequestId, jobId, moduleType, providerId,
                    moduleVersion, classSessionId, mediaId, mediaSha256, durationMs, timeBase,
                    analysisRange, status, revision, createdAt, updatedAt, payload, error);
        }
    }

    // ==================================================================
    //  与脚本同名的辅助函数
    // ==================================================================

    /** 对应脚本的 {@code check()}：不满足即抛错。 */
    private static void require(boolean satisfied, String message) {
        if (!satisfied) {
            throw new ContractViolation(message);
        }
    }

    /**
     * 对应脚本的 {@code interval()}：要求 {@code lower <= startMs < endMs <= upper}。
     *
     * @return 二元数组 {@code [startMs, endMs]}
     */
    private static long[] interval(Long startMs, Long endMs, long lower, long upper, String name) {
        require(startMs != null && endMs != null, name + " 必须同时提供 startMs 与 endMs");
        require(lower <= startMs && startMs < endMs && endMs <= upper,
                name + " 必须满足 " + lower + " <= startMs < endMs <= " + upper
                        + "，实际 [" + startMs + "," + endMs + ")");
        return new long[]{startMs, endMs};
    }

    /** 对应脚本的 {@code overlap()}。 */
    private static long overlap(long[] left, long[] right) {
        return Math.max(0, Math.min(left[1], right[1]) - Math.max(left[0], right[0]));
    }

    /** 对应脚本的 {@code union_size()}：按起点排序后累计并集长度。 */
    private static long unionSize(List<long[]> spans) {
        long total = 0;
        long end = -1;
        List<long[]> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingLong(span -> span[0]));
        for (long[] span : sorted) {
            total += Math.max(0, span[1] - Math.max(span[0], end));
            end = Math.max(end, span[1]);
        }
        return total;
    }

    /** 对应脚本的 {@code sorted(spans)} 比较：按 (startMs, endMs) 升序。 */
    private static boolean isSorted(List<long[]> spans) {
        for (int i = 1; i < spans.size(); i++) {
            long[] previous = spans.get(i - 1);
            long[] current = spans.get(i);
            if (previous[0] > current[0]
                    || (previous[0] == current[0] && previous[1] > current[1])) {
                return false;
            }
        }
        return true;
    }

    /** 对应脚本的 hash 断言：64 位且全部落在 {@code 0123456789abcdef}。 */
    private static boolean isLowerHex64(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** 对应脚本的 {@code endswith("Z")}；Java 侧用 Instant 承载，额外承担非 null 检查。 */
    private static boolean isUtcZ(Instant value) {
        return value != null && value.toString().endsWith("Z");
    }
}
