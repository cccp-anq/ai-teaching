package com.aijiaoxue.videoreact.job.web;

import com.aijiaoxue.videoreact.job.config.ContractProperties;
import com.aijiaoxue.videoreact.job.service.JobEnvelopeAssembler;
import com.aijiaoxue.videoreact.job.service.JobService;
import com.aijiaoxue.videoreact.job.wire.JobEnvelope;
import com.aijiaoxue.videoreact.job.wire.JobRequest;
import com.aijiaoxue.videoreact.media.config.MediaProperties;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 契约 §2 的最小 HTTP 接口。
 *
 * <p>刻意<b>不带</b>旧沙盒那套 {@code /api/videos/{videoId}/transcription}：
 * 契约规定前缀为 {@code baseUrl/api/v1}，三个路由对每个模块完全一致，
 * 主项目才能用同一套适配器接多个模块。
 *
 * <p>返回码语义（契约 §2）：
 * <ul>
 *   <li>{@code POST /jobs}：首次 <b>202</b>；重复 <b>200</b>；</li>
 *   <li>{@code GET /jobs/{jobId}}：<b>200</b>，不存在 404；</li>
 *   <li>{@code GET /health}：<b>200</b> 或 503，不含密钥与队列详情。</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobService;
    private final ContractProperties contract;
    private final MediaProperties mediaProperties;

    public JobController(
            JobService jobService,
            ContractProperties contract,
            MediaProperties mediaProperties) {
        this.jobService = jobService;
        this.contract = contract;
        this.mediaProperties = mediaProperties;
    }

    /**
     * 提交转写任务。
     *
     * <p>幂等键取 {@code Idempotency-Key} 头；契约要求它与 body 里的
     * {@code clientRequestId} 一致，不一致属于请求自相矛盾，直接 422。
     *
     * @param idempotencyKey 幂等键（可选，但给了就必须与 body 一致）
     * @param request        请求体
     * @return 202 新建 / 200 复用
     */
    @PostMapping(path = "/jobs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobEnvelope> submit(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JobRequest request) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()
                && request != null && request.clientRequestId() != null
                && !idempotencyKey.equals(request.clientRequestId())) {
            throw ContractRejectionException.validation(
                    "Idempotency-Key 与 clientRequestId 不一致",
                    List.of(ContractRejectionException.detail("clientRequestId",
                            "必须与 Idempotency-Key 相同")));
        }

        JobService.SubmitOutcome outcome = jobService.submit(request);
        // 首次 202、重复 200 —— 主项目据此区分"新受理"与"幂等复用"
        return ResponseEntity.status(outcome.duplicate() ? 200 : 202).body(outcome.envelope());
    }

    /**
     * 查询任务。
     *
     * @param jobId          任务 ID
     * @param clientRequestId 可选；带上则必须与任务一致，否则按 404 处理（不泄漏归属）
     * @return 信封
     */
    @GetMapping("/jobs/{jobId}")
    public JobEnvelope get(
            @PathVariable @NotBlank String jobId,
            @RequestHeader(name = "X-Client-Request-Id", required = false) String clientRequestId) {
        return jobService.get(jobId, clientRequestId);
    }

    /**
     * 健康与接口版本。
     *
     * <p>契约要求「只返回健康/接口版本，可做内部访问限制；不含密钥/队列详情」。
     * 因此这里<b>不</b>返回队列长度、任务计数、凭证状态等运维细节。
     *
     * <p>同时返回交接表里需要登记的能力边界（支持格式/语言/上限），
     * 便于主项目在联调前直接读取，而不是去翻文档。
     *
     * @return 健康信息
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("moduleType", "TRANSCRIPTION");
        body.put("providerId", contract.providerId());
        body.put("moduleVersion", contract.moduleVersion());
        body.put("schemaVersion", JobEnvelope.SCHEMA_VERSION);
        body.put("timeBase", JobEnvelope.TIME_BASE);
        body.put("checkedAt", Instant.now().toString());
        // 能力边界：交接表数值，供主项目登记
        body.put("capabilities", Map.of(
                "languages", com.aijiaoxue.videoreact.job.wire.SupportedLanguages.declared(),
                "speakerLabels", jobService.supportsSpeakerLabels(),
                "confidenceFromEngine", false,
                "maxDurationMs", String.valueOf(mediaProperties.maxDurationMs()),
                "maxBodyBytes", String.valueOf(contract.maxBodyBytes()),
                "maxSegments", String.valueOf(contract.maxSegments()),
                "ffmpegConfigured", mediaProperties.ffmpegPath() != null
                        && !mediaProperties.ffmpegPath().isBlank()));
        return ResponseEntity.ok(body);
    }
}
