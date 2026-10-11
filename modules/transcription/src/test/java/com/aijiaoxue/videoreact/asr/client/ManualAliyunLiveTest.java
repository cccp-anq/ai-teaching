package com.aijiaoxue.videoreact.asr.client;

import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阿里云真实接口联调（<b>默认跳过</b>）。
 *
 * <p>只有同时设置了环境变量 {@code MANUAL_ASR_TEST_KEY} 才会运行：
 * <pre>
 * $env:MANUAL_ASR_TEST_KEY = "sk-..."
 * mvn -B -s .mvn/settings-sandbox.xml test "-Dtest=ManualAliyunLiveTest"
 * </pre>
 *
 * <p><b>为什么要有这个测试</b>：离线测试只能证明"我按我的假设发了请求、并正确解析了
 * 我编造的响应"。真实接口的字段名、参数类型、失败形状是否与假设一致，
 * 只有真机能回答——2026-09-15 就是靠真机发现 {@code channel_id} 必须发数组，
 * 而发标量时<b>提交返回 HTTP 200 却在异步阶段失败</b>，离线测试永远抓不到。
 *
 * <p><b>为什么默认跳过而不是标 {@code @Disabled}</b>：{@code @Disabled} 会让它永远不跑；
 * 用环境变量门控可以在需要时一条命令拉起，同时不会让 CI 因为缺凭据而红。
 *
 * <p><b>成本</b>：每次运行提交 1 个约 4 秒的音频任务，费用可忽略（≈0.001 元）。
 * 用的是阿里云官方公开样例音频，不含任何真实课堂内容。
 */
@EnabledIfEnvironmentVariable(named = "MANUAL_ASR_TEST_KEY", matches = ".+")
class ManualAliyunLiveTest {

    /** 阿里云官方样例音频（约 3.9 秒中文）。 */
    private static final String SAMPLE_AUDIO =
            "https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male.wav";

    private static final String PUBLIC_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";

    @Test
    @DisplayName("真机：提交 → 轮询 → 下载结果 JSON → 解析出毫秒分段")
    void liveSubmitPollAndParse() throws Exception {
        String apiKey = System.getenv("MANUAL_ASR_TEST_KEY");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // 结果 JSON 是另一个主机上的预签名 URL。这里自己取一层，
        // 而不是让客户端内部去取——这样测试能独立断言"结果文件确实可下载、结构确实是那样"。
        AliyunAsrClient client = new AliyunAsrClient(properties(apiKey), new ObjectMapper(),
                url -> {
                    try {
                        HttpResponse<String> response = http.send(
                                HttpRequest.newBuilder(URI.create(url))
                                        .timeout(Duration.ofSeconds(60))
                                        .header("Accept", "application/json, */*")
                                        .GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertThat(response.statusCode())
                                .as("结果 JSON 应当可直接下载（预签名 URL，无需 Bearer）")
                                .isEqualTo(200);
                        return response.body();
                    } catch (Exception e) {
                        throw new AssertionError("下载结果 JSON 失败: " + e.getMessage(), e);
                    }
                });

        // ---------- 1. 提交 ----------
        String taskId = client.submit(AsrSubmitCommand.ofUrl(
                SAMPLE_AUDIO, client.modelIdentifier(), List.of("zh")));
        assertThat(taskId).as("应当拿到 task_id").isNotBlank();

        // ---------- 2. 轮询到终态 ----------
        AsrProviderStatus status = null;
        for (int i = 0; i < 60; i++) {
            status = client.describe(taskId);
            if (status.status().isTerminal()) {
                break;
            }
            Thread.sleep(2000);
        }
        assertThat(status).isNotNull();
        assertThat(status.status())
                .as("真机任务应当成功；失败原因=%s", status.errorMessage())
                .isEqualTo(ProviderTaskStatus.SUCCESS);

        // ---------- 3. 解析结果 ----------
        assertThat(status.sentences()).as("应当解析出分段").isNotEmpty();
        ProviderSentence first = status.sentences().get(0);
        assertThat(first.startMs()).as("句子级时间戳是毫秒整数").isNotNull().isLessThan(10_000L);
        assertThat(first.endMs()).isNotNull();
        assertThat(first.endMs()).isGreaterThan(first.startMs());
        assertThat(first.finalSentence()).isNotBlank();
        // 3.9 秒音频的时长应当落在合理范围，用来确认读的是 properties 而不是任务级字符串日期
        assertThat(status.audioDuration()).isNotNull().isBetween(1.0, 30.0);

        System.out.printf("[真机] taskId=%s 分段=%d 时长=%.3fs 文本=%s%n",
                taskId, status.sentences().size(), status.audioDuration(), status.result());
    }

    @Test
    @DisplayName("真机：不存在的模型名要在提交阶段就失败（而不是变成一个坏任务）")
    void liveRejectsUnknownModel() {
        AliyunAsrClient client = new AliyunAsrClient(
                properties(System.getenv("MANUAL_ASR_TEST_KEY"), "qwen-not-a-real-model"),
                new ObjectMapper(), url -> "{}");

        // 真机长这样：HTTP 400 {"code":"InvalidParameter","message":"Model not exist."}
        assertThatThrownBy(() -> client.submit(
                AsrSubmitCommand.ofUrl(SAMPLE_AUDIO, client.modelIdentifier())))
                .isInstanceOf(AsrProviderException.class)
                .satisfies(e -> {
                    AsrProviderException ex = (AsrProviderException) e;
                    assertThat(ex.providerErrorCode()).isEqualTo("InvalidParameter");
                    // 参数类错误重试一次也不会变好
                    assertThat(ex.retryable()).isFalse();
                });
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    private static AsrProperties properties(String apiKey) {
        return properties(apiKey, "qwen-audio-3.0-asr-flash-filetrans");
    }

    private static AsrProperties properties(String apiKey, String model) {
        return new AsrProperties(
                null,
                new AsrProperties.Provider("aliyun-filetrans", "asr.tencentcloudapi.com", 10, 120,
                        new AsrProperties.Tencent("", "", "ap-guangzhou"),
                        new AsrProperties.Wand("https://tokenhub.tencentmaas.com", "", "wand-asr-v1"),
                        // workspaceId 留空 ⇒ 走公共域名 dashscope.aliyuncs.com。
                        // 实测该域名下这个 Key 可直接调用（见 docs/阿里云模型选型.md §5）。
                        new AsrProperties.Aliyun("", PUBLIC_BASE_URL, apiKey, model,
                                null, false, 0, null, 0)),
                new AsrProperties.Poll(true, Duration.ofSeconds(2), Duration.ofSeconds(1), 20,
                        Duration.ofMinutes(5)),
                new AsrProperties.Engine("16k_zh_en_2.0", 1, 3, 0, 0, 1, 0, 0, 0, 0),
                new AsrProperties.Source(false, 5, 1024L * 1024L * 1024L, 18000L),
                new AsrProperties.Debug(false, "./.work/asr-raw"));
    }
}
