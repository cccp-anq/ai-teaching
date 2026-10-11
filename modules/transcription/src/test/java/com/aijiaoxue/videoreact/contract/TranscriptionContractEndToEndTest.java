package com.aijiaoxue.videoreact.contract;

import com.aijiaoxue.videoreact.asr.client.AsrProviderClient;
import com.aijiaoxue.videoreact.asr.client.AsrProviderStatus;
import com.aijiaoxue.videoreact.asr.client.AsrSubmitCommand;
import com.aijiaoxue.videoreact.asr.client.ProviderSentence;
import com.aijiaoxue.videoreact.asr.client.ProviderTaskStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约端到端集成测试。
 *
 * <p>走真实链路：真实 HTTP 路由 → 真实 Bearer 鉴权 → 真实媒体下载与 sha256 核验 →
 * 真实 ffprobe/ffmpeg（本地媒体文件）→ 假 ASR 提供方 → 真实时间映射 → 真实 H2 落库 →
 * 真实的契约不变量校验。
 *
 * <p>只有两处被替换，且都是<b>外部依赖</b>而不是被测逻辑：
 * <ol>
 *   <li>ASR 提供方：不调用真实 TokenHub（会产生费用且结果不可复现）；</li>
 *   <li>媒体源：用一个本地 HTTP 服务器提供真实 MP4 字节，
 *       这样 sha256 核验、ffprobe 探测、ffmpeg 抽音轨都是真实执行的。</li>
 * </ol>
 *
 * <p>媒体服务器用 {@code localtest.me} 暴露：它解析到 127.0.0.1，
 * 但主机名不是环回形式，因此能通过 {@code MediaDownloader} 的公网地址校验——
 * 这不是绕过安全设计，而是让测试能真实覆盖下载与核验路径。
 *
 * <p><b>{@code @Import} 不能省</b>：嵌套的 {@code @TestConfiguration} 不会被
 * {@code @SpringBootTest} 自动拾取。少了它，容器会装配<b>真实的</b> ASR 客户端，
 * 测试就变成"调用真实云服务"——既不可复现，也会因为没配凭证而失败。
 */
@SpringBootTest(properties = {"APP_COMPATIBILITY_API=true"},
        classes = com.aijiaoxue.videoreact.VideoReactAsrApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(
        TranscriptionContractEndToEndTest.FakeProviderConfiguration.class)
class TranscriptionContractEndToEndTest {

    private static final String BEARER = "test-bearer-token-0123456789";

    private static final String MEDIA_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static HttpServer mediaServer;

    private static String mediaUrl;

    private static byte[] mediaBytes;

    @LocalServerPort
    private int port;

    @Autowired
    private AsrProviderClient providerClient;

    /** 捕获提交给"提供方"的命令，用于断言模块确实内联提交了音频而不是外泄 sourceUrl。 */
    private static final AtomicReference<AsrSubmitCommand> LAST_COMMAND = new AtomicReference<>();

    /**
     * 测试替身：假 ASR 提供方。
     */
    @TestConfiguration
    static class FakeProviderConfiguration {

        @Bean
        @Primary
        AsrProviderClient fakeAsrProviderClient() {
            return new AsrProviderClient() {
                @Override
                public String submit(AsrSubmitCommand command) {
                    LAST_COMMAND.set(command);
                    return "th_fake_task_0001";
                }

                @Override
                public AsrProviderStatus describe(String providerTaskId) {
                    // 片段时间为"音频轴"时间；因为整条音轨提取，它与原媒体轴一致
                    return new AsrProviderStatus(
                            providerTaskId,
                            ProviderTaskStatus.SUCCESS,
                            "completed",
                            "本段用于契约验证。",
                            List.of(
                                    new ProviderSentence("本段用于契约验证。", null, null,
                                            1200L, 3400L, null, List.of()),
                                    new ProviderSentence("第二段转写内容。", null, null,
                                            5000L, 6800L, null, List.of())),
                            9.3,
                            null);
                }

                @Override
                public String providerName() {
                    return "FAKE";
                }

                @Override
                public boolean credentialsReady() {
                    return true;
                }

                @Override
                public List<String> configurationHints() {
                    return List.of();
                }

                @Override
                public String modelIdentifier() {
                    return "fake-asr-1";
                }
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        startMediaServer();

        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:contract-e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        registry.add("videoreact.contract.provider-id", () -> "transcription-e2e");
        registry.add("videoreact.contract.module-version", () -> "e2e-1.0.0");
        registry.add("videoreact.contract.bearer-tokens", () -> BEARER);
        registry.add("videoreact.media.work-dir", () -> "./.work/media-e2e");
        registry.add("videoreact.media.ffmpeg-path", () -> "../../.work/tools/ffmpeg/ffmpeg.exe");
        registry.add("videoreact.media.ffprobe-path", () -> "../../.work/tools/ffmpeg/ffprobe.exe");
        // 下载走本地 HTTP，因此需要放开回环 http；生产配置默认是关闭的
        registry.add("videoreact.media.allow-loopback-http", () -> "true");
        registry.add("videoreact.media.keep-media-and-audio", () -> "false");
        registry.add("videoreact.media.max-duration-ms", () -> "7200000");
        registry.add("videoreact.asr.poll.interval", () -> "1s");
        // 端到端失败时最需要看到 worker 里发生了什么，默认 INFO 会淹没在框架日志里
        registry.add("logging.level.com.aijiaoxue.videoreact", () -> "DEBUG");
    }

    /**
     * 启动本地媒体服务器，提供一份真实的 MP4 字节。
     *
     * <p>用的是之前实测过的公开 MP4 缓存下来的内容；如果本地缓存不存在，
     * 则生成一个最小的合法 WAV（ffmpeg 同样能探测与抽音轨），
     * 保证测试在离线环境也能跑。
     */
    private static synchronized void startMediaServer() throws Exception {
        if (mediaServer != null) {
            return;
        }
        Path cached = Path.of(".work/tools/media/sintel-trailer.mp4");
        if (Files.exists(cached)) {
            mediaBytes = Files.readAllBytes(cached);
        } else {
            mediaBytes = synthesizeWav(3);
        }

        mediaServer = HttpServer.create(new InetSocketAddress(0), 0);
        mediaServer.createContext("/media", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, mediaBytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(mediaBytes);
            }
        });
        mediaServer.start();
        mediaUrl = "http://127.0.0.1:" + mediaServer.getAddress().getPort() + "/media";
    }

    /** 在没有任何素材时合成一段 16k 单声道 WAV，保证测试可离线运行。 */
    private static byte[] synthesizeWav(int seconds) {
        int rate = 16000;
        int samples = rate * seconds;
        byte[] pcm = new byte[samples * 2];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            bb.putShort((short) (Math.sin(2 * Math.PI * 440 * i / rate) * 6000));
        }
        java.nio.ByteBuffer wav = java.nio.ByteBuffer.allocate(44 + pcm.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        wav.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return wav.array();
    }

    @AfterAll
    static void stopMediaServer() {
        if (mediaServer != null) {
            mediaServer.stop(0);
        }
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer " + BEARER)
                .build();
    }

    /** 用真实媒体字节的 sha256，保证 hash 核验能通过。 */
    private static String realSha256() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(mediaBytes));
    }

    private static Map<String, Object> request(String clientRequestId, String sha256)
            throws Exception {
        return Map.of(
                "schemaVersion", "1.0",
                "clientRequestId", clientRequestId,
                "moduleType", "TRANSCRIPTION",
                "classSessionId", "session-e2e-001",
                "mediaId", "media-e2e-001",
                "media", Map.of(
                        "sha256", sha256,
                        "durationMs", 60_000L,
                        "mimeType", "video/mp4",
                        "sourceUrl", mediaUrl,
                        "urlExpiresAt", "2030-01-01T00:00:00Z"),
                "analysisRange", Map.of("startMs", 0L, "endMs", 60_000L),
                "parameters", Map.of("language", "zh-CN", "enableSpeakerLabels", false));
    }

    /** 轮询直到终态，模拟主项目的轮询节奏。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitTerminal(String jobId) throws Exception {
        for (int i = 0; i < 60; i++) {
            Map<String, Object> envelope = client().get()
                    .uri("/api/v1/jobs/{id}", jobId)
                    .retrieve()
                    .body(Map.class);
            Object status = envelope.get("status");
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                return envelope;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("任务未在预期时间内进入终态: " + jobId);
    }

    @Test
    @DisplayName("无凭证访问 → 401，且不进入业务逻辑")
    void rejectsWithoutBearer() {
        var response = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .get().uri("/api/v1/health")
                .retrieve()
                .onStatus(s -> s.value() == 401, (req, resp) -> {
                })
                .toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(String.valueOf(response.getBody().get("code"))).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("/health 返回身份与能力边界（交接表数值），且不含任何密钥")
    void healthExposesCapabilities() {
        Map<?, ?> health = client().get().uri("/api/v1/health").retrieve().body(Map.class);

        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health.get("providerId")).isEqualTo("transcription-e2e");
        assertThat(health.get("moduleVersion")).isEqualTo("e2e-1.0.0");
        assertThat(health.get("timeBase")).isEqualTo("MEDIA_START_MS");
        assertThat(health.get("capabilities")).isInstanceOf(Map.class);
        // 绝不能泄漏凭证
        assertThat(health.toString()).doesNotContain(BEARER);
    }

    @Test
    @DisplayName("全链路：真实媒体下载 + sha256 核验 + ffmpeg 抽音轨 + 时间映射 + 落库")
    void fullHappyPath() throws Exception {
        String sha256 = realSha256();
        Map<String, Object> body = request("req-e2e-ok", sha256);

        var submit = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);

        // 首次提交必须是 202
        assertThat(submit.getStatusCode().value()).isEqualTo(202);
        String jobId = String.valueOf(submit.getBody().get("jobId"));
        assertThat(jobId).startsWith("job-");

        Map<String, Object> envelope = awaitTerminal(jobId);

        // ---- 用与官方 verify_contract.py 同一套规则校验 ----
        com.aijiaoxue.videoreact.job.wire.JobEnvelope parsed =
                parse(envelope);
        assertThat(ContractInvariants.check(parsed)).isEmpty();

        assertThat(envelope.get("status")).isEqualTo("SUCCEEDED");
        assertThat(envelope.get("revision")).isEqualTo(1);
        assertThat(envelope.get("error")).isNull();
        assertThat(envelope.get("timeBase")).isEqualTo("MEDIA_START_MS");
        // 身份必须与请求一致
        assertThat(envelope.get("clientRequestId")).isEqualTo("req-e2e-ok");
        assertThat(envelope.get("classSessionId")).isEqualTo("session-e2e-001");
        assertThat(envelope.get("mediaId")).isEqualTo("media-e2e-001");
        assertThat(envelope.get("mediaSha256")).isEqualTo(sha256);
        assertThat(envelope.get("durationMs")).isEqualTo(60_000);

        // ---- payload ----
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload.get("engineVersion")).isEqualTo("fake-asr-1");
        assertThat(payload.get("language")).isEqualTo("zh-CN");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = (List<Map<String, Object>>) payload.get("segments");
        assertThat(segments).hasSize(2);
        // 时间必须原样保留（整条音轨提取 ⇒ 恒等映射）
        assertThat(segments.get(0).get("startMs")).isEqualTo(1200);
        assertThat(segments.get(0).get("endMs")).isEqualTo(3400);
        assertThat(segments.get(1).get("startMs")).isEqualTo(5000);
        assertThat(segments.get(0).get("id")).isEqualTo("seg-001");
        assertThat(segments.get(0).get("confidence")).isNull();
        assertThat(segments.get(0).get("speakerLabel")).isNull();

        // ---- 关键断言：模块内联提交了音频，没有把受控 sourceUrl 交给第三方 ----
        AsrSubmitCommand command = LAST_COMMAND.get();
        assertThat(command).isNotNull();
        assertThat(command.hasInlineAudio()).isTrue();
        assertThat(command.audioBytes()).isNotEmpty();
        assertThat(command.audioUrl()).isNull();
    }

    @Test
    @DisplayName("同键重提：200 且复用同一 jobId（不重复处理）")
    void duplicateSubmitReturns200() throws Exception {
        Map<String, Object> body = request("req-e2e-dup", realSha256());

        var first = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(Map.class);
        awaitTerminal(String.valueOf(first.getBody().get("jobId")));

        var second = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(Map.class);

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("jobId")).isEqualTo(first.getBody().get("jobId"));
    }

    @Test
    @DisplayName("同键但 media 不同 → 409 IDEMPOTENCY_CONFLICT")
    void conflictingSubmitReturns409() throws Exception {
        Map<String, Object> body = request("req-e2e-conflict", realSha256());
        client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(Map.class);

        Map<String, Object> changed = request("req-e2e-conflict", "b".repeat(64));
        var response = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON).body(changed)
                .retrieve()
                .onStatus(s -> s.value() == 409, (req, resp) -> {
                })
                .toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(response.getBody().get("code")))
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("hash 不符 → 任务 FAILED MEDIA_HASH_MISMATCH，且不可重试")
    void hashMismatchFailsJob() throws Exception {
        Map<String, Object> body = request("req-e2e-hash", "c".repeat(64));

        var submit = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(Map.class);
        Map<String, Object> envelope =
                awaitTerminal(String.valueOf(submit.getBody().get("jobId")));

        assertThat(envelope.get("status")).isEqualTo("FAILED");
        assertThat(envelope.get("payload")).isNull();
        assertThat(envelope.get("revision")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertThat(error.get("code")).isEqualTo("MEDIA_HASH_MISMATCH");
        assertThat(error.get("retryable")).isEqualTo(false);
        // 错误信息里不能出现媒体 URL
        assertThat(String.valueOf(error.get("message"))).doesNotContain("localtest.me");
    }

    @Test
    @DisplayName("参数校验失败 → 422，且 details 能定位到字段")
    void validationFailureReturns422() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(request("req-e2e-bad", realSha256()));
        body.put("analysisRange", Map.of("startMs", 10_000L, "endMs", 20_000L));
        body.put("parameters", Map.of("language", "zh-CN", "enableSpeakerLabels", true));

        var response = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve()
                .onStatus(s -> s.value() == 422, (req, resp) -> {
                })
                .toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(response.getBody().get("code"))).isEqualTo("VALIDATION_FAILED");
        // 说话人标签不支持必须显式拒绝，不能静默忽略
        assertThat(String.valueOf(response.getBody().get("details")))
                .contains("enableSpeakerLabels");
    }

    @Test
    @DisplayName("不支持的 schemaVersion → 422 UNSUPPORTED_SCHEMA（区别于字段校验失败）")
    void unsupportedSchemaReturns422() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(request("req-e2e-schema", realSha256()));
        body.put("schemaVersion", "2.0");

        var response = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve()
                .onStatus(s -> s.value() == 422, (req, resp) -> {
                })
                .toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(response.getBody().get("code"))).isEqualTo("UNSUPPORTED_SCHEMA");
    }

    @Test
    @DisplayName("查询不存在的任务 → 404，不泄漏归属信息")
    void unknownJobReturns404() {
        var response = client().get().uri("/api/v1/jobs/job-does-not-exist")
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, resp) -> {
                })
                .toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(String.valueOf(response.getBody().get("code"))).isEqualTo("NOT_FOUND");
    }

    /** 把 Map 重新序列化再反序列化成信封，避免测试直接依赖 Map 结构。 */
    private static com.aijiaoxue.videoreact.job.wire.JobEnvelope parse(Map<String, Object> raw) {
        var mapper = new tools.jackson.databind.ObjectMapper();
        return mapper.convertValue(raw, com.aijiaoxue.videoreact.job.wire.JobEnvelope.class);
    }
}
