package com.aijiaoxue.videoreact.asr.staging;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OSS 连通性与预签名 URL 可行性验证（<b>默认跳过</b>）。
 *
 * <p>只有设置了 {@code MANUAL_OSS_TEST=1} 才会运行：
 * <pre>
 * $env:MANUAL_OSS_TEST = "1"
 * mvn -B -s .mvn/settings-sandbox.xml test "-Dtest=ManualOssConnectivityTest"
 * </pre>
 *
 * <p><b>为什么在写实现之前先做这个</b>：整个阿里云方案压在一个假设上——
 * 「百度能用一个<b>带查询串的预签名 URL</b> 匿名下载到我们的音频」。
 * 这个假设如果错了，后面写的每一行上传代码都要推倒重来。
 * 所以先用最小代价验掉它，而不是等实现完再发现。
 *
 * <p>本测试做四件事，每件都独立可诊断：
 * <ol>
 *   <li><b>上传</b>——验凭证有效、RAM 策略的 {@code oss:PutObject} 够用；</li>
 *   <li><b>预签名 URL 能被匿名访问</b>——这是方案的核心假设，
 *       用<b>不带任何凭证</b>的 HTTP 客户端去 GET；</li>
 *   <li><b>改坏签名必须被拒</b>——证明我们依赖的是签名而不是"恰好公共读"。
 *       这条很重要：如果 bucket 实际是公共读的，第 2 步会假绿；</li>
 *   <li><b>删除</b>——验 {@code oss:DeleteObject} 够用，且确认测试不留垃圾。</li>
 * </ol>
 *
 * <p><b>测试对象</b>：一个几百字节的临时文件，前缀与生产一致
 * （{@code videoreact/staging/}），用完立即删除。
 */
@EnabledIfEnvironmentVariable(named = "MANUAL_OSS_TEST", matches = ".+")
class ManualOssConnectivityTest {

    private static final String ENDPOINT_TEMPLATE = "https://oss-%s.aliyuncs.com";

    @Test
    @DisplayName("真机：上传 → 预签名 URL 匿名可读 → 坏签名被拒 → 删除")
    void connectivityAndPresignedUrl() throws Exception {
        String accessKeyId = required("OSS_ACCESS_KEY_ID");
        String accessKeySecret = required("OSS_ACCESS_KEY_SECRET");
        String bucket = required("OSS_BUCKET");
        String region = required("OSS_REGION");
        String prefix = normalizePrefix(System.getenv("OSS_PREFIX"));
        String endpoint = ENDPOINT_TEMPLATE.formatted(region);

        System.out.printf("[OSS] bucket=%s region=%s prefix=%s endpoint=%s%n",
                bucket, region, prefix, endpoint);

        // 对象键用随机名 + 明显的测试标记，便于在控制台一眼认出并手工清理
        String objectKey = prefix + "connectivity-probe-" + Instant.now().toEpochMilli() + ".txt";
        byte[] payload = ("videoreact OSS connectivity probe "
                + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8);

        OSS client = new OSSClientBuilder()
                .build(endpoint, accessKeyId, accessKeySecret);
        HttpClient anonymous = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        try {
            // ---------- 0. 前置检查：桶与 Key 必须属于同一账号 ----------
            // 这一步不是多余。跨账号时后面每一步都会失败，而错误信息
            // （"Access denied by bucket policy"）会把人引向权限策略，
            // 真正的原因却是账号对不上。在这里先把它拦下来并说清怎么修。
            assertBucketReachable(client, bucket);
            System.out.println("[OSS] 0/4 前置检查通过：桶可访问，账号匹配");

            // ---------- 1. 上传 ----------
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(payload.length);
            metadata.setContentType("text/plain; charset=utf-8");
            client.putObject(new PutObjectRequest(bucket, objectKey,
                    new java.io.ByteArrayInputStream(payload), metadata));
            System.out.println("[OSS] 1/4 上传成功: " + objectKey);

            // ---------- 2. 预签名 URL 匿名可读（核心假设） ----------
            Date expiration = Date.from(Instant.now().plus(Duration.ofMinutes(10)));
            String url = client.generatePresignedUrl(bucket, objectKey, expiration).toString();
            // 只打 host 与路径，不打查询串：里面带签名，等同临时凭证
            System.out.println("[OSS] 2/4 预签名 URL host+path = " + url.split("\\?")[0]);

            HttpResponse<byte[]> anon = anonymous.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertThat(anon.statusCode())
                    .as("预签名 URL 必须能被匿名 GET（这是整个暂存方案的前提）。"
                            + "若为 403，检查 RAM 策略是否含 oss:GetObject 且 Resource 前缀正确")
                    .isEqualTo(200);
            assertThat(anon.body())
                    .as("匿名读到的内容必须与上传的一致")
                    .isEqualTo(payload);
            System.out.println("[OSS] 2/4 匿名 GET 成功，内容一致，sha256="
                    + sha256(anon.body()).substring(0, 16) + "…");

            // ---------- 3. 坏签名必须被拒 ----------
            // 把 Signature 参数改坏再请求。若 bucket 实际是公共读的，这里会返回 200，
            // 那说明第 2 步是"假绿"——我们并没有真正依赖签名。
            String tampered = tamperSignature(url);
            HttpResponse<String> tamperedResponse = anonymous.send(
                    HttpRequest.newBuilder(URI.create(tampered))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(tamperedResponse.statusCode())
                    .as("签名被改坏后必须被拒绝。返回 200 说明 bucket 是公共读的，"
                            + "课堂录音会被任何拿到 URL 的人下载——这是隐私问题，必须修")
                    .isIn(403, 401);
            System.out.println("[OSS] 3/4 坏签名被拒 HTTP " + tamperedResponse.statusCode()
                    + "，证明访问控制真的来自签名");

            // ---------- 4. 删除 ----------
            client.deleteObject(bucket, objectKey);
            System.out.println("[OSS] 4/4 已删除探测对象（bucket 内不留垃圾）");

        } finally {
            // 无论断言在哪一步失败都要清理；删不掉也不该掩盖真正的失败原因
            try {
                client.deleteObject(bucket, objectKey);
            } catch (RuntimeException cleanupFailure) {
                System.out.println("[OSS] 清理时出错（可能是已删除）: " + cleanupFailure.getMessage());
            }
            client.shutdown();
        }

        System.out.println("[OSS] 连通性验证全部通过，暂存方案的前提成立");
    }

    // ------------------------------------------------------------------
    //  辅助
    // ------------------------------------------------------------------

    /**
     * 前置检查：确认 bucket 属于这个 AccessKey 所在的账号。
     *
     * <p>{@code getBucketInfo} 不需要任何 OSS 数据权限，只要能通过身份认证就该成功；
     * 它失败只有两种可能，且各自对应完全不同的修法：
     * <ul>
     *   <li>{@code The bucket you access does not belong to you} ——
     *       <b>桶与 Key 不同账号</b>。去控制台核对两边的主账号 ID；</li>
     *   <li>其它 {@code AccessDenied} —— 桶名或地域抄错。</li>
     * </ul>
     * 把它放在最前面，是为了避免后面每一步都报 {@code Access denied by bucket policy}
     * 而把人引向"去改权限策略"——那是错的排查方向。
     */
    private static void assertBucketReachable(OSS client, String bucket) {
        try {
            String actualName = client.getBucketInfo(bucket).getBucket().getName();
            assertThat(actualName).isEqualTo(bucket);
        } catch (OSSException e) {
            String message = e.getErrorMessage() == null ? "" : e.getErrorMessage();
            if (message.contains("does not belong to you")) {
                throw new AssertionError("""
                        OSS 前置检查失败：bucket「%s」与当前 AccessKey 不属于同一个阿里云账号。

                        这不是权限策略问题，改策略不会有任何效果。请核对两处：
                          1) OSS 控制台 → 存储空间列表：bucket「%s」列在哪个账号下、哪个地域；
                          2) RAM 控制台 → 用户：这把 AccessKey（%s）属于哪个账号。
                        两边必须是同一个主账号，否则 OSS 一律以
                        "Access denied by bucket policy" 拒绝后续所有操作。

                        也请顺便确认地域一致（当前按 %s 连接）。
                        """.formatted(bucket, bucket,
                        System.getenv("OSS_ACCESS_KEY_ID"),
                        System.getenv("OSS_REGION")));
            }
            throw new AssertionError("OSS 前置检查失败 [%s]: %s"
                    .formatted(e.getErrorCode(), message), e);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertThat(value)
                .as("环境变量 %s 未设置。请先 . .\\scripts\\Use-Env.ps1 载入 .env", name)
                .isNotBlank();
        return value.trim();
    }

    /** 与 {@code StagingProperties} 相同的归一化：要么空，要么以 / 结尾。 */
    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    /**
     * 把 {@code Signature=...} 的最后一个字符改掉。
     *
     * <p>只动签名本身，不动 {@code Expires} 与 {@code OSSAccessKeyId}：
     * 这样若被拒绝，原因一定是"签名对不上"，而不是"参数缺失"。
     */
    private static String tamperSignature(String url) {
        int idx = url.indexOf("Signature=");
        assertThat(idx).as("预签名 URL 里应当有 Signature 参数").isGreaterThan(0);
        int valueStart = idx + "Signature=".length();
        int valueEnd = url.indexOf('&', valueStart);
        if (valueEnd < 0) {
            valueEnd = url.length();
        }
        String signature = url.substring(valueStart, valueEnd);
        char last = signature.charAt(signature.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return url.substring(0, valueEnd - 1) + replacement + url.substring(valueEnd);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
