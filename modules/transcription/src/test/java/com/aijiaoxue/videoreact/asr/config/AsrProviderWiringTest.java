package com.aijiaoxue.videoreact.asr.config;

import com.aijiaoxue.videoreact.asr.client.AliyunAsrClient;
import com.aijiaoxue.videoreact.asr.client.AsrProviderClient;
import com.aijiaoxue.videoreact.asr.client.WandAsrClient;
import com.aijiaoxue.videoreact.asr.staging.AudioStaging;
import com.aijiaoxue.videoreact.asr.staging.InlineAudioStaging;
import com.aijiaoxue.videoreact.asr.staging.StagingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提供方装配测试。
 *
 * <p><b>为什么值得单独一个测试类</b>：三个提供方走的是完全不同的协议，
 * 而"选错/装配不上"在本模块里表现得很隐蔽——{@code ApplicationContextRunner}
 * 能在一秒内证明"这条路真的装得起来"，而不是靠人去读那个 {@code switch}。
 *
 * <p><b>注意不能靠 {@code withPropertyValues} 改提供方</b>：{@code AsrProperties}
 * 是 record，本测试又是手动注册的实例，属性源绑不上去。第一版就是这么写的，
 * 结果四个用例都在断言"我以为改成了 aliyun，其实还是 wand"。
 * 所以这里<b>直接构造</b> {@code AsrProperties}——测的是
 * {@code AsrConfiguration} 的分支选择，不是 Spring 的属性绑定。
 * 属性绑定是否把 {@code application.yml} 绑对了，由
 * {@link ApplicationYamlWiringTest} 用真实启动覆盖。
 */
class AsrProviderWiringTest {

    private ApplicationContextRunner runner(String providerType) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AsrConfiguration.class)
                .withBean(AsrProperties.class, () -> properties(providerType))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(StagingProperties.class, AsrProviderWiringTest::stagingProperties)
                .withBean("courseHotwordProperties", CourseHotwordProperties.class,
                        AsrProviderWiringTest::hotwordProperties);
    }

    @Test
    @DisplayName("默认提供方 wand 能装配，并支持内联音频")
    void wandWiresUp() {
        runner("wand").run(context -> {
            assertThat(context).hasNotFailed();
            AsrProviderClient client = context.getBean(AsrProviderClient.class);

            assertThat(client).isInstanceOf(WandAsrClient.class);
            assertThat(client.providerName()).isEqualTo("WAND");
            // 这条决定 JobRunner 走内联还是走暂存：TokenHub 走内联
            assertThat(client.acceptsInlineAudio()).isTrue();
        });
    }

    @Test
    @DisplayName("aliyun-filetrans 能装配，且明确声明只收公网 URL")
    void aliyunWiresUp() {
        runner("aliyun-filetrans").run(context -> {
            assertThat(context).hasNotFailed();
            AsrProviderClient client = context.getBean(AsrProviderClient.class);

            assertThat(client).isInstanceOf(AliyunAsrClient.class);
            assertThat(client.providerName()).isEqualTo("ALIYUN_FILE_TRANS");
            // 这两条组合起来的含义：必须走暂存，而当前暂存不可用
            // —— 也就是"选了阿里云但没配暂存"会明确失败而不是静默降级
            assertThat(client.acceptsInlineAudio()).isFalse();
            assertThat(context.getBean(AudioStaging.class).urlStagingAvailable()).isFalse();
        });
    }

    @Test
    @DisplayName("别名 aliyun 与 aliyun-filetrans 等价，避免使用者猜错名字")
    void aliyunAliasIsAccepted() {
        runner("aliyun").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AsrProviderClient.class)).isInstanceOf(AliyunAsrClient.class);
        });
    }

    @Test
    @DisplayName("类型名大小写与首尾空格都能容忍")
    void providerTypeIsNormalised() {
        runner("  Aliyun-FileTrans  ").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AsrProviderClient.class)).isInstanceOf(AliyunAsrClient.class);
        });
    }

    @Test
    @DisplayName("类型为空时回落到 tencent，而不是抛 NPE")
    void nullTypeFallsBackToTencent() {
        runner(null).run(context -> {
            // tencent 客户端构造本身不需要凭证（凭证在提交时才检查）
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AsrProviderClient.class).providerName()).isEqualTo("TENCENT");
        });
    }

    @Test
    @DisplayName("未知提供方必须在启动时报错，并列出可选值")
    void unknownProviderFailsFast() {
        runner("openai").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("aliyun-filetrans")
                    .hasMessageContaining("wand")
                    .hasMessageContaining("tencent");
        });
    }

    @Test
    @DisplayName("暂存 bean 当前只能是 inline-only 的实现")
    void stagingBeanIsInlineOnlyForNow() {
        runner("wand").run(context -> {
            AudioStaging staging = context.getBean(AudioStaging.class);

            assertThat(staging).isInstanceOf(InlineAudioStaging.class);
            assertThat(staging.urlStagingAvailable()).isFalse();
        });
    }

    /**
     * {@code application.yml} 的关键连线是否真的存在。
     *
     * <p>直接读文件断言而不是启动容器：这里要防的是"代码里加了 aliyun 分支，
     * 但 yml 里忘了加对应的 {@code aliyun:} 配置块"——那种情况下
     * Jackson/Spring 会把缺失的嵌套 record 绑成 {@code null}，
     * 直到有人真的把 {@code ASR_PROVIDER} 切成 aliyun 才在运行期炸掉。
     */
    @Test
    @DisplayName("application.yml 里三个提供方的配置块都齐")
    void applicationYamlHasAllProviderBlocks() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertThat(yaml).contains("aliyun:");
        assertThat(yaml).contains("workspace-id:");
        assertThat(yaml).contains("DASHSCOPE_API_KEY");
        assertThat(yaml).contains("DASHSCOPE_WORKSPACE_ID");
        assertThat(yaml).contains("qwen-audio-3.0-asr-flash-filetrans");
        assertThat(yaml).contains("wand:");
        assertThat(yaml).contains("tencent:");
        // 三个可选值必须都在注释里写清，否则使用者要去读代码才知道能填什么
        assertThat(yaml).contains("aliyun-filetrans");
    }

    /**
     * 暂存配置块是否真的接上了。
     *
     * <p>占位配置最危险的失败模式是"字段加了、yml 没连、于是填了环境变量也没人读"。
     * 这条断言把 yml 与 {@link StagingProperties} 的键名钉在一起。
     */
    @Test
    @DisplayName("application.yml 里的 staging 配置块键名与 StagingProperties 对得上")
    void applicationYamlHasStagingBlock() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertThat(yaml).contains("staging:");
        assertThat(yaml).contains("type: ${STAGING_TYPE:inline}");
        assertThat(yaml).contains("access-key-id: ${OSS_ACCESS_KEY_ID:}");
        assertThat(yaml).contains("access-key-secret: ${OSS_ACCESS_KEY_SECRET:}");
        assertThat(yaml).contains("bucket: ${OSS_BUCKET:}");
        assertThat(yaml).contains("public-endpoint: ${OSS_PUBLIC_ENDPOINT:}");
        assertThat(yaml).contains("internal-endpoint: ${OSS_INTERNAL_ENDPOINT:}");
        assertThat(yaml).contains("url-expiry-seconds:");
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    private static AsrProperties properties(String providerType) {
        return new AsrProperties(
                null,
                new AsrProperties.Provider(providerType, "asr.tencentcloudapi.com", 10, 120,
                        new AsrProperties.Tencent("", "", "ap-guangzhou"),
                        new AsrProperties.Wand("https://tokenhub.tencentmaas.com", "", "wand-asr-v1"),
                        new AsrProperties.Aliyun("", "https://dashscope.aliyuncs.com/api/v1", "",
                                "qwen-audio-3.0-asr-flash-filetrans", null, false, 0, null, 0)),
                new AsrProperties.Poll(true, Duration.ofSeconds(5), Duration.ofSeconds(10), 20,
                        Duration.ofHours(2)),
                new AsrProperties.Engine("16k_zh_en_2.0", 1, 3, 0, 0, 1, 0, 0, 0, 0),
                new AsrProperties.Source(false, 5, 1024L * 1024L * 1024L, 18000L),
                new AsrProperties.Debug(false, "./.work/asr-raw"));
    }

    private static CourseHotwordProperties hotwordProperties() {
        // 热词构建器在装配期不读任何值，给一个空表即可
        return new CourseHotwordProperties(java.util.Map.of(), 10, 128, 30);
    }

    /**
     * 暂存配置：默认 inline（= 明确失败）。
     *
     * <p>注册它是为了让 {@code AsrStartupCheck} 也能被装配起来——
     * 那个 bean 是"选了 OSS 但实现未完成"这类状态的唯一出口，
     * 如果测试里不注册它，这条路径就永远不会被走到。
     */
    private static StagingProperties stagingProperties() {
        return new StagingProperties("inline", null, null, null, null, null,
                null, null, 0L, 0L, 0L, 0, 0);
    }
}
