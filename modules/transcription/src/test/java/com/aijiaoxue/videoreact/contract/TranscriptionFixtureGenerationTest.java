package com.aijiaoxue.videoreact.contract;

import com.aijiaoxue.videoreact.asr.client.AsrProviderClient;
import com.aijiaoxue.videoreact.asr.client.AsrProviderException;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成"与官方 fixture 等价"的成功/失败 JSON，供主项目 M2/M3 直接使用。
 *
 * <p><b>为什么要有这个测试类</b>：契约 §7 要求每个模块交付「两份正常/失败结果」，
 * 而验收包 README 说明主项目「M1 互换成功/失败样例，M2 验证标准接收」——
 * 也就是说主项目要先拿到<b>真实模块产出的信封</b>才能开发接收逻辑。
 * 手写的示例没有这个效力：它不经过模块自己的序列化路径，字段名或可空语义
 * 一旦与实现不一致，主项目就会按错的形状开发。
 *
 * <p><b>等价性怎么保证</b>：这里不手写 JSON，而是
 * <ol>
 *   <li>起真实 HTTP 服务与真实媒体服务器，走真实的契约路由；</li>
 *   <li>媒体下载、sha256 核验、ffprobe/ffmpeg 抽音轨全部真实执行；</li>
 *   <li>只有识别引擎被替换（否则需要凭证与费用，且结果不可复现）；</li>
 *   <li>把模块<b>真正返回的响应体</b>原样落盘为 fixture；</li>
 *   <li>用 {@link ContractInvariants}（官方 {@code verify_contract.py} 的 Java 移植）
 *       逐条校验后再写出。</li>
 * </ol>
 *
 * <p>产物落在 {@code docs/fixtures/transcription-module-v0.1/}，
 * 与官方样例包结构对应，但字段值来自本模块实现。
 */
// 本类通篇在与"无类型 JSON"打交道：RestClient 的 .body(Map.class) 与
// ObjectMapper.readValue(json, Map.class) 都返回原始类型 Map，赋给
// Map<String, Object> 必然产生 unchecked 转换。这些转换在断言处会立刻
// 以 ClassCastException 暴露出来，所以这里是"知其不可为"的取舍，不是掩盖问题。
// 加在类上而不是逐个方法：本类 6 个方法都要做同样的转换，逐个标注只会让噪音更大。
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {"APP_COMPATIBILITY_API=true"},
        classes = com.aijiaoxue.videoreact.VideoReactAsrApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TranscriptionFixtureGenerationTest.FixtureProviderConfiguration.class)
class TranscriptionFixtureGenerationTest {

    private static final String BEARER = "fixture-bearer-token-0123456789";

    /** 与官方 fixture 的 media 时长量级一致（60 秒），便于主项目直接对照。 */
    private static final long DURATION_MS = 60_000L;

    private static final Path OUTPUT_DIR = Path.of("docs/fixtures/transcription-module-v0.1");

    private static HttpServer mediaServer;

    private static String mediaUrl;

    private static byte[] mediaBytes;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 受控的识别引擎替身。
     *
     * <p>返回与官方 fixture 同形的分句，但时间是<b>本模块真实处理后的原媒体毫秒</b>。
     * 之所以要替换：真实引擎需要凭证与额度，且同一段媒体在不同时间可能给出不同切句，
     * 那样产出的 fixture 不可复现，主项目无法据此做回归。
     */
    @TestConfiguration
    static class FixtureProviderConfiguration {

        @Bean
        @Primary
        AsrProviderClient fixtureProvider() {
            return new AsrProviderClient() {

                @Override
                public String submit(AsrSubmitCommand command) {
                    if (!command.hasInlineAudio()) {
                        // 断言模块确实内联提交（契约要求模块自己核验媒体，不外泄受控地址）
                        throw new AsrProviderException("FIXTURE", "ExpectedInlineAudio",
                                "fixture 期望模块以内联音频提交", false);
                    }
                    return "th_fixture_0001";
                }

                @Override
                public AsrProviderStatus describe(String providerTaskId) {
                    return new AsrProviderStatus(
                            providerTaskId,
                            ProviderTaskStatus.SUCCESS,
                            "completed",
                            "本节介绍递归，先看终止条件和递推关系。请用纸笔推导这个递归示例。现在总结递归调用如何返回。",
                            List.of(
                                    new ProviderSentence("本节介绍递归，先看终止条件和递推关系。",
                                            null, null, 0L, 20_000L, null, List.of()),
                                    new ProviderSentence("请用纸笔推导这个递归示例。",
                                            null, null, 20_000L, 30_000L, null, List.of()),
                                    new ProviderSentence("现在总结递归调用如何返回。",
                                            null, null, 50_000L, 60_000L, null, List.of())),
                            60.0,
                            null);
                }

                @Override
                public String providerName() {
                    return "FIXTURE";
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
                    return "fixture-asr-1";
                }
            };
        }
    }

    @BeforeAll
    static void startMediaServer() throws Exception {
        Path cached = Path.of(".work/tools/media/sintel-trailer.mp4");
        mediaBytes = Files.exists(cached) ? Files.readAllBytes(cached) : synthesizeWav(3);

        mediaServer = HttpServer.create(new InetSocketAddress(0), 0);
        mediaServer.createContext("/media", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, mediaBytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(mediaBytes);
            }
        });
        mediaServer.start();
        // lvh.me 只解析到 127.0.0.1：既能被模块下载，又能通过公网地址校验
        mediaUrl = "http://lvh.me:" + mediaServer.getAddress().getPort() + "/media";
    }

    @AfterAll
    static void stopMediaServer() {
        if (mediaServer != null) {
            mediaServer.stop(0);
        }
    }

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:fixture-gen;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        registry.add("videoreact.contract.provider-id", () -> "transcription-module");
        registry.add("videoreact.contract.module-version", () -> "0.1.0");
        registry.add("videoreact.contract.bearer-tokens", () -> BEARER);
        registry.add("videoreact.media.work-dir", () -> "./.work/media-fixture");
        registry.add("videoreact.media.ffmpeg-path", () -> "../../.work/tools/ffmpeg/ffmpeg.exe");
        registry.add("videoreact.media.ffprobe-path", () -> "../../.work/tools/ffmpeg/ffprobe.exe");
        registry.add("videoreact.media.allow-loopback-http", () -> "true");
        registry.add("videoreact.asr.poll.interval", () -> "1s");
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer " + BEARER)
                .build();
    }

    private static String mediaSha256() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(mediaBytes));
    }

    /**
     * 构造请求体，形状与官方 {@code transcription-request.json} 一致。
     *
     * @param clientRequestId 提交幂等键
     * @param sha256          媒体 hash（传错即触发 MDIA_HASH_MISMATCH 失败样例）
     * @param withSpeakerLabels 是否请求说话人标签（true 会被 422 拒绝，用于负例）
     */
    private static Map<String, Object> requestBody(
            String clientRequestId, String sha256, boolean withSpeakerLabels) {
        return Map.of(
                "schemaVersion", "1.0",
                "clientRequestId", clientRequestId,
                "moduleType", "TRANSCRIPTION",
                "classSessionId", "session-fixture-001",
                "mediaId", "media-fixture-001",
                "media", Map.of(
                        "sha256", sha256,
                        "durationMs", DURATION_MS,
                        "mimeType", "video/mp4",
                        "sourceUrl", mediaUrl,
                        "urlExpiresAt", "2030-01-01T00:00:00Z"),
                "analysisRange", Map.of("startMs", 0L, "endMs", DURATION_MS),
                "parameters", Map.of(
                        "language", "zh-CN",
                        "enableSpeakerLabels", withSpeakerLabels));
    }

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
        throw new AssertionError("任务未进入终态: " + jobId);
    }

    private void writeFixture(String fileName, Object content) throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content);
        Path target = OUTPUT_DIR.resolve(fileName);
        Files.writeString(target, json + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("[fixture] 已写出 " + target.toAbsolutePath() + "（" + json.length() + " 字符）");
    }

    @Test
    @DisplayName("生成成功样例：走真实契约路由，产出与官方 fixture 等价的 JobEnvelope")
    // Jackson 把 JSON 反序列化成 Map<String, Object>，取嵌套的 analysisRange/payload/segments
    // 必然要强转。这里加在方法上而不是局部变量上——Java 不允许把 @SuppressWarnings
    // 写在局部变量声明上，之前那 7 处局部标注其实是空操作，警告一直没被压住。
    @SuppressWarnings("unchecked")
    void generateSucceededFixture() throws Exception {
        String sha256 = mediaSha256();
        Map<String, Object> body = requestBody("req-transcription-module-001", sha256, false);

        var submit = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
        // 首次提交必须是 202
        assertThat(submit.getStatusCode().value()).isEqualTo(202);

        String jobId = String.valueOf(submit.getBody().get("jobId"));
        Map<String, Object> envelope = awaitTerminal(jobId);

        // ---- 用官方校验脚本的规则逐条自检后才落盘 ----
        var parsed = objectMapper.convertValue(envelope,
                com.aijiaoxue.videoreact.job.wire.JobEnvelope.class);
        assertThat(ContractInvariants.check(parsed))
                .as("生成的成功样例必须通过契约不自变量校验")
                .isEmpty();

        // ---- 与官方样例逐一对照的关键字段 ----
        assertThat(envelope.get("schemaVersion")).isEqualTo("1.0");
        assertThat(envelope.get("moduleType")).isEqualTo("TRANSCRIPTION");
        assertThat(envelope.get("timeBase")).isEqualTo("MEDIA_START_MS");
        assertThat(envelope.get("status")).isEqualTo("SUCCEEDED");
        assertThat(envelope.get("revision")).isEqualTo(1);
        assertThat(envelope.get("payload")).isNotNull();
        assertThat(envelope.get("error")).isNull();
        assertThat(envelope.get("mediaSha256")).isEqualTo(sha256);
        // 用 Long.valueOf 比较：Jackson 反序列化出的数字是 Integer，
        // 直接与 60_000L 比会因 Long/Integer 不相等而假失败
        assertThat(Long.valueOf(String.valueOf(envelope.get("durationMs")))).isEqualTo(DURATION_MS);
        // 身份与请求一致
        assertThat(envelope.get("classSessionId")).isEqualTo("session-fixture-001");
        assertThat(envelope.get("mediaId")).isEqualTo("media-fixture-001");
        Map<String, Object> envelopeRange = (Map<String, Object>) envelope.get("analysisRange");
        assertThat(Long.valueOf(String.valueOf(envelopeRange.get("startMs")))).isZero();
        assertThat(Long.valueOf(String.valueOf(envelopeRange.get("endMs")))).isEqualTo(DURATION_MS);

        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload.get("language")).isEqualTo("zh-CN");
        assertThat(payload.get("engineVersion")).isEqualTo("fixture-asr-1");
        assertThat(payload.get("processedRanges")).isInstanceOf(List.class);
        assertThat(payload.get("segments")).isInstanceOf(List.class);
        assertThat(payload.get("warnings")).isInstanceOf(List.class);

        List<Map<String, Object>> segments = (List<Map<String, Object>>) payload.get("segments");
        assertThat(segments).hasSize(3);
        // 片段时间为原媒体轴（整条音轨提取 ⇒ 恒等映射）
        assertThat(segments.get(0).get("id")).isEqualTo("seg-001");
        assertThat(segments.get(0).get("startMs")).isEqualTo(0);
        assertThat(segments.get(0).get("endMs")).isEqualTo(20_000);
        assertThat(segments.get(0).get("text"))
                .isEqualTo("本节介绍递归，先看终止条件和递推关系。");
        // 引擎不提供置信度 → 必须为 null，不能编造
        assertThat(segments.get(0).get("confidence")).isNull();
        assertThat(segments.get(0).get("speakerLabel")).isNull();
        assertThat(segments.get(2).get("id")).isEqualTo("seg-003");

        writeFixture("transcription-success.json", envelope);
    }

    @Test
    @DisplayName("生成失败样例：hash 不符 → FAILED MEDIA_HASH_MISMATCH，且不可重试")
    @SuppressWarnings("unchecked")
    void generateFailedFixture() throws Exception {
        // 故意用与真实媒体不符的 hash，触发 MEDIA_HASH_MISMATCH
        String wrongSha256 = "a".repeat(64);
        Map<String, Object> body = requestBody("req-transcription-module-001-failed", wrongSha256, false);

        var submit = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
        assertThat(submit.getStatusCode().value()).isEqualTo(202);

        String jobId = String.valueOf(submit.getBody().get("jobId"));
        Map<String, Object> envelope = awaitTerminal(jobId);

        var parsed = objectMapper.convertValue(envelope,
                com.aijiaoxue.videoreact.job.wire.JobEnvelope.class);
        assertThat(ContractInvariants.check(parsed))
                .as("生成的失败样例必须通过契约不自变量校验")
                .isEmpty();

        assertThat(envelope.get("status")).isEqualTo("FAILED");
        assertThat(envelope.get("revision")).isEqualTo(1);
        // 失败必须 payload=null 且 error 齐备（契约 §4）
        assertThat(envelope.get("payload")).isNull();
        assertThat(envelope.get("error")).isNotNull();

        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertThat(error.get("code")).isEqualTo("MEDIA_HASH_MISMATCH");
        assertThat(error.get("message")).isNotNull();
        assertThat(error.get("retryable")).isEqualTo(false);
        // 契约 §5：message 不含媒体 URL 与堆栈
        assertThat(String.valueOf(error.get("message"))).doesNotContain("lvh.me");
        assertThat(String.valueOf(error.get("message"))).doesNotContain("at com.");

        writeFixture("transcription-failed.json", envelope);
    }

    @Test
    @DisplayName("生成请求样例：与成功样例成对，且能被校验脚本的 request_matches 规则对上")
    @SuppressWarnings("unchecked")
    void generateRequestFixture() throws Exception {
        Map<String, Object> body = requestBody("req-transcription-module-001", mediaSha256(), false);
        writeFixture("transcription-request.json", body);

        // 请求与结果必须能按官方规则对上。
        // 注意不能依赖"另一个测试先跑完并写出成功样例"——JUnit 方法顺序不保证，
        // 那样会出现"单独跑这个类就报文件不存在"的假失败。
        // 这里自己走一遍同一请求拿到结果，成对校验。
        var submit = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
        Map<String, Object> success = awaitTerminal(String.valueOf(submit.getBody().get("jobId")));

        for (String key : List.of("schemaVersion", "clientRequestId", "moduleType",
                "classSessionId", "mediaId")) {
            assertThat(body.get(key)).as("request_matches " + key).isEqualTo(success.get(key));
        }
        // 时间区间要按键值逐个比：反序列化后是 Map，且数字类型可能是 Integer
        Map<String, Object> requestRange = (Map<String, Object>) body.get("analysisRange");
        Map<String, Object> resultRange = (Map<String, Object>) success.get("analysisRange");
        assertThat(String.valueOf(resultRange.get("startMs")))
                .isEqualTo(String.valueOf(requestRange.get("startMs")));
        assertThat(String.valueOf(resultRange.get("endMs")))
                .isEqualTo(String.valueOf(requestRange.get("endMs")));

        Map<String, Object> media = (Map<String, Object>) body.get("media");
        assertThat(media.get("sha256")).isEqualTo(success.get("mediaSha256"));
        assertThat(String.valueOf(media.get("durationMs")))
                .isEqualTo(String.valueOf(success.get("durationMs")));
    }

    @Test
    @DisplayName("生成能力声明样例：/health 响应，供主项目登记交接表数值")
    void generateHealthFixture() throws Exception {
        Map<?, ?> health = client().get().uri("/api/v1/health").retrieve().body(Map.class);

        assertThat(health.get("status")).isEqualTo("UP");
        // 绝不能把凭证写进任何对外响应
        assertThat(health.toString()).doesNotContain(BEARER);

        writeFixture("module-health.json", health);
    }

    @Test
    @DisplayName("生成错误拒绝样例：422 的拒绝体形状（与任务 FAILED 严格区分）")
    void generateRejectionFixture() throws Exception {
        Map<String, Object> body = requestBody("req-transcription-module-001-rejected",
                mediaSha256(), true);

        var response = client().post().uri("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(s -> s.value() == 422, (req, resp) -> {
                })
                .toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        Map<?, ?> rejection = response.getBody();
        assertThat(rejection.get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(rejection.get("retryable")).isEqualTo(false);
        // 契约 §5：拒绝体必须有 requestId 与 details
        assertThat(rejection.get("requestId")).isNotNull();
        assertThat(rejection.get("details")).isInstanceOf(List.class);
        assertThat((List<?>) rejection.get("details")).isNotEmpty();

        writeFixture("transcription-rejected-422.json", rejection);
    }

    @Test
    @DisplayName("契约自检必须能拒绝坏样例（若校验器失效，上面的落盘就等于没校验）")
    void verifierRejectsTamperedFixture() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        // 先确保成功样例已存在（JUnit 方法顺序不保证，这里独立构造最小合法样例来扰动）
        Map<String, Object> succeeded = objectMapper.readValue(
                objectMapper.writeValueAsString(objectMapper.readValue(
                        "{\"schemaVersion\":\"1.0\",\"clientRequestId\":\"r\",\"jobId\":\"j\","
                                + "\"moduleType\":\"TRANSCRIPTION\",\"providerId\":\"p\","
                                + "\"moduleVersion\":\"v\",\"classSessionId\":\"c\",\"mediaId\":\"m\","
                                + "\"mediaSha256\":\"" + "a".repeat(64) + "\",\"durationMs\":60000,"
                                + "\"timeBase\":\"MEDIA_START_MS\","
                                + "\"analysisRange\":{\"startMs\":0,\"endMs\":60000},"
                                + "\"status\":\"SUCCEEDED\",\"revision\":1,"
                                + "\"createdAt\":\"2026-09-13T03:00:00Z\","
                                + "\"updatedAt\":\"2026-09-13T03:01:00Z\","
                                + "\"payload\":{\"language\":\"zh-CN\",\"engineVersion\":\"e\","
                                + "\"processedRanges\":[{\"startMs\":0,\"endMs\":60000}],"
                                + "\"segments\":[{\"id\":\"seg-001\",\"startMs\":0,\"endMs\":1000,"
                                + "\"text\":\"x\",\"confidence\":null,\"speakerLabel\":null}],"
                                + "\"warnings\":[]},\"error\":null}", Map.class)),
                Map.class);

        // 篡改 1：revision 改成 2 → 必须被拒
        Map<String, Object> badRevision = new java.util.HashMap<>(succeeded);
        badRevision.put("revision", 2);
        assertThat(ContractInvariants.check(objectMapper.convertValue(badRevision,
                com.aijiaoxue.videoreact.job.wire.JobEnvelope.class))).isNotEmpty();

        // 篡改 2：SUCCEEDED 却把 payload 置空 → 必须被拒
        Map<String, Object> badPayload = new java.util.HashMap<>(succeeded);
        badPayload.put("payload", null);
        assertThat(ContractInvariants.check(objectMapper.convertValue(badPayload,
                com.aijiaoxue.videoreact.job.wire.JobEnvelope.class))).isNotEmpty();

        // 篡改 3：片段越出 processedRanges → 必须被拒
        Map<String, Object> badSegment = new java.util.HashMap<>(succeeded);
        badSegment.put("payload", Map.of(
                "language", "zh-CN",
                "engineVersion", "e",
                "processedRanges", List.of(Map.of("startMs", 0, "endMs", 1000)),
                "segments", List.of(Map.of("id", "seg-001", "startMs", 0, "endMs", 5000,
                        "text", "x")),
                "warnings", List.of()));
        assertThat(ContractInvariants.check(objectMapper.convertValue(badSegment,
                com.aijiaoxue.videoreact.job.wire.JobEnvelope.class))).isNotEmpty();
    }
}
