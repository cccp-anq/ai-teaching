package com.aijiaoxue.videoreact.asr.client;

import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AliyunAsrClient} 的离线协议测试。
 *
 * <p>用真实的 {@link HttpServer} 而不是打桩 HTTP 客户端：这样能真实覆盖
 * 请求头、请求体、URL 拼接、响应解析与错误分支，而且<b>不联网、不消耗额度</b>。
 * 只有"结果 JSON 下载"这一步是注入的，因为它指向的是另一个主机。
 *
 * <p>刻意不测的东西：真实百炼接口的字段名是否与文档一致。
 * 那只能靠真机验证，写在这里会变成"用我的假设验证我的假设"。
 */
class AliyunAsrClientTest {

    private static final String TASK_ID = "7d0a58a3-1dbe-4de9-8cff-5f48213128b0";

    private HttpServer server;

    private String baseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 捕获提交请求体，用于断言我们到底发了什么。 */
    private final AtomicReference<String> submitBody = new AtomicReference<>();

    private final AtomicReference<String> submitAuthHeader = new AtomicReference<>();

    private final AtomicReference<String> submitAsyncHeader = new AtomicReference<>();

    /** 轮询次数，用于让任务状态依次经过 PENDING → RUNNING → SUCCEEDED。 */
    private final AtomicInteger pollCount = new AtomicInteger();

    /** 轮询响应体（由各用例设置）。 */
    private final AtomicReference<String> pollBody = new AtomicReference<>();

    /** 轮询请求方法，用于断言 GET/POST。 */
    private final AtomicReference<String> pollMethod = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/audio/asr/transcription", exchange -> {
            submitBody.set(readBody(exchange));
            submitAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            submitAsyncHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
            respond(exchange, 200, """
                    {"request_id":"req-submit","output":{"task_id":"%s","task_status":"PENDING"}}
                    """.formatted(TASK_ID));
        });
        server.createContext("/api/v1/tasks/", exchange -> {
            pollMethod.set(exchange.getRequestMethod());
            respond(exchange, 200, pollBody.get());
        });
        server.createContext("/result.json", exchange ->
                respond(exchange, 200, RESULT_JSON));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ------------------------------------------------------------------
    //  提交
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交：带上 Bearer、异步标识、model/file_urls，且 parameters 对象必须存在")
    void submitSendsRequiredHeadersAndBody() {
        AliyunAsrClient client = client();

        String taskId = client.submit(AsrSubmitCommand.ofUrl(
                "https://staging.example.com/audio.wav", client.modelIdentifier()));

        assertThat(taskId).isEqualTo(TASK_ID);
        assertThat(submitAuthHeader.get()).isEqualTo("Bearer test-key");
        // 官方：「请勿遗漏，否则无法提交任务」
        assertThat(submitAsyncHeader.get()).isEqualTo("enable");

        JsonNode body = objectMapper.readTree(submitBody.get());
        assertThat(body.get("model").stringValue()).isEqualTo("qwen-audio-3.0-asr-flash-filetrans");
        assertThat(body.get("input").get("file_urls").get(0).stringValue())
                .isEqualTo("https://staging.example.com/audio.wav");
        // 官方：新域名下 parameters 必须传，即使是空对象
        assertThat(body.has("parameters")).isTrue();
        // channel_id 必须是数组。发标量时提交照样 200，任务却在异步阶段失败：
        // "parameter.channel_id must be an array"（2026-09-15 真机实测）
        assertThat(body.get("parameters").get("channel_id").isArray()).isTrue();
        assertThat(body.get("parameters").get("channel_id").get(0).asInt()).isZero();
    }

    @Test
    @DisplayName("提交：channel_id 必须是数组——真机踩过的坑，用真实报文钉住")
    void channelIdMustBeAnArray() {
        // 这条不是重复：它断言的是"服务端会怎么判"，而不是"我们发了什么"。
        // 下面这段 FAILED 报文来自真机（parameters 里发 channel_id=0 标量时的响应）。
        String realFailure = """
                {"request_id":"9df07c87-1a6b-95eb-846a-9b8830723984",
                 "output":{"task_id":"96b3f8dd-a501-4ab1-8ca1-c17e9ad5d6a4",
                   "task_status":"FAILED",
                   "submit_time":"2026-09-15 20:08:21.062",
                   "scheduled_time":"2026-09-15 20:08:21.111",
                   "end_time":"2026-09-15 20:08:21.210",
                   "code":"parameter.channel_id must be an array",
                   "message":"parameter.channel_id must be an array"}}
                """;
        pollBody.set(realFailure);

        AsrProviderStatus status = client().describe(TASK_ID);

        assertThat(status.status()).isEqualTo(ProviderTaskStatus.FAILED);
        // 失败原因必须完整透出，否则用户在任务详情里只看到一句笼统的失败
        assertThat(status.errorMessage()).contains("channel_id must be an array");
    }

    @Test
    @DisplayName("提交：SERVER_ERROR 这种光秃秃的码要补一句可操作的说明")
    void bareServerErrorGetsAHint() {
        // 真机：音频地址不可达时阿里云只回 {"code":"SERVER_ERROR"}，连 message 都没有
        pollBody.set("""
                {"request_id":"490decf0-b177-93db-baac-135489091bd9",
                 "output":{"task_id":"5b8dff33-4939-4d84-86e9-5876fbf6932f",
                   "task_status":"FAILED",
                   "submit_time":"2026-09-15 20:08:21.400",
                   "scheduled_time":"2026-09-15 20:08:21.447",
                   "end_time":"2026-09-15 20:09:06.573",
                   "code":"SERVER_ERROR"}}
                """);

        AsrProviderStatus status = client().describe(TASK_ID);

        assertThat(status.status()).isEqualTo(ProviderTaskStatus.FAILED);
        assertThat(status.errorMessage())
                .contains("SERVER_ERROR")
                .contains("暂存地址")
                .contains("公网可达");
    }

    @Test
    @DisplayName("提交：languageHints 为空时整个字段都不发，而不是发空数组")
    void submitOmitsLanguageHintsWhenEmpty() {
        AliyunAsrClient client = client();
        client.submit(AsrSubmitCommand.ofUrl("https://staging.example.com/a.wav",
                client.modelIdentifier(), List.of()));

        JsonNode parameters = objectMapper.readTree(submitBody.get()).get("parameters");
        assertThat(parameters.get("language_hints")).isNull();
    }

    @Test
    @DisplayName("提交：配了 languageHints 就发出去（中英混课的场景）")
    void submitSendsLanguageHints() {
        AliyunAsrClient client = client();
        client.submit(AsrSubmitCommand.ofUrl("https://staging.example.com/a.wav",
                client.modelIdentifier(), List.of("zh", "en")));

        JsonNode hints = objectMapper.readTree(submitBody.get())
                .get("parameters").get("language_hints");
        assertThat(hints).hasSize(2);
        assertThat(hints.get(0).stringValue()).isEqualTo("zh");
    }

    @Test
    @DisplayName("提交：内联音频必须被明确拒绝，而不是发一个空 file_urls")
    void submitRejectsInlineAudio() {
        AliyunAsrClient client = client();

        assertThatThrownBy(() -> client.submit(AsrSubmitCommand.ofBytes(
                new byte[] {1, 2, 3}, "a.wav", client.modelIdentifier())))
                .isInstanceOf(AsrProviderException.class)
                .hasMessageContaining("不接受内联音频");
    }

    @Test
    @DisplayName("提交：HTTP 400 且在 HTTP 200 里带业务 code，都要抛异常")
    void submitRaisesOnBusinessErrorCode() throws IOException {
        restartWithSubmitResponse(200, """
                {"request_id":"req-1","code":"InvalidParameter","message":"file_urls 不能为空"}
                """);
        AliyunAsrClient client = client();

        assertThatThrownBy(() -> client.submit(
                AsrSubmitCommand.ofUrl("https://staging.example.com/a.wav", client.modelIdentifier())))
                .isInstanceOf(AsrProviderException.class)
                .hasMessageContaining("InvalidParameter");
    }

    // ------------------------------------------------------------------
    //  轮询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("轮询：PENDING/RUNNING 都不算终态，SUCCEEDED 才解析结果")
    void describeWalksThroughStates() {
        pollBody.set(pending());
        AliyunAsrClient client = client();

        assertThat(client.describe(TASK_ID).status()).isEqualTo(ProviderTaskStatus.WAITING);

        pollBody.set(running());
        assertThat(client.describe(TASK_ID).status()).isEqualTo(ProviderTaskStatus.RUNNING);

        pollBody.set(succeeded());
        AsrProviderStatus status = client.describe(TASK_ID);

        assertThat(status.status()).isEqualTo(ProviderTaskStatus.SUCCESS);
        assertThat(pollMethod.get()).isEqualTo("GET");
        // 官方：句子级 begin_time/end_time 是毫秒整数
        assertThat(status.sentences()).hasSize(2);
        assertThat(status.sentences().get(0).startMs()).isEqualTo(100L);
        assertThat(status.sentences().get(0).endMs()).isEqualTo(3820L);
        assertThat(status.sentences().get(0).finalSentence()).isEqualTo("Hello world, 这里是阿里巴巴语音实验室。");
        // 官方：说话人分离开启时才有 speaker_id
        assertThat(status.sentences().get(1).speakerId()).isEqualTo(1L);
        // 时长来自 properties.original_duration_in_milliseconds，转成秒
        assertThat(status.audioDuration()).isEqualTo(3.834);
        assertThat(status.result()).contains("第二句");
    }

    @Test
    @DisplayName("轮询：任务级 end_time 是字符串日期，绝不能当成句子时间戳")
    void describeIgnoresTaskLevelEndTime() {
        pollBody.set(succeeded());
        AsrProviderStatus status = client().describe(TASK_ID);

        // 结果 JSON 里任务级 end_time 形如 "2024-12-16 16:31:02.375"；
        // 若解析串了字段，这里会看到 2024 这种量级
        assertThat(status.sentences())
                .allSatisfy(sentence -> assertThat(sentence.startMs()).isLessThan(10_000L));
    }

    @Test
    @DisplayName("轮询：子任务失败时整体判失败（任务级 SUCCEEDED 不代表这条成功）")
    void describeFailsWhenSubtaskFailed() {
        pollBody.set("""
                {"output":{"task_id":"%s","task_status":"SUCCEEDED","results":[
                  {"file_url":"https://x/a.wav","code":"InvalidFile.DownloadFailed",
                   "message":"The audio file cannot be downloaded.","subtask_status":"FAILED"}]}}
                """.formatted(TASK_ID));
        AsrProviderStatus status = client().describe(TASK_ID);

        assertThat(status.status()).isEqualTo(ProviderTaskStatus.FAILED);
        assertThat(status.errorMessage()).contains("InvalidFile.DownloadFailed");
    }

    @Test
    @DisplayName("轮询：未知状态当作仍在处理，而不是把任务判死")
    void describeTreatsUnknownStatusAsInProgress() {
        pollBody.set("""
                {"output":{"task_id":"%s","task_status":"SOMETHING_NEW"}}
                """.formatted(TASK_ID));

        assertThat(client().describe(TASK_ID).status()).isEqualTo(ProviderTaskStatus.UNKNOWN);
        assertThat(ProviderTaskStatus.UNKNOWN.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("轮询：状态映射覆盖官方四个枚举值")
    void statusMappingCoversOfficialEnum() {
        assertThat(AliyunAsrClient.mapStatus("PENDING")).isEqualTo(ProviderTaskStatus.WAITING);
        assertThat(AliyunAsrClient.mapStatus("RUNNING")).isEqualTo(ProviderTaskStatus.RUNNING);
        assertThat(AliyunAsrClient.mapStatus("SUCCEEDED")).isEqualTo(ProviderTaskStatus.SUCCESS);
        assertThat(AliyunAsrClient.mapStatus("FAILED")).isEqualTo(ProviderTaskStatus.FAILED);
        assertThat(AliyunAsrClient.mapStatus(null)).isEqualTo(ProviderTaskStatus.UNKNOWN);
    }

    // ------------------------------------------------------------------
    //  结果解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结果：静音音频（transcripts 有但 sentences 空）算成功且无分段")
    void describeAcceptsEmptySentencesAsSilence() {
        pollBody.set(succeeded());
        AliyunAsrClient client = new AliyunAsrClient(properties(baseUrl), objectMapper,
                url -> """
                        {"properties":{"original_duration_in_milliseconds":5000},
                         "transcripts":[{"channel_id":0,"text":"","sentences":[]}]}
                        """);

        AsrProviderStatus status = client.describe(TASK_ID);

        // 契约：合法静音可成功且 segments=[]，由 JobRunner 补 NO_SPEECH 警告
        assertThat(status.status()).isEqualTo(ProviderTaskStatus.SUCCESS);
        assertThat(status.sentences()).isEmpty();
        assertThat(status.hasSentences()).isFalse();
    }

    @Test
    @DisplayName("结果：transcripts 缺失属于不可重试的结构异常")
    void describeRejectsMissingTranscripts() {
        pollBody.set(succeeded());
        AliyunAsrClient client = new AliyunAsrClient(properties(baseUrl), objectMapper,
                url -> "{\"properties\":{}}");

        assertThatThrownBy(() -> client.describe(TASK_ID))
                .isInstanceOf(AsrProviderException.class)
                .hasMessageContaining("transcripts");
    }

    @Test
    @DisplayName("结果：多音轨时按 channel 归并，不是只取第一条")
    void describeMergesAllChannels() {
        pollBody.set(succeeded());
        AliyunAsrClient client = new AliyunAsrClient(properties(baseUrl), objectMapper,
                url -> """
                        {"properties":{"original_duration_in_milliseconds":2000},
                         "transcripts":[
                           {"channel_id":0,"text":"左","sentences":[{"begin_time":0,"end_time":500,"text":"左"}]},
                           {"channel_id":1,"text":"右","sentences":[{"begin_time":500,"end_time":1000,"text":"右"}]}]}
                        """);

        AsrProviderStatus status = client.describe(TASK_ID);

        assertThat(status.sentences()).hasSize(2);
        assertThat(status.result()).isEqualTo("左右");
    }

    @Test
    @DisplayName("结果：成功但没有 transcription_url 时判失败并说明原因")
    void describeFailsWhenTranscriptionUrlMissing() {
        pollBody.set("""
                {"output":{"task_id":"%s","task_status":"SUCCEEDED","results":[
                  {"file_url":"https://x/a.wav","subtask_status":"SUCCEEDED"}]}}
                """.formatted(TASK_ID));
        AsrProviderStatus status = client().describe(TASK_ID);

        assertThat(status.status()).isEqualTo(ProviderTaskStatus.FAILED);
        assertThat(status.errorMessage()).contains("transcription_url");
    }

    @Test
    @DisplayName("轮询：真实成功报文的嵌套 output 结构（外层 results 才是有效路径）")
    void parsesRealSucceededEnvelope() {
        // 这段是 2026-09-15 真机响应的结构复刻（签名串已截断）。
        // 关键点：output 里嵌了一个同名的 output，里面是重复的一份 results。
        // 有效路径是【外层】output.results —— 读错层级会拿不到 transcription_url。
        pollBody.set("""
                {"request_id":"f2521ef9-c411-9b81-8fa1-503009a7d4a7",
                 "output":{"task_id":"%s","task_status":"SUCCEEDED",
                   "submit_time":"2026-09-15 20:09:08.590",
                   "scheduled_time":"2026-09-15 20:09:08.626",
                   "end_time":"2026-09-15 20:09:09.141",
                   "output":{"file_url":"https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male.wav",
                     "results":[{"file_url":"https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male.wav",
                       "results":[],"transcription_url":"https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/prod/fun-asr/20260915/x.json?Expires=1&Signature=TRUNCATED",
                       "subtask_status":"SUCCEEDED"}],
                     "transcription_url":"https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/prod/fun-asr/20260915/x.json?Expires=1&Signature=TRUNCATED",
                     "subtask_status":"SUCCEEDED"},
                   "file_url":"https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male.wav",
                   "usage":{"duration":4},
                   "request_id":"494ec9ef-1dda-4d1c-8145-3c9af05c5a27",
                   "results":[{"file_url":"https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male.wav",
                     "results":[],"transcription_url":"http://127.0.0.1:1/result.json",
                     "subtask_status":"SUCCEEDED"}],
                   "transcription_url":"http://127.0.0.1:1/result.json",
                   "subtask_status":"SUCCEEDED"}}
                """.formatted(TASK_ID));

        AsrProviderStatus status = client().describe(TASK_ID);

        assertThat(status.status()).isEqualTo(ProviderTaskStatus.SUCCESS);
        assertThat(status.sentences()).hasSize(2);
    }

    @Test
    @DisplayName("结果：下载结果 JSON 失败时可重试（链接 24 小时内仍可补取）")
    void describeIsRetryableWhenResultDownloadFails() {
        pollBody.set(succeeded());
        AliyunAsrClient client = new AliyunAsrClient(properties(baseUrl), objectMapper,
                url -> {
                    throw new AsrProviderException(AliyunAsrClient.PROVIDER,
                            "ResultDownloadFailed", "下载转写结果 JSON 失败，HTTP 403", true);
                });

        assertThatThrownBy(() -> client.describe(TASK_ID))
                .isInstanceOf(AsrProviderException.class)
                .satisfies(e -> assertThat(((AsrProviderException) e).retryable()).isTrue());
    }

    // ------------------------------------------------------------------
    //  凭证与地址
    // ------------------------------------------------------------------

    @Test
    @DisplayName("凭证：没配 API Key 时给出可操作的提示，且提交前就报错")
    void missingApiKeyIsReportedActionably() {
        AliyunAsrClient client = new AliyunAsrClient(
                properties(baseUrl, "", ""), objectMapper, url -> RESULT_JSON);

        assertThat(client.credentialsReady()).isFalse();
        assertThat(client.configurationHints())
                .anySatisfy(hint -> assertThat(hint).contains("DASHSCOPE_API_KEY"))
                .anySatisfy(hint -> assertThat(hint).contains("DASHSCOPE_WORKSPACE_ID"));
        // WorkspaceId 的提示必须标明"可选"。曾经这里写的是「缺它会 404/401」，
        // 那是照文档推的，而真机实测证明公共域名可以正常调用——
        // 过时的提示会把使用者引去配一个不影响可用性的东西。
        assertThat(client.configurationHints())
                .anySatisfy(hint -> assertThat(hint).contains("可选"))
                .allSatisfy(hint -> assertThat(hint).doesNotContain("404/401"));
        assertThatThrownBy(() -> client.submit(
                AsrSubmitCommand.ofUrl("https://x/a.wav", client.modelIdentifier())))
                .isInstanceOf(AsrProviderException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    @DisplayName("地址：配了 workspaceId 就用专属域名，没配才回落到 baseUrl")
    void resolvedBaseUrlPrefersWorkspaceDomain() {
        AliyunAsrClient withWorkspace = new AliyunAsrClient(
                properties(baseUrl, "k", "ws-abc"), objectMapper, url -> RESULT_JSON);
        AliyunAsrClient withoutWorkspace = new AliyunAsrClient(
                properties("https://dashscope.aliyuncs.com/api/v1/", "k", "  "), objectMapper,
                url -> RESULT_JSON);

        // 走这里而不是直接断言字段：确保假 HTTP 服务器只在本测试里被绕过，
        // 而 workspaceId 的改写规则本身仍被覆盖
        assertThat(withWorkspace.providerName()).isEqualTo("ALIYUN_FILE_TRANS");
        assertThat(withoutWorkspace.providerName()).isEqualTo("ALIYUN_FILE_TRANS");
        assertThat(properties(baseUrl, "k", "ws-abc").provider().aliyun().resolvedBaseUrl())
                .isEqualTo("https://ws-abc.cn-beijing.maas.aliyuncs.com/api/v1");
        assertThat(properties(baseUrl, "k", "").provider().aliyun().resolvedBaseUrl())
                .isEqualTo(baseUrl);
        // 末尾斜杠必须被吃掉，否则会拼出 //tasks/...
        assertThat(properties("https://dashscope.aliyuncs.com/api/v1/", "k", "")
                .provider().aliyun().resolvedBaseUrl())
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1");
    }

    @Test
    @DisplayName("配置：language-hints 绑成空串时要收敛成 null，否则会发出非法语种码")
    void blankLanguageHintsAreNormalisedAway() {
        AsrProperties.Aliyun bound = new AsrProperties.Aliyun(
                "", "https://x/api/v1", "k", "m", List.of(""), false, 0, "", 0);

        assertThat(bound.languageHints()).isNull();
    }

    @Test
    @DisplayName("能力：明确声明不收内联音频，且单文件上限是 2GB")
    void declaresCapabilities() {
        AliyunAsrClient client = client();

        assertThat(client.acceptsInlineAudio()).isFalse();
        assertThat(client.maxInputUrlBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(client.providerName()).isEqualTo("ALIYUN_FILE_TRANS");
    }

    @Test
    @DisplayName("重试判定：参数/文件类不可重试，限流与 5xx 可重试")
    void retryClassification() {
        assertThat(AliyunAsrClient.isRetryable(400, "InvalidParameter")).isFalse();
        assertThat(AliyunAsrClient.isRetryable(400, "InvalidFile.DownloadFailed")).isFalse();
        assertThat(AliyunAsrClient.isRetryable(401, null)).isFalse();
        assertThat(AliyunAsrClient.isRetryable(429, null)).isTrue();
        assertThat(AliyunAsrClient.isRetryable(500, null)).isTrue();
        assertThat(AliyunAsrClient.isRetryable(200, null)).isTrue();
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    private AliyunAsrClient client() {
        return new AliyunAsrClient(properties(baseUrl), objectMapper, url -> RESULT_JSON);
    }

    /**
     * 构造配置。
     *
     * <p>轮询间隔不在这里配置：间隔由 {@code JobRunner} 决定，
     * client 只管"问一次答一次"，所以本测试不需要等待。
     *
     * <p><b>workspaceId 必须留空</b>，否则 {@code resolvedBaseUrl()} 会按设计
     * 改写成 {@code https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1}，
     * 测试就会真的打到阿里云去——第一版就是这么写的，结果拿到一个真实的
     * {@code InvalidApiKey} 401，而不是本地假服务器该给的响应。
     */
    private static AsrProperties properties(String base) {
        return properties(base, "test-key", "");
    }

    private static AsrProperties properties(String base, String apiKey, String workspaceId) {
        return new AsrProperties(
                null,
                new AsrProperties.Provider("aliyun-filetrans", "asr.tencentcloudapi.com", 5, 60,
                        new AsrProperties.Tencent("", "", "ap-guangzhou"),
                        new AsrProperties.Wand("https://tokenhub.tencentmaas.com", "", "wand-asr-v1"),
                        new AsrProperties.Aliyun(workspaceId, base, apiKey,
                                "qwen-audio-3.0-asr-flash-filetrans",
                                null, false, 0, null, 0)),
                new AsrProperties.Poll(true, Duration.ofSeconds(1), Duration.ofSeconds(1), 20,
                        Duration.ofMinutes(30)),
                new AsrProperties.Engine("16k_zh_en_2.0", 1, 3, 0, 0, 1, 0, 0, 0, 0),
                new AsrProperties.Source(false, 5, 1024L * 1024L * 1024L, 18000L),
                new AsrProperties.Debug(false, "./.work/asr-raw"));
    }

    private static String pending() {
        return """
                {"request_id":"r1","output":{"task_id":"%s","task_status":"PENDING",
                 "task_metrics":{"TOTAL":1,"SUCCEEDED":0,"FAILED":0}}}
                """.formatted(TASK_ID);
    }

    private static String running() {
        return """
                {"request_id":"r2","output":{"task_id":"%s","task_status":"RUNNING",
                 "task_metrics":{"TOTAL":1,"SUCCEEDED":0,"FAILED":0}}}
                """.formatted(TASK_ID);
    }

    private static String succeeded() {
        return """
                {"request_id":"r3","output":{"task_id":"%s","task_status":"SUCCEEDED",
                 "end_time":"2024-12-16 16:31:02.375",
                 "results":[{"file_url":"https://x/a.wav","subtask_status":"SUCCEEDED",
                   "transcription_url":"http://127.0.0.1:1/result.json"}],
                 "task_metrics":{"TOTAL":1,"SUCCEEDED":1,"FAILED":0}},
                 "usage":{"duration":4}}
                """.formatted(TASK_ID);
    }

    /** 官方「识别结果说明」里的样例结构（保留毫秒整数时间戳与 speaker_id）。 */
    private static final String RESULT_JSON = """
            {"file_url":"https://x/a.wav",
             "properties":{"audio_format":"pcm_s16le","channels":[0],
               "original_sampling_rate":16000,
               "original_duration_in_milliseconds":3834},
             "transcripts":[{"channel_id":0,"content_duration_in_milliseconds":3720,
               "text":"Hello world, 这里是阿里巴巴语音实验室。第二句。",
               "sentences":[
                 {"begin_time":100,"end_time":3820,
                  "text":"Hello world, 这里是阿里巴巴语音实验室。","sentence_id":1,"speaker_id":0,
                  "words":[{"begin_time":100,"end_time":596,"text":"Hello ","punctuation":""}]},
                 {"begin_time":4000,"end_time":4800,
                  "text":"第二句。","sentence_id":2,"speaker_id":1}]}]}
            """;

    private void restartWithSubmitResponse(int status, String body) throws IOException {
        server.removeContext("/api/v1/services/audio/asr/transcription");
        server.createContext("/api/v1/services/audio/asr/transcription", exchange -> {
            submitBody.set(readBody(exchange));
            respond(exchange, status, body);
        });
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
