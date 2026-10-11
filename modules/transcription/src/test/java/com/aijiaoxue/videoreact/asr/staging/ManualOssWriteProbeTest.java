package com.aijiaoxue.videoreact.asr.staging;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写入权限细粒度定位（<b>默认跳过</b>）。设 {@code MANUAL_OSS_TEST=1} 后运行。
 *
 * <p><b>为什么要切这么细</b>：{@code Access denied by bucket policy} 只说是资源侧策略拒的，
 * 不说是哪一种操作、也不说被哪条语句拒的。把每个可能的写操作单独跑一遍，
 * 就能回答三个关键问题：
 * <ol>
 *   <li>是<b>所有写</b>都被拒，还是只有某个前缀/某种操作；</li>
 *   <li>换成桶根目录会不会仍然被拒（能区分"前缀限定"与"全局拒绝"）；</li>
 *   <li>预签名 URL 是否也受同一策略约束——<b>这条最重要</b>：
 *       如果连预签名 URL 都被 bucket policy 拒，那整个暂存方案在这个桶上就不成立。</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "MANUAL_OSS_TEST", matches = ".+")
class ManualOssWriteProbeTest {

    @Test
    @DisplayName("真机：细粒度定位写入被拒的范围（前缀 / 路径 / 预签名）")
    void probeWriteScope() throws Exception {
        String bucket = System.getenv("OSS_BUCKET").trim();
        String region = System.getenv("OSS_REGION").trim();
        String prefix = normalizePrefix(System.getenv("OSS_PREFIX"));
        String stamp = String.valueOf(Instant.now().toEpochMilli());
        byte[] payload = "probe".getBytes(StandardCharsets.UTF_8);

        OSS client = new OSSClientBuilder().build(
                "https://oss-%s.aliyuncs.com".formatted(region),
                System.getenv("OSS_ACCESS_KEY_ID").trim(),
                System.getenv("OSS_ACCESS_KEY_SECRET").trim());

        System.out.println();
        System.out.println("=== 写入范围探测（bucket=" + bucket + ", prefix=" + prefix + "）===");
        System.out.println("目的：判断拒绝是按前缀还是整个桶——这条边界决定该改 RAM 策略的哪一句。");
        System.out.println();

        String[] keys = {
                prefix + "probe-" + stamp + ".txt",                    // 目标前缀
                "videoreact/staging-probe-" + stamp + ".txt",          // 前缀但少了末尾斜杠
                "videoreact-probe-" + stamp + ".txt",                  // 桶根目录
        };
        for (String key : keys) {
            probePut(client, bucket, key, payload);
        }

        // 预签名 URL：即使 PUT 被拒，生成的 URL 也要单独看——
        // 它是由 SDK 本地签名、不访问服务端，所以"生成成功"不代表"能用"
        String signedKey = prefix + "probe-signed-" + stamp + ".txt";
        String url = null;
        try {
            url = client.generatePresignedUrl(bucket, signedKey,
                    Date.from(Instant.now().plus(Duration.ofMinutes(10)))).toString();
            System.out.printf("%-52s ✅ 本地生成成功（未访问服务端）%n", "E. 生成预签名 URL");
        } catch (Exception e) {
            System.out.printf("%-52s ❌ %s%n", "E. 生成预签名 URL", e.getMessage());
        }
        if (url != null) {
            probeAnonymousGet(url, "F. 匿名 GET 预签名 URL（对象不存在，看错误码）");
        }

        // 清理尝试：成功过的 key 不该留下垃圾
        for (String key : keys) {
            try {
                client.deleteObject(bucket, key);
            } catch (RuntimeException ignored) {
                // 删除失败本身已在 D 步探测过
            }
        }
        client.shutdown();

        System.out.println();
        System.out.println("判读方法：");
        System.out.println("  只有目标前缀被拒 + 桶根可写 → 策略里按前缀限制了，需要放行 " + prefix);
        System.out.println("  两者都被拒             → 策略是全局拒绝写入（或该账号被整体 Deny）");
        System.out.println("  匿名 GET 也被拒        → 连预签名都过不去，暂存方案在这个桶上不成立");

        assertThat(true).isTrue();
    }

    private static void probePut(OSS client, String bucket, String key, byte[] payload) {
        String label = "C" + (key.startsWith("videoreact/") ? ". PUT " : ". PUT（桶根） ") + key;
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(payload.length);
            client.putObject(new PutObjectRequest(bucket, key,
                    new ByteArrayInputStream(payload), metadata));
            System.out.printf("%-52s ✅ 成功%n", shorten(label));
            try {
                client.deleteObject(bucket, key);
            } catch (RuntimeException ignored) {
                // 忽略：已单独探测过删除
            }
        } catch (OSSException e) {
            System.out.printf("%-52s ❌ %s / %s%n",
                    shorten(label), e.getErrorCode(), e.getErrorMessage());
        }
    }

    private static void probeAnonymousGet(String url, String label) {
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? ""
                    : response.body().replaceAll("\\s+", " ");
            System.out.printf("%-52s HTTP %d %s%n", label, response.statusCode(),
                    body.length() > 90 ? body.substring(0, 90) + "…" : body);
        } catch (Exception e) {
            System.out.printf("%-52s ❌ %s%n", label, e.getMessage());
        }
    }

    private static String shorten(String text) {
        return text.length() <= 52 ? text : text.substring(0, 28) + "…"
                + text.substring(text.length() - 20);
    }

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
}
