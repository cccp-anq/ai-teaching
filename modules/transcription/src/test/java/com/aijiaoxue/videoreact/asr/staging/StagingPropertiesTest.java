package com.aijiaoxue.videoreact.asr.staging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StagingProperties} 的归一化与推导。
 *
 * <p><b>为什么占位配置也值得一整个测试类</b>：环境变量绑定出来的字符串很难保证干净——
 * 空串、前后空格、多余的斜杠都会出现。这些值会直接拼进 OSS 的请求里，
 * 拼错了就是一个很难查的 404 或签名失败，而且失败点离"我填了什么"很远。
 * 在绑定阶段把 {@code "  "} 收敛成 {@code null}、把 {@code a/b} 补成 {@code a/b/}，
 * 比在上传失败时回头猜便宜得多。
 */
class StagingPropertiesTest {

    // ------------------------------------------------------------------
    //  类型与必填项
    // ------------------------------------------------------------------

    @Test
    @DisplayName("type 默认 inline；大小写与空格都能容忍")
    void typeDefaultsToInlineAndIsNormalised() {
        assertThat(properties(null, null, null, null, null, null).type())
                .isEqualTo(StagingProperties.TYPE_INLINE);
        assertThat(properties("  ", null, null, null, null, null).type())
                .isEqualTo(StagingProperties.TYPE_INLINE);
        assertThat(properties("  OSS  ", null, null, null, null, null).type())
                .isEqualTo(StagingProperties.TYPE_OSS);
        assertThat(properties("OSS", "k", "s", "b", "cn-beijing", null).isOss()).isTrue();
        assertThat(properties("inline", "k", "s", "b", "cn-beijing", null).isOss()).isFalse();
    }

    @Test
    @DisplayName("region 空值时回落到 cn-beijing，而不是拼出 oss-null.aliyuncs.com")
    void regionFallsBack() {
        StagingProperties props = properties("oss", "k", "s", "b", "  ", null);

        assertThat(props.region()).isEqualTo("cn-beijing");
        assertThat(props.resolvedPublicEndpoint()).isEqualTo("https://oss-cn-beijing.aliyuncs.com");
    }

    @Test
    @DisplayName("选了 oss 但缺凭证时，isOssConfigured 如实回答并列出缺哪几项")
    void reportsMissingOssFields() {
        StagingProperties empty = properties("oss", null, null, null, "cn-beijing", null);

        assertThat(empty.isOss()).isTrue();
        assertThat(empty.isOssConfigured()).isFalse();
        assertThat(empty.missingOssFields())
                .containsExactly("OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET", "OSS_BUCKET");
        // 提示必须能直接照着去填，而不是只说"配置不完整"
        assertThat(empty.describe()).contains("配置不完整").contains("OSS_BUCKET");

        StagingProperties full = properties("oss", "k", "s", "b", "cn-beijing", null);
        assertThat(full.isOssConfigured()).isTrue();
        assertThat(full.missingOssFields()).isEmpty();
    }

    @Test
    @DisplayName("空串与纯空格都要收敛成 null，不能变成 bucket 名 \"  \"")
    void blankValuesBecomeNull() {
        StagingProperties props = properties("oss", "  ", " ", "", "cn-beijing", "   ");

        assertThat(props.accessKeyId()).isNull();
        assertThat(props.accessKeySecret()).isNull();
        assertThat(props.bucket()).isNull();
        assertThat(props.publicEndpoint()).isNull();
        // 空白 bucket 若被当成有效值，会在上传时变成一个极难定位的 404
        assertThat(props.isOssConfigured()).isFalse();
    }

    // ------------------------------------------------------------------
    //  前缀
    // ------------------------------------------------------------------

    @Test
    @DisplayName("前缀统一成「要么空，要么以 / 结尾」")
    void prefixIsNormalised() {
        assertThat(props("a/b").prefix()).isEqualTo("a/b/");
        assertThat(props("a/b/").prefix()).isEqualTo("a/b/");
        assertThat(props("/a/b").prefix()).isEqualTo("a/b/");
        assertThat(props("//a/b//").prefix()).isEqualTo("a/b//");
        assertThat(props("").prefix()).isEmpty();
        assertThat(props(null).prefix()).isEmpty();
        assertThat(props("   ").prefix()).isEmpty();
        assertThat(props("/").prefix()).isEmpty();
    }

    @Test
    @DisplayName("对象键由前缀拼成，不会出现少斜杠或多斜杠")
    void objectKeyIsJoinedCleanly() {
        assertThat(props("videoreact/staging/").objectKey("job-1.wav"))
                .isEqualTo("videoreact/staging/job-1.wav");
        assertThat(props("videoreact/staging").objectKey("job-1.wav"))
                .isEqualTo("videoreact/staging/job-1.wav");
        // 空前缀时不应以 / 开头（以 / 开头的 key 在 OSS 里是另一个对象）
        assertThat(props("").objectKey("job-1.wav")).isEqualTo("job-1.wav");
    }

    // ------------------------------------------------------------------
    //  端点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("公网端点：留空按 region 推导；显式配置优先且末尾斜杠被吃掉")
    void publicEndpointResolution() {
        assertThat(properties("oss", "k", "s", "b", "cn-shanghai", null)
                .resolvedPublicEndpoint()).isEqualTo("https://oss-cn-shanghai.aliyuncs.com");
        assertThat(properties("oss", "k", "s", "b", "cn-beijing",
                "https://cdn.example.com/").resolvedPublicEndpoint())
                .isEqualTo("https://cdn.example.com");
    }

    @Test
    @DisplayName("上传端点：配了内网就用内网，没配才回落公网")
    void uploadEndpointPrefersInternal() {
        StagingProperties withoutInternal = properties("oss", "k", "s", "b", "cn-beijing", null);
        assertThat(withoutInternal.resolvedUploadEndpoint())
                .isEqualTo(withoutInternal.resolvedPublicEndpoint());

        StagingProperties withInternal = new StagingProperties("oss", "k", "s", "b", "cn-beijing",
                "videoreact/staging/", null, "https://oss-cn-beijing-internal.aliyuncs.com/",
                10800L, 104857600L, 10485760L, 300, 3);

        assertThat(withInternal.resolvedUploadEndpoint())
                .isEqualTo("https://oss-cn-beijing-internal.aliyuncs.com");
        // 关键：内网端点绝不能出现在给识别引擎的预签名 URL 里
        assertThat(withInternal.resolvedPublicEndpoint())
                .isEqualTo("https://oss-cn-beijing.aliyuncs.com");
    }

    // ------------------------------------------------------------------
    //  数值兜底
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无效数值回落到安全默认值")
    void numericDefaults() {
        StagingProperties zeroed = new StagingProperties("oss", "k", "s", "b", "cn-beijing",
                null, null, null, 0L, 0L, 0L, 0, -1);

        assertThat(zeroed.urlExpirySeconds()).isEqualTo(StagingProperties.DEFAULT_URL_EXPIRY_SECONDS);
        assertThat(zeroed.timeoutSeconds()).isEqualTo(300);
        assertThat(zeroed.maxRetries()).isZero();
    }

    @Test
    @DisplayName("describe() 只输出定位信息，绝不输出凭证")
    void describeNeverLeaksCredentials() {
        StagingProperties props = properties("oss", "AKID-SECRET-LOOKING-VALUE",
                "SECRET-VALUE-SHOULD-NOT-APPEAR", "my-bucket", "cn-beijing", null);

        String described = props.describe();

        assertThat(described).contains("my-bucket").contains("cn-beijing");
        assertThat(described).doesNotContain("AKID-SECRET-LOOKING-VALUE");
        assertThat(described).doesNotContain("SECRET-VALUE-SHOULD-NOT-APPEAR");
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    private static StagingProperties props(String prefix) {
        return new StagingProperties("oss", "k", "s", "b", "cn-beijing", prefix,
                null, null, 10800L, 104857600L, 10485760L, 300, 3);
    }

    private static StagingProperties properties(
            String type, String keyId, String keySecret, String bucket, String region, String endpoint) {
        return new StagingProperties(type, keyId, keySecret, bucket, region, "videoreact/staging/",
                endpoint, null, 10800L, 104857600L, 10485760L, 300, 3);
    }
}
