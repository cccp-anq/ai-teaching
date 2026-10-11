package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.asr.client.AsrProviderClient;
import com.aijiaoxue.videoreact.asr.client.AsrProviderException;
import com.aijiaoxue.videoreact.asr.client.AsrProviderStatus;
import com.aijiaoxue.videoreact.asr.client.AsrSubmitCommand;
import com.aijiaoxue.videoreact.asr.client.ProviderTaskStatus;
import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import com.aijiaoxue.videoreact.asr.staging.AudioStaging;
import com.aijiaoxue.videoreact.asr.staging.AudioStagingException;
import com.aijiaoxue.videoreact.asr.service.AsrRawResultDumper;
import com.aijiaoxue.videoreact.job.domain.TranscriptionJob;
import com.aijiaoxue.videoreact.job.repository.TranscriptionJobRepository;
import com.aijiaoxue.videoreact.job.web.ContractErrors;
import com.aijiaoxue.videoreact.job.wire.JobEnvelope;
import com.aijiaoxue.videoreact.job.wire.JobStatus;
import com.aijiaoxue.videoreact.media.FfmpegMediaTool;
import com.aijiaoxue.videoreact.media.MediaDownloader;
import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 任务执行器：契约要求的媒体获取 → hash 核验 → 音轨提取 → 转写 → 时间回映射 → 落库。
 *
 * <p>按契约 §3，模块必须<b>自己下载并核验 sha256</b>，因此这里不是把 sourceUrl
 * 转手交给 ASR 提供方，而是：
 * <ol>
 *   <li>受控下载媒体（拒绝内网/非 HTTPS/超限，见 {@link MediaDownloader}）；</li>
 *   <li>比对 sha256，不一致立即 FAILED {@code MEDIA_HASH_MISMATCH}（<b>不能靠重试解决</b>）；</li>
 *   <li>ffprobe 探测时长与音轨，ffmpeg 抽整条音轨为 16k 单声道 WAV
 *       （整条提取 ⇒ 音频轴与原媒体轴恒等，满足"时间必须映射回原媒体"）；</li>
 *   <li>把音频<b>内联</b>提交给 ASR（不再外泄受控 sourceUrl）；
 *       <b>若提供方只收公网 URL</b>（阿里云 filetrans 系列即如此，官方 FAQ 明确不支持 base64），
 *       则先经 {@link AudioStaging} 暂存出一个预签名地址再提交，
 *       并在任务终态后清理——见 {@link #submitToProvider}；</li>
 *   <li>轮询到终态，把片段时间映射回原媒体轴并裁剪到已处理范围内；</li>
 *   <li>写入终态；终态一旦写入不再改变。</li>
 * </ol>
 *
 * <p><b>失败分类</b>严格按契约 §5：下载凭据过期 = 可重试；
 * hash 不符 / 无法解码 = 不可重试；提供方临时故障 = 可重试。
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    /** 一次处理最多轮询提供方的次数。 */
    private static final int MAX_PROVIDER_POLLS = 120;

    @org.springframework.beans.factory.annotation.Value("${videoreact.contract.max-poll-seconds:7200}")
    private int maxPollSeconds = 7200;
    @org.springframework.beans.factory.annotation.Value("${MERGE_MODE:}") private String integratedMode = "";
    @org.springframework.beans.factory.annotation.Value("${APP_RECOGNITION_ENABLED:false}") private boolean recognitionEnabled;
    @org.springframework.beans.factory.annotation.Value("${videoreact.asr.inline-max-bytes:67108864}") private long inlineMaxBytes = 64L * 1024 * 1024;
    private final TranscriptionJobRepository repository;
    private final MediaDownloader downloader;
    private final FfmpegMediaTool mediaTool;
    private final AsrProviderClient providerClient;
    private final JobEnvelopeAssembler assembler;
    private final TranscriptTimeMapper timeMapper;
    private final MediaProperties mediaProperties;
    private final AsrProperties asrProperties;
    private final ResultSizeGuard resultSizeGuard;
    private final AudioStaging audioStaging;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AsrRawResultDumper rawResultDumper;

    public JobRunner(
            TranscriptionJobRepository repository,
            MediaDownloader downloader,
            FfmpegMediaTool mediaTool,
            AsrProviderClient providerClient,
            JobEnvelopeAssembler assembler,
            TranscriptTimeMapper timeMapper,
            MediaProperties mediaProperties,
            AsrProperties asrProperties,
            ResultSizeGuard resultSizeGuard,
            AudioStaging audioStaging) {
        this.repository = repository;
        this.downloader = downloader;
        this.mediaTool = mediaTool;
        this.providerClient = providerClient;
        this.assembler = assembler;
        this.timeMapper = timeMapper;
        this.mediaProperties = mediaProperties;
        this.asrProperties = asrProperties;
        this.resultSizeGuard = resultSizeGuard;
        this.audioStaging = audioStaging;
    }

    /**
     * 同步执行一个任务（由 {@code JobService} 在提交后异步入队调用）。
     *
     * <p>不抛异常：所有失败都转成 FAILED 终态写库。
     * 因为契约规定「GET 失败不是远端任务 FAILED」，反过来本模块的失败
     * 必须体现为任务 FAILED，而不是让调用方从 HTTP 错误里猜。
     *
     * @param jobId 任务 ID
     */
    public void run(String jobId) {
        Optional<TranscriptionJob> found = repository.findByJobId(jobId);
        if (found.isEmpty()) {
            log.warn("执行时任务不存在: jobId={}", jobId);
            return;
        }
        TranscriptionJob job = found.get();
        if (job.isTerminal()) {
            log.info("任务 {} 已是终态 {}，跳过执行（终态不可变）", jobId, job.status());
            return;
        }
        if ("1".equals(integratedMode) && !recognitionEnabled) {
            fail(job, ContractErrors.PROCESSING_FAILED,
                    "识别功能未启用，任务不能进入静默等待", false);
            return;
        }
        if (asrProperties.poll() == null || !asrProperties.poll().enabled()) {
            fail(job, ContractErrors.PROCESSING_FAILED,
                    "ASR_POLL_ENABLED=false：异步识别无法推进", false);
            return;
        }

        Path workDir = Path.of(mediaProperties.workDir()).resolve(jobId);
        Path mediaFile = null;
        Path audioFile = null;
        try {
            if (job.status() == JobStatus.RUNNING && job.providerTaskId() == null) {
                fail(job, ContractErrors.PROCESSING_FAILED, "ASR_SUBMISSION_UNKNOWN: 上次提交状态不明，不自动重复付费提交", false);
                cleanupStagedAudio(jobId, null);
                return;
            }
            boolean resume = job.providerTaskId() != null;
            if (resume && job.engineVersion() != null && !job.engineVersion().equals(providerClient.modelIdentifier())) { fail(job, ContractErrors.PROCESSING_FAILED, "供应方或模型已变化，拒绝用新配置续查旧任务", false); return; }
            List<String> preWarnings = new ArrayList<>();
            String providerTaskId = job.providerTaskId();
            AudioStaging.StagedAudio staged = null;
            if (!resume) {
            Instant now = Instant.now();
            TranscriptionJob running = job.withRunning(null, null, now);
            repository.update(running);
            job = running;

            // ---------- 1. 下载 ----------
            MediaDownloader.DownloadedMedia media = downloader.download(job.sourceUrl(), workDir);
            mediaFile = media.path();

            // ---------- 2. hash 核验 ----------
            if (!media.sha256().equalsIgnoreCase(job.mediaSha256())) {
                fail(job, ContractErrors.MEDIA_HASH_MISMATCH,
                        "媒体内容与登记的 sha256 不一致：期望 " + abbreviate(job.mediaSha256())
                                + "，实际 " + abbreviate(media.sha256()) + "。这属于数据问题，重试无效。",
                        false);
                return;
            }

            // ---------- 3. 探测与抽音轨 ----------
            FfmpegMediaTool.ProbeResult probe = mediaTool.probe(mediaFile);
            long tolerance = mediaProperties.durationToleranceMs();
            if (Math.abs(probe.durationMs() - job.durationMs()) > tolerance) {
                // 契约：真实容差在登记核验时处理，接收时不能静默裁切。
                // 因此这里既不裁切也不拒绝，只如实告知，信封 durationMs 仍与请求一致。
                preWarnings.add("DURATION_MISMATCH:registered=%d,actual=%d".formatted(
                        job.durationMs(), probe.durationMs()));
                log.warn("任务 {} 的媒体时长与登记值不一致: 登记={}ms, 实际={}ms（仅告警，不做裁切）",
                        job.jobId(), job.durationMs(), probe.durationMs());
            }
            if (!probe.hasAudio()) {
                fail(job, ContractErrors.UNSUPPORTED_MEDIA,
                        "媒体中没有音轨（容器格式 " + probe.formatName() + "）", false);
                return;
            }

            audioFile = mediaTool.extractAudio(mediaFile, workDir);
            long audioBytes = Files.size(audioFile);

            // ---------- 4. 提交给提供方 ----------

            try {
                Submission submission = submitToProvider(
                        jobId, audioFile, audioBytes, languageHintsFor(job.language()));
                providerTaskId = submission.providerTaskId();
                staged = submission.staged();
            } catch (AsrProviderException e) {
                fail(job, ContractErrors.PROCESSING_FAILED,
                        "提交识别任务失败: " + e.getMessage(), e.retryable());
                return;
            } catch (AudioStagingException e) {
                // 暂存失败与识别失败分开报：两者补救动作完全不同
                // （一个是对象存储/权限问题，一个是引擎/额度问题）
                fail(job, ContractErrors.PROCESSING_FAILED,
                        "音频暂存失败（" + e.code() + "）: " + e.getMessage(), e.retryable());
                return;
            }
            job = repository.findByJobId(jobId).orElse(job);
            job = job.withRunning(providerTaskId, providerClient.modelIdentifier(), Instant.now());
            repository.update(job);
            log.info("任务 {} 已提交: providerTaskId={}, 音频 {} 字节", jobId, providerTaskId, audioBytes);

            } // Known provider IDs resume polling without download or resubmission.
            // ---------- 5. 轮询到终态 ----------
            // 暂存地址必须在轮询结束后才能删：引擎可能排队期间才来拉音频。
            AsrProviderStatus status;
            boolean interrupted = false;
            try {
                status = pollUntilTerminal(providerTaskId, job.createdAt().plusSeconds(Math.min(asrProperties.poll().timeout().toSeconds(),maxPollSeconds)));
            } catch (RunInterrupted e) {
                interrupted = true;
                throw e;
            } finally {
                if (!interrupted) {
                    cleanupStagedAudio(jobId, staged);
                }
            }
            if (status == null) {
                fail(job, ContractErrors.PROCESSING_FAILED,
                        "等待识别结果超过持久任务期限", true);
                return;
            }
            if (status.status() == ProviderTaskStatus.FAILED) {
                fail(job, ContractErrors.PROCESSING_FAILED,
                        "识别引擎返回失败: " + nullToText(status.errorMessage()), false);
                return;
            }
            if (status.status() != ProviderTaskStatus.SUCCESS) {
                fail(job, ContractErrors.PROCESSING_FAILED,
                        "识别任务未进入成功终态: " + status.statusText(), true);
                return;
            }
            if (rawResultDumper != null) {
                rawResultDumper.dump(job.id(), status.sentences());
            }

            // ---------- 6. 时间映射与载荷组装 ----------
            // 整条音轨提取 ⇒ 偏移为 0。emittedRanges 就是请求的处理范围：
            // 本实现不裁剪，因此"已处理"等于"请求的范围"。
            List<JobEnvelope.Range> processedRanges = List.of(
                    new JobEnvelope.Range(job.analysisStartMs(), job.analysisEndMs()));

            TranscriptTimeMapper.Mapped mapped = timeMapper.map(
                    status.sentences(),
                    TranscriptTimeMapper.offsetFor(0L),
                    job.analysisStartMs(),
                    job.analysisEndMs(),
                    processedRanges, repository.speakerLabels(jobId));

            List<String> warnings = new ArrayList<>(preWarnings);
            warnings.addAll(mapped.warnings());
            if (mapped.segments().isEmpty()) {
                // 契约：合法静音可成功且 segments=[]，但必须标 NO_SPEECH，
                // 以区别于"解码失败被包装成静音"
                warnings.add("NO_SPEECH");
            }

            JobEnvelope.TranscriptionPayload payload = new JobEnvelope.TranscriptionPayload(
                    normalizeLanguage(job.language()),
                    providerClient.modelIdentifier(),
                    processedRanges,
                    mapped.segments(),
                    warnings);

            // 结果体积上限：超限时明确失败而不是截断。
            // 截断会让主项目拿到"看起来完整、实际缺片段"的结果，覆盖统计与证据引用都会错，
            // 而它无法从响应里察觉。宁可失败让主项目改用更小的 analysisRange。
            String payloadJson = assembler.writePayload(payload);
            String sizeProblem = resultSizeGuard.check(mapped.segments().size(), payloadJson);
            if (sizeProblem != null) {
                fail(job, ContractErrors.PROCESSING_FAILED, sizeProblem, false);
                return;
            }

            Instant finishedAt = Instant.now();
            TranscriptionJob succeeded = job.withOutcome(
                    JobStatus.SUCCEEDED, payloadJson, null, null, null, finishedAt);
            repository.update(succeeded);
            log.info("任务 {} 成功: 片段 {} 条, warnings={}", jobId, mapped.segments().size(), warnings);

        } catch (RunInterrupted e) {
            // Keep RUNNING/providerTaskId for restart; interruption is not a provider failure.
            return;
        } catch (MediaDownloader.MediaDownloadException e) {
            // 下载阶段可能抛出 HTTP 级错误码，这里收敛成契约允许的任务级码
            fail(reload(jobId, job), TaskErrorCodes.normalize(e.code()), e.getMessage(), e.retryable());
        } catch (FfmpegMediaTool.MediaToolException e) {
            fail(reload(jobId, job), TaskErrorCodes.normalize(e.code()), e.getMessage(), e.retryable());
        } catch (AsrProviderException e) {
            fail(reload(jobId, job), ContractErrors.PROCESSING_FAILED, e.getMessage(), e.retryable());
        } catch (IOException e) {
            fail(reload(jobId, job), ContractErrors.PROCESSING_FAILED,
                    "读取音频文件失败: " + e.getClass().getSimpleName(), true);
        } catch (RuntimeException e) {
            log.error("任务 {} 处理时发生未预期异常", jobId, e);
            fail(reload(jobId, job), ContractErrors.PROCESSING_FAILED,
                    "处理失败: " + e.getClass().getSimpleName(), true);
        } finally {
            cleanup(workDir, mediaFile, audioFile);
        }
    }

    /**
     * 把抽好的音轨提交给提供方。
     *
     * <p><b>两条路，由提供方能力决定</b>（见 {@link AsrProviderClient#acceptsInlineAudio()}）：
     * <ul>
     *   <li><b>内联</b>：直接把字节 base64 提交。字节不出模块，契约的 sha256 核验语义完整；</li>
     *   <li><b>URL-only</b>：先暂存音轨换一个预签名地址，提交后再轮询，最后清理。
     *       这里提交的是<b>抽好的音轨</b>而不是原始录像——体积小一个数量级
     *       （45 分钟 16k 单声道 WAV 约 86MB，同长度 1080p 录像 1.0–1.7GB），
     *       上传时间与对象存储成本都由这个选择决定。</li>
     * </ul>
     *
     * <p><b>暂存句柄的生命周期交给调用方</b>：引擎可能在排队期间才去拉取音频，
     * 提交完就删会让任务以一个含糊的下载失败告终。所以这里把句柄放在
     * {@link Submission#staged} 里返回，由 {@link #run} 在轮询结束后统一关闭。
     *
     * @param audioFile  抽好的音轨
     * @param audioBytes 音轨字节数（仅用于日志）
     * @param languageHints 语种提示
     * @return 提交结果：任务 ID + 可能存在的暂存句柄
     * @throws AsrProviderException  提交失败
     * @throws AudioStagingException 暂存失败
     */
    private Submission submitToProvider(String jobId, Path audioFile, long audioBytes, List<String> languageHints) {
        long providerLimit = providerClient.maxInputUrlBytes();
        if (audioBytes > providerLimit) {
            throw new AsrProviderException("LOCAL", "AudioTooLarge",
                    "抽取音轨超过识别提供方体积上限: " + audioBytes + " > " + providerLimit, false);
        }
        if (providerClient.acceptsInlineAudio()) {
            if (audioBytes > inlineMaxBytes) {
                throw new AsrProviderException("LOCAL", "InlineAudioTooLarge",
                        "内联音频超过内存安全上限: " + audioBytes + " > " + inlineMaxBytes, false);
            }
            AsrSubmitCommand command = AsrSubmitCommand.ofBytes(
                    readAllBytes(audioFile),
                    audioFile.getFileName().toString(),
                    providerClient.modelIdentifier(),
                    languageHints);
            return new Submission(providerClient.submit(command), null);
        }

        AudioStaging.StagedAudio staged = audioStaging.stage(
                audioFile,
                "audio/wav",
                audioFile.getFileName().toString(),
                stagingLifetime());
        repository.setStagingObjectKey(jobId, staged.objectKey());
        try {
            log.info("音轨已暂存: {} 字节，地址有效期至 {}", audioBytes, staged.expiresAt());
            AsrSubmitCommand command = AsrSubmitCommand.ofUrl(
                    staged.url(), providerClient.modelIdentifier(), languageHints);
            return new Submission(providerClient.submit(command), staged);
        } catch (RuntimeException e) {
            // 提交失败就没有后续轮询会用到这个地址，当场清理，不留给调用方
            if (audioStaging.delete(staged.objectKey())) {
                repository.clearStagingObjectKey(jobId, staged.objectKey());
            }
            throw e;
        }
    }

    /**
     * 一次提交的结果。
     *
     * @param providerTaskId 提供方任务 ID
     * @param staged         暂存句柄；内联提交时为 null，需由调用方在轮询结束后关闭
     */
    private record Submission(String providerTaskId, AudioStaging.StagedAudio staged) {
    }

    /**
     * 暂存地址有效期。
     *
     * <p>取轮询上限再加一半余量：一个地址只要活过"排队 + 识别 + 最后一次查询"就够了，
     * 设得过长（例如 24 小时）等于让音频在对象存储里多躺很久，既增加暴露面也增加存储成本。
     *
     * @return 有效期
     */
    private Duration stagingLifetime() {
        Duration poll = asrProperties.poll() == null ? null : asrProperties.poll().timeout();
        Duration base = poll == null || poll.isZero() || poll.isNegative()
                ? Duration.ofHours(1)
                : poll;
        return base.plus(base.dividedBy(2));
    }

    /**
     * 语种提示。
     *
     * <p>请求里的 {@code language} 由主项目给（契约 §2），这里是把它翻译成引擎要的短码。
     * <b>不认识就返回空</b>而不是原样透传：语种码不在引擎支持列表里时，
     * 有的引擎会直接拒掉整个任务。空表示"让引擎自己判断"，
     * 比传一个非法值安全。
     *
     * @param language 请求中的语种
     * @return 提示列表；无法映射时为空列表
     */
    static List<String> languageHintsFor(String language) {
        if (language == null || language.isBlank()) {
            return List.of();
        }
        String normalized = language.trim().toLowerCase();
        int separator = -1;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '-' || c == '_') {
                separator = i;
                break;
            }
        }
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        if ("auto".equals(normalized) || "und".equals(normalized) || normalized.length() != 2) {
            return List.of();
        }
        return List.of(normalized);
    }

    /** 读取音轨字节；把 IOException 收敛成 AsrProviderException 以复用同一套失败处理。 */
    private static byte[] readAllBytes(Path audioFile) {
        try {
            return Files.readAllBytes(audioFile);
        } catch (IOException e) {
            throw new AsrProviderException("LOCAL", "AudioReadFailed",
                    "读取音轨文件失败: " + e.getMessage(), true, e);
        }
    }

    /**
     * 轮询提供方直到终态。
     *
     * @param providerTaskId 提供方任务号
     * @return 终态状态；超时返回 null
     */
    private AsrProviderStatus pollUntilTerminal(String providerTaskId, Instant deadline) {
        Duration interval = asrProperties.poll().interval();
        int transientFailures = 0;
        while (Instant.now().isBefore(deadline)) {
            try {
                AsrProviderStatus status = providerClient.describe(providerTaskId);
                transientFailures = 0;
                if (status.status().isTerminal()) {
                    return status;
                }
            } catch (AsrProviderException e) {
                if (!e.retryable()) {
                    throw e;
                }
                transientFailures++;
                log.warn("轮询任务 {} 遇到第 {} 次瞬时错误，将在当前持久期限内继续: {}",
                        providerTaskId, transientFailures, e.getClass().getSimpleName());
            }
            try {
                Thread.sleep(Math.max(500, interval.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RunInterrupted();
            }
        }
        return null;
    }

    private void cleanupStagedAudio(String jobId, AudioStaging.StagedAudio currentHandle) {
        String key = repository.stagingObjectKey(jobId)
                .orElse(currentHandle == null ? null : currentHandle.objectKey());
        if (key == null || key.isBlank()) {
            return;
        }
        if (audioStaging.delete(key)) {
            repository.clearStagingObjectKey(jobId, key);
        }
    }

    private static class RunInterrupted extends RuntimeException {}

    private TranscriptionJob reload(String jobId, TranscriptionJob fallback) {
        return repository.findByJobId(jobId).orElse(fallback);
    }

    /**
     * 写入失败终态。
     *
     * <p>若任务已是终态则不改写：契约要求终态不可变，
     * 迟到的失败不能把已经成功的结果覆盖掉。
     */
    private void fail(TranscriptionJob job, String code, String message, boolean retryable) {
        if (job == null) {
            return;
        }
        if (job.isTerminal()) {
            log.warn("任务 {} 已是终态 {}，忽略迟到的失败({})", job.jobId(), job.status(), code);
            return;
        }
        String safeMessage = message == null ? "处理失败" : truncate(message, 900);
        TranscriptionJob failed = job.withOutcome(
                JobStatus.FAILED, null, code, safeMessage, retryable, Instant.now());
        repository.update(failed);
        log.warn("任务 {} 失败: code={}, retryable={}, message={}",
                job.jobId(), code, retryable, safeMessage);
    }

    private void cleanup(Path workDir, Path mediaFile, Path audioFile) {
        if (mediaProperties.keepMediaAndAudio()) {
            log.info("按配置保留下载媒体与音频: {}", workDir.toAbsolutePath());
            return;
        }
        if (audioFile != null) {
            MediaDownloader.deleteQuietly(audioFile);
        }
        if (mediaFile != null) {
            MediaDownloader.deleteQuietly(mediaFile);
        }
        try {
            if (Files.exists(workDir)) {
                try (var entries = Files.list(workDir)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(workDir);
                    }
                }
            }
        } catch (IOException ignored) {
            // 清理失败不影响业务结果
        }
    }

    /** 契约要求 language 非空；未知用 und。 */
    private static String normalizeLanguage(String requested) {
        if (requested == null || requested.isBlank() || "auto".equalsIgnoreCase(requested)) {
            // 目前引擎不回报实际语种，auto 时用 und 表示未知，而不是冒充 zh-CN
            return "und";
        }
        return requested;
    }

    private static String nullToText(String value) {
        return value == null || value.isBlank() ? "(引擎未给出原因)" : value;
    }

    private static String abbreviate(String hash) {
        if (hash == null || hash.length() < 16) {
            return String.valueOf(hash);
        }
        return hash.substring(0, 8) + "…" + hash.substring(hash.length() - 8);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
