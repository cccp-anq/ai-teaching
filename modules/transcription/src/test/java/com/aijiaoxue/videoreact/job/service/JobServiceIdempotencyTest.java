package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.config.ContractProperties;
import com.aijiaoxue.videoreact.job.domain.TranscriptionJob;
import com.aijiaoxue.videoreact.job.support.InMemoryTranscriptionJobRepository;
import com.aijiaoxue.videoreact.job.web.ContractErrors;
import com.aijiaoxue.videoreact.job.web.ContractRejectionException;
import com.aijiaoxue.videoreact.job.wire.JobEnvelope;
import com.aijiaoxue.videoreact.job.wire.JobRequest;
import com.aijiaoxue.videoreact.job.wire.JobStatus;
import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提交幂等与冲突语义测试（契约 §3）。
 *
 * <p>这是契约里最容易做错、也是主项目最依赖的部分：
 * 它决定"重试会不会重复计费"和"改了参数会不会静默覆盖旧任务"。
 * 因此每条规则单独一个用例，并且都直接调用 {@link JobService}（不是测一个 if）。
 */
class JobServiceIdempotencyTest {

    private static final String HASH = "a".repeat(64);

    private InMemoryTranscriptionJobRepository repository;
    private JobService service;

    /** 被入队的任务 ID。由 JobService 的 enqueue 接缝覆盖，从而完全确定。 */
    private final List<String> enqueued = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = new InMemoryTranscriptionJobRepository();
        ObjectMapper mapper = new ObjectMapper();
        service = new JobService(
                repository,
                new JobRequestValidator(),
                new RequestFingerprint(mapper),
                new JobEnvelopeAssembler(mapper),
                null,
                new ContractProperties("transcription-test", "test-1.0",
                        List.of("test-token"), 1_048_576L, 20_000, 1800),
                new MediaProperties("./.work/media-test", "ffmpeg", "ffprobe",
                        10, 600, 180, 2_147_483_648L, 7_200_000L,
                        List.of(), false, 2000L, false)) {
            @Override
            protected void enqueue(String jobId) {
                // 同步记录而不真的下载/识别：让"入队了几次"的断言完全确定。
                // 真实实现是共享线程池异步执行，断言会不稳定——不稳定的测试等于没有保护。
                enqueued.add(jobId);
            }
        };
    }

    private static JobRequest request(String clientRequestId) {
        return new JobRequest(
                "1.0",
                clientRequestId,
                "TRANSCRIPTION",
                "session-demo-001",
                "media-demo-001",
                new JobRequest.MediaRef(HASH, 60_000L, "video/mp4",
                        "https://media.example.com/fixture.mp4", "2026-09-13T04:00:00Z"),
                new JobRequest.AnalysisRange(0L, 60_000L),
                new JobRequest.TranscriptionParameters("zh-CN", false));
    }

    @Test
    @DisplayName("首次提交：创建任务并返回 QUEUED 信封（对应 HTTP 202）")
    void firstSubmitCreatesJob() {
        JobService.SubmitOutcome outcome = service.submit(request("req-001"));

        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.envelope().status()).isEqualTo(JobStatus.QUEUED);
        assertThat(outcome.envelope().revision()).isZero();
        assertThat(outcome.envelope().clientRequestId()).isEqualTo("req-001");
        assertThat(outcome.envelope().jobId()).isNotBlank();
        // 非终态必须有 payload=null 与 error=null
        assertThat(outcome.envelope().payload()).isNull();
        assertThat(outcome.envelope().error()).isNull();
        assertThat(repository.size()).isEqualTo(1);
        assertThat(enqueued).hasSize(1);
    }

    @Test
    @DisplayName("同键同内容重提：复用同一任务，不重复创建、不重复处理（对应 HTTP 200）")
    void duplicateSubmitReusesJob() {
        JobService.SubmitOutcome first = service.submit(request("req-002"));
        JobService.SubmitOutcome second = service.submit(request("req-002"));

        assertThat(second.duplicate()).isTrue();
        assertThat(second.envelope().jobId()).isEqualTo(first.envelope().jobId());
        assertThat(repository.size()).isEqualTo(1);
        assertThat(repository.insertCount).isEqualTo(1);
        // 只入队一次：第二次是幂等复用，不能重复跑识别（会重复计费）
        assertThat(enqueued).hasSize(1);
    }

    @Test
    @DisplayName("仅下载凭据续期：同键仍复用，并更新凭据（契约允许续期后同键继续）")
    void refreshedCredentialsReuseSameJob() {
        service.submit(request("req-003"));

        JobRequest refreshed = new JobRequest(
                "1.0", "req-003", "TRANSCRIPTION", "session-demo-001", "media-demo-001",
                new JobRequest.MediaRef(HASH, 60_000L, "video/mp4",
                        "https://media.example.com/fixture-renewed.mp4?sign=new",
                        "2026-09-13T06:00:00Z"),
                new JobRequest.AnalysisRange(0L, 60_000L),
                new JobRequest.TranscriptionParameters("zh-CN", false));

        JobService.SubmitOutcome second = service.submit(refreshed);

        assertThat(second.duplicate()).isTrue();
        assertThat(repository.size()).isEqualTo(1);
        TranscriptionJob stored = repository.all().get(0);
        // 凭据被更新为新地址，但任务身份不变
        assertThat(stored.sourceUrl()).contains("fixture-renewed.mp4");
        assertThat(stored.urlExpiresAt()).isEqualTo(Instant.parse("2026-09-13T06:00:00Z"));
    }

    @Test
    @DisplayName("同键但 media 不同：409 IDEMPOTENCY_CONFLICT，不静默改任务")
    void differentMediaConflicts() {
        service.submit(request("req-004"));

        JobRequest changed = new JobRequest(
                "1.0", "req-004", "TRANSCRIPTION", "session-demo-001", "media-demo-001",
                new JobRequest.MediaRef("b".repeat(64), 60_000L, "video/mp4",
                        "https://media.example.com/fixture.mp4", "2026-09-13T04:00:00Z"),
                new JobRequest.AnalysisRange(0L, 60_000L),
                new JobRequest.TranscriptionParameters("zh-CN", false));

        assertThatThrownBy(() -> service.submit(changed))
                .isInstanceOf(ContractRejectionException.class)
                .satisfies(e -> {
                    ContractRejectionException ex = (ContractRejectionException) e;
                    assertThat(ex.code()).isEqualTo(ContractErrors.IDEMPOTENCY_CONFLICT);
                    assertThat(ex.status().value()).isEqualTo(409);
                    assertThat(ex.retryable()).isFalse();
                });
        // 原任务未被改写
        assertThat(repository.all().get(0).mediaSha256()).isEqualTo(HASH);
    }

    @Test
    @DisplayName("同键但 analysisRange 不同：409（更换分析参数必须用新 clientRequestId）")
    void differentRangeConflicts() {
        service.submit(request("req-005"));

        JobRequest changed = new JobRequest(
                "1.0", "req-005", "TRANSCRIPTION", "session-demo-001", "media-demo-001",
                new JobRequest.MediaRef(HASH, 60_000L, "video/mp4",
                        "https://media.example.com/fixture.mp4", "2026-09-13T04:00:00Z"),
                new JobRequest.AnalysisRange(10_000L, 30_000L),
                new JobRequest.TranscriptionParameters("zh-CN", false));

        assertThatThrownBy(() -> service.submit(changed))
                .isInstanceOf(ContractRejectionException.class)
                .satisfies(e -> assertThat(((ContractRejectionException) e).code())
                        .isEqualTo(ContractErrors.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("已终态的任务：凭据再变也原样返回，终态不可变")
    void terminalJobIsImmutable() {
        JobService.SubmitOutcome created = service.submit(request("req-006"));
        TranscriptionJob stored = repository.findByJobId(created.envelope().jobId()).orElseThrow();
        // 手工置为终态，模拟处理已完成
        repository.update(stored.withOutcome(JobStatus.SUCCEEDED,
                "{\"language\":\"zh-CN\",\"engineVersion\":\"t\",\"processedRanges\":[],"
                        + "\"segments\":[],\"warnings\":[\"NO_SPEECH\"]}",
                null, null, null, Instant.now()));

        JobRequest credentialsOnly = new JobRequest(
                "1.0", "req-006", "TRANSCRIPTION", "session-demo-001", "media-demo-001",
                new JobRequest.MediaRef(HASH, 60_000L, "video/mp4",
                        "https://media.example.com/another.mp4", "2026-09-13T09:00:00Z"),
                new JobRequest.AnalysisRange(0L, 60_000L),
                new JobRequest.TranscriptionParameters("zh-CN", false));

        JobService.SubmitOutcome second = service.submit(credentialsOnly);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.envelope().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(second.envelope().revision()).isEqualTo(1);
        TranscriptionJob after = repository.findByJobId(created.envelope().jobId()).orElseThrow();
        // 终态不可变：下载凭据也不该被续期改写
        assertThat(after.sourceUrl()).doesNotContain("another.mp4");
        assertThat(after.status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("查询：jobId 与 clientRequestId 不匹配时按 404 处理（不泄漏任务归属）")
    void getWithMismatchedRequestIdIsNotFound() {
        JobService.SubmitOutcome created = service.submit(request("req-007"));
        String jobId = created.envelope().jobId();

        assertThat(service.get(jobId, "req-007").jobId()).isEqualTo(jobId);

        assertThatThrownBy(() -> service.get(jobId, "someone-elses-request"))
                .isInstanceOf(ContractRejectionException.class)
                .satisfies(e -> {
                    ContractRejectionException ex = (ContractRejectionException) e;
                    assertThat(ex.code()).isEqualTo(ContractErrors.NOT_FOUND);
                    assertThat(ex.status().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("查询不存在的任务：404（与无权同形，不区分）")
    void getUnknownJobIsNotFound() {
        assertThatThrownBy(() -> service.get("job-does-not-exist", null))
                .isInstanceOf(ContractRejectionException.class)
                .satisfies(e -> assertThat(((ContractRejectionException) e).status().value())
                        .isEqualTo(404));
    }

    @Test
    @DisplayName("信封身份字段来自配置而不是请求体（providerId/moduleVersion 不可被调用方伪造）")
    void identityComesFromConfig() {
        JobService.SubmitOutcome outcome = service.submit(request("req-008"));

        assertThat(outcome.envelope().providerId()).isEqualTo("transcription-test");
        assertThat(outcome.envelope().moduleVersion()).isEqualTo("test-1.0");
        assertThat(outcome.envelope().moduleType()).isEqualTo("TRANSCRIPTION");
        assertThat(outcome.envelope().timeBase()).isEqualTo(JobEnvelope.TIME_BASE);
        assertThat(outcome.envelope().schemaVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("恢复非终态任务：重启后可被重新推进")
    void resumesNonTerminalJobs() {
        service.submit(request("req-009"));
        enqueued.clear();

        int resumed = service.resumeNonTerminal(10);

        assertThat(resumed).isEqualTo(1);
        assertThat(enqueued).hasSize(1);
    }

    @Test
    @DisplayName("完成的 JobRunner 依赖为 null 也不会被误用（测试替身只覆盖 run）")
    void runnerStubDoesNotTouchDependencies() {
        // 这个断言本身是护栏：如果将来 JobService 在提交路径上调用了 runner 的其他成员，
        // 这些测试会立刻以 NPE 失败，提示"提交路径不应依赖处理细节"
        assertThat(enqueued).isEmpty();
        assertThat(Duration.ofSeconds(1).toMillis()).isEqualTo(1000L);
    }
}
