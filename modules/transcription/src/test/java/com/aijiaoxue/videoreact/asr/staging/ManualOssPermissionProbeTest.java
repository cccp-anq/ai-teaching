package com.aijiaoxue.videoreact.asr.staging;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectListing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OSS 权限问题定位（<b>默认跳过</b>）。
 *
 * <p>只有设置了 {@code MANUAL_OSS_TEST=1} 才会运行。
 *
 * <p><b>为什么单独一个类</b>：{@code Access denied by bucket policy} 这个报错里
 * 藏着一条容易忽略的信息——拒绝来自 <b>bucket policy</b>，不是 RAM 策略。
 * 这两者的排查路径完全不同：
 * <ul>
 *   <li>RAM 策略（用户侧）：RAM 控制台 → 用户 → 权限管理</li>
 *   <li>bucket policy（资源侧）：OSS 控制台 → bucket → 权限管理 → **Bucket 授权策略**</li>
 * </ul>
 * 不知道区别的人会一直去改 RAM 策略，然后困惑于"改了没反应"——资源侧的
 * 显式 Deny 优先级高于用户侧的 Allow。
 *
 * <p>本类逐个能力探测并打印实际错误，把"该去哪一屏改"直接指出来。
 * 探测全部只读，唯一的写操作是上传一个 1 字节对象（用完即删）。
 */
@EnabledIfEnvironmentVariable(named = "MANUAL_OSS_TEST", matches = ".+")
class ManualOssPermissionProbeTest {

    @Test
    @DisplayName("真机：逐项探测 OSS 权限，定位拒绝来自 RAM 还是 bucket policy")
    void probePermissions() {
        String accessKeyId = System.getenv("OSS_ACCESS_KEY_ID");
        String accessKeySecret = System.getenv("OSS_ACCESS_KEY_SECRET");
        String bucket = System.getenv("OSS_BUCKET");
        String region = System.getenv("OSS_REGION");

        assertThat(bucket).as("OSS_BUCKET 未设置").isNotBlank();
        System.out.printf("[探测] bucket=%s region=%s%n", bucket, region);

        OSS client = new OSSClientBuilder()
                .build("https://oss-%s.aliyuncs.com".formatted(region),
                        accessKeyId, accessKeySecret);

        try {
            // ---------- 0. ownership 判定（最关键的一步） ----------
            // 这一段是在一次误判之后补上的：我们曾长期认为
            // "Access denied by bucket policy" 意味着 bucket 上有条 Deny，
            // 直到 GetBucketPolicy 返回 NoSuchBucketPolicy 才证明那个方向是错的。
            // ListBuckets 不依赖任何 bucket 是否存在，返回的是"这把 Key 属于哪个账号、
            // 该账号有哪些桶"——正是判断跨账号所需的另一半信息。
            probe("0. 这把 Key 能看见哪些桶（owner 判定）", () -> {
                var buckets = client.listBuckets();
                if (buckets.isEmpty()) {
                    return "空列表 → 该账号下没有桶，或策略未授予 oss:ListBuckets";
                }
                StringBuilder text = new StringBuilder("共 " + buckets.size() + " 个：");
                for (var b : buckets) {
                    text.append("\n        - ").append(b.getName())
                            .append("  region=").append(b.getRegion())
                            .append(b.getName().equals(bucket) ? "   ← 目标桶在此账号下 ✅" : "");
                }
                boolean owned = buckets.stream().anyMatch(b -> b.getName().equals(bucket));
                text.append(owned
                        ? "\n        ⇒ 目标桶属于这把 Key 的账号"
                        : "\n        ⇒ ⚠ 目标桶【不在】这把 Key 的账号下 —— 这就是 AccessDenied 的根因");
                return text.toString();
            });

            // ---------- A. 读 bucket 元信息 ----------
            // 这条不需要任何 OSS 数据权限，只要能通过身份认证就该成功。
            // 它成功 = 凭证有效、端点正确，问题一定在权限策略上。
            probe("A. 身份认证（GetBucketInfo）", () ->
                    client.getBucketInfo(bucket).getBucket().getName());

            // ---------- B. 列举对象 ----------
            // 我的 RAM 策略【故意没给】oss:ListObjects，所以这里预期被拒。
            // 被拒是正常的——列出它是为了确认"拒绝的理由"与 A 是否不同。
            probe("B. 列举对象（ListObjects，策略故意未授予）", () -> {
                ObjectListing listing = client.listObjects(bucket);
                return "对象数=" + listing.getObjectSummaries().size();
            });

            // ---------- C. 上传（关键） ----------
            String probeKey = "videoreact/staging/permission-probe.tmp";
            probe("C. 上传对象（PutObject）", () -> {
                client.putObject(bucket, probeKey,
                        new java.io.ByteArrayInputStream(new byte[] {0}), null);
                return "上传成功";
            });

            // ---------- D. 删除 ----------
            probe("D. 删除对象（DeleteObject）", () -> {
                client.deleteObject(bucket, probeKey);
                return "删除成功（或对象不存在）";
            });

        } finally {
            client.shutdown();
        }
    }

    /**
     * 跑一个探测并打印结果。
     *
     * <p>刻意<b>不</b>让失败中断整个测试：这里的目的是"把每个能力的真实结果列出来"，
     * 一个能力失败不应该掩盖其余能力的结论。
     */
    private static void probe(String label, ThrowingSupplier action) {
        try {
            System.out.printf("[探测] %-46s ✅ %s%n", label, action.get());
        } catch (OSSException e) {
            System.out.printf("[探测] %-46s ❌ %s / %s%n",
                    label, e.getErrorCode(), oneLine(e.getErrorMessage()));
            if (e.getErrorMessage() != null && e.getErrorMessage().contains("bucket policy")) {
                System.out.println("        ↑ 拒绝来自【Bucket 授权策略】（资源侧），"
                        + "不是 RAM 策略。去 OSS 控制台 → 该 bucket → 权限管理 → Bucket 授权策略 检查");
            }
            if ("AccessDenied".equals(e.getErrorCode())
                    && e.getErrorMessage() != null
                    && !e.getErrorMessage().contains("bucket policy")) {
                System.out.println("        ↑ 普通 AccessDenied：去 RAM 控制台检查给这个子账号的策略");
            }
        } catch (Exception e) {
            System.out.printf("[探测] %-46s ❌ %s: %s%n",
                    label, e.getClass().getSimpleName(), oneLine(e.getMessage()));
        }
    }

    private static String oneLine(String text) {
        return text == null ? "(无描述)" : text.replaceAll("\\s+", " ");
    }

    /** 允许抛受检异常的 Supplier。 */
    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }
}
