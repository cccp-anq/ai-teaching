package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.config.ContractProperties;
import com.aijiaoxue.videoreact.job.domain.TranscriptionJob;
import com.aijiaoxue.videoreact.job.repository.TranscriptionJobRepository;
import com.aijiaoxue.videoreact.job.web.ContractRejectionException;
import com.aijiaoxue.videoreact.job.wire.JobEnvelope;
import com.aijiaoxue.videoreact.job.wire.JobRequest;
import com.aijiaoxue.videoreact.job.wire.JobStatus;
import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务受理与查询：契约 §2 的路由语义与 §3 的幂等规则都在这里。
 *
 * <p><b>幂等语义</b>（契约 §3）：
 * <ul>
 *   <li>首次提交 → HTTP <b>202</b>，创建任务；</li>
 *   <li>同 {@code clientRequestId} 且业务指纹相同 → HTTP <b>200</b>，返回同一任务；</li>
 *   <li>同 {@code clientRequestId} 但指纹不同 → HTTP <b>409 IDEMPOTENCY_CONFLICT</b>，
 *       <b>不静默改任务</b>；</li>
 *   <li>同键重提但仅下载凭据变化（主项目续期 URL）且任务尚未终态 →
 *       更新凭据并<b>继续处理</b>（不是新建任务、也不是冲突）；</li>
 *   <li>任务已终态 → 终态不可变，无论凭据怎么变都原样返回。</li>
 * </ul>
 *
 * <p>执行放在独立线程池：契约要求"提交超时后用同 clientRequestId 重试"，
 * 因此提交接口必须快速返回 202，不能阻塞在下载与识别上。
 */
@Service
public class JobService {
    public boolean supportsSpeakerLabels(){return validator.supportsSpeakerLabels();}

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final TranscriptionJobRepository repository;
    private final JobRequestValidator validator;
    private final RequestFingerprint fingerprint;
    private final JobEnvelopeAssembler assembler;
    private final JobRunner runner;
    private final ContractProperties contract;
    private final MediaProperties mediaProperties;

    /** 提交接口只做受理与落库，真正的处理在单线程池里顺序执行（首版不做并发调度）。 */
    private final java.util.Set<String> scheduled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "transcription-worker");
        thread.setDaemon(true);
        return thread;
    });

    public JobService(
            TranscriptionJobRepository repository,
            JobRequestValidator validator,
            RequestFingerprint fingerprint,
            JobEnvelopeAssembler assembler,
            JobRunner runner,
            ContractProperties contract,
            MediaProperties mediaProperties) {
        this.repository = repository;
        this.validator = validator;
        this.fingerprint = fingerprint;
        this.assembler = assembler;
        this.runner = runner;
        this.contract = contract;
        this.mediaProperties = mediaProperties;
    }

    /**
     * 提交结果。
     *
     * @param envelope  信封
     * @param duplicate true 表示同键同内容复用（HTTP 200），false 表示新建（HTTP 202）
     */
    public record SubmitOutcome(JobEnvelope envelope, boolean duplicate) {
    }

    /**
     * 受理提交。
     *
     * @param request 请求
     * @return 提交结果
     */
    @Transactional
    public SubmitOutcome submit(JobRequest request) {
        validator.validate(request, maxMediaDurationMs());

        String requestHash = fingerprint.of(request);
        Optional<TranscriptionJob> existing =
                repository.findByClientRequestId(request.clientRequestId());

        if (existing.isPresent()) {
            return handleExisting(existing.get(), request, requestHash);
        }

        Instant now = Instant.now();
        TranscriptionJob job = new TranscriptionJob(
                null,
                "job-" + UUID.randomUUID(),
                request.clientRequestId(),
                requestHash,
                JobEnvelope.SCHEMA_VERSION,
                contract.providerId(),
                contract.moduleVersion(),
                request.classSessionId(),
                request.mediaId(),
                request.media().sha256(),
                request.media().durationMs(),
                request.media().mimeType(),
                request.media().sourceUrl(),
                Instant.parse(request.media().urlExpiresAt()),
                request.analysisRange().startMs(),
                request.analysisRange().endMs(),
                request.parameters().language(),
                JobStatus.QUEUED,
                0,
                null, null, null, null, null, null, 0,
                now, now);

        TranscriptionJob saved = repository.insert(job);
        repository.setSpeakerLabels(saved.jobId(), Boolean.TRUE.equals(request.parameters().enableSpeakerLabels()));
        log.info("已受理转写任务: jobId={}, {}", saved.jobId(), String.join(", ", request.logFields()));

        // 事务提交后再异步执行；提交接口不等结果，满足"快速返回、可安全重试"
        enqueueAfterCommit(saved.jobId());

        return new SubmitOutcome(assembler.toEnvelope(saved), false);
    }

    /**
     * 处理同 clientRequestId 的重复提交。
     *
     * <p>分成四种情况，顺序不能换：
     * <ol>
     *   <li>指纹不同 → 409（无论是否终态，契约明确"不静默改任务"）；</li>
     *   <li>已终态 → 原样返回，终态不可变；</li>
     *   <li>未终态且凭据未变 → 原样返回（重复提交，不重复处理）；</li>
     *   <li>未终态且凭据更新 → 续用同一任务，更新下载凭据并重新入队。</li>
     * </ol>
     */
    private SubmitOutcome handleExisting(TranscriptionJob job, JobRequest request, String requestHash) {
        if (!job.requestHash().equals(requestHash)) {
            throw ContractRejectionException.idempotencyConflict(
                    "clientRequestId=" + request.clientRequestId()
                            + " 已存在但请求内容不同（身份/hash/duration/range/parameters 至少一项不同）。"
                            + "更换分析参数或媒体请使用新的 clientRequestId。");
        }

        if (job.isTerminal()) {
            log.info("任务 {} 已是终态 {}，按幂等返回原结果", job.jobId(), job.status());
            return new SubmitOutcome(assembler.toEnvelope(job), true);
        }

        Instant newExpiry = Instant.parse(request.media().urlExpiresAt());
        boolean credentialsChanged =
                !java.util.Objects.equals(job.sourceUrl(), request.media().sourceUrl())
                        || !java.util.Objects.equals(job.urlExpiresAt(), newExpiry);

        if (credentialsChanged) {
            // 契约：同键仅更新下载凭据且尚未终态可续处理
            TranscriptionJob refreshed = job.withRefreshedDownload(
                    request.media().sourceUrl(), newExpiry);
            repository.update(refreshed);
            log.info("任务 {} 下载凭据已续期，继续处理", refreshed.jobId());
            // 仅在尚未开始处理时重新入队，避免与正在跑的实例并发处理同一任务
            if (job.status() == JobStatus.QUEUED) {
                enqueueAfterCommit(refreshed.jobId());
            }
            return new SubmitOutcome(assembler.toEnvelope(refreshed), true);
        }

        log.info("任务 {} 重复提交且内容一致，返回既有任务（不重复处理）", job.jobId());
        return new SubmitOutcome(assembler.toEnvelope(job), true);
    }

    /**
     * 查询任务。
     *
     * <p>不存在返回 404，且<b>不区分"不存在"与"无权"</b>（契约 §5：不泄漏任务归属）。
     *
     * @param jobId        任务 ID
     * @param clientRequestId 请求方可选带上的提交键；带则必须匹配，否则视为无权
     * @return 信封
     */
    public JobEnvelope get(String jobId, String clientRequestId) {
        TranscriptionJob job = repository.findByJobId(jobId)
                .orElseThrow(() -> ContractRejectionException.notFound("任务不存在"));
        if (clientRequestId != null && !clientRequestId.isBlank()
                && !clientRequestId.equals(job.clientRequestId())) {
            // 用 404 而不是 403：不确认这个 jobId 是否存在
            throw ContractRejectionException.notFound("任务不存在");
        }
        return assembler.toEnvelope(job);
    }

    /**
     * 推进所有非终态任务，供后台补偿轮询使用。
     *
     * <p>正常情况下任务在提交线程里就跑完了；这里兜的是进程重启后残留的 QUEUED/RUNNING。
     *
     * @param limit 单批上限
     * @return 入队数量
     */
    public int resumeNonTerminal(int limit) {
        var pending = repository.findNonTerminal(limit);
        for (TranscriptionJob job : pending) {
            log.info("恢复未完成任务: jobId={}, status={}", job.jobId(), job.status());
            enqueue(job.jobId());
        }
        return pending.size();
    }

    /**
     * 把任务交给工作线程。
     *
     * <p>抽成 {@code protected} 是为了让测试能<b>确定性地</b>观察"入队了几次"：
     * 真实实现用共享线程池异步执行，而异步会让"是否重复处理"的断言变得不稳定，
     * 不稳定的测试最终会被删掉，等于没有保护。
     *
     * @param jobId 任务 ID
     */
    protected void enqueue(String jobId) {
        if (!scheduled.add(jobId)) return;
            workers.submit(() -> { try { runner.run(jobId); } finally { scheduled.remove(jobId); } });
    }

    /**
     * 在事务提交之后再入队。
     *
     * <p><b>为什么不能直接 enqueue</b>：{@link #submit} 标注了 {@code @Transactional}，
     * 而 worker 跑在另一个线程上——它可能在事务提交<b>之前</b>就去查库，
     * 结果查不到刚插入的任务、静默返回，任务就永远停在 QUEUED。
     * 这个竞态在内存仓储的单测里不会出现（没有事务），却会在真实数据库下稳定复现，
     * 所以必须显式等到 {@code afterCommit}。
     *
     * <p>没有事务同步（例如单元测试直接调用）时立即入队。
     *
     * @param jobId 任务 ID
     */
    protected void enqueueAfterCommit(String jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(jobId);
                }
            });
            return;
        }
        enqueue(jobId);
    }

    /**
     * 本部署允许的最大媒体时长，取自媒体配置。
     *
     * <p>这是交接表里声明的"最大媒体"数值来源，也决定超限请求返回 422 而不是排到队列里才失败。
     */
    private long maxMediaDurationMs() {
        return mediaProperties.maxDurationMs();
    }

    /**
     * 关闭工作线程池。
     *
     * <p>必须真正停止：契约要求"重启可恢复"，而恢复的前提是旧实例已经停止写库。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }

    /** 供 /health 与自检展示当前身份。 */
    public String providerId() {
        return contract.providerId();
    }

    /** 供 /health 与自检展示当前版本。 */
    public String moduleVersion() {
        return contract.moduleVersion();
    }
}
