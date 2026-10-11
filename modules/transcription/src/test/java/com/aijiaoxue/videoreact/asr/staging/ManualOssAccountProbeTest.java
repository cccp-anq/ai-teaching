package com.aijiaoxue.videoreact.asr.staging;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定位 OSS 配置里的"哪一半"配错了（<b>默认跳过</b>）。
 *
 * <p>只有设置了 {@code MANUAL_OSS_TEST=1} 才会运行。
 *
 * <p><b>解决什么问题</b>：{@code The bucket you access does not belong to you}
 * 只有一个含义——<b>bucket 名与 AccessKey 不属于同一个阿里云账号</b>。
 * 但这句话不告诉你是"桶名抄错了"还是"Key 是另一个账号的"。
 *
 * <p>{@code ListBuckets} 给出的正是缺失的那一半：<b>这把 Key 能看见哪些桶</b>。
 * 两者一对照，答案立刻确定：
 * <ul>
 *   <li>列表里有目标桶 → 桶名对，Key 是另一个账号的（或桶在别的账号下）</li>
 *   <li>列表里没有目标桶 → 要么桶名抄错，要么 Key 与桶不同账号</li>
 *   <li>列表返回空 → 这把 Key 没有任何桶权限，或策略里少了 {@code oss:ListBuckets}</li>
 * </ul>
 *
 * <p>⚠️ 这个探测需要 {@code oss:ListBuckets} 权限。正式策略里我们<b>故意不给</b>它
 * （见 {@code docs/OSS暂存接入方案.md} §5），所以这里被拒是正常的——
 * 被拒时改用控制台肉眼核对即可，探测仍会打印出该去哪一屏看。
 */
@EnabledIfEnvironmentVariable(named = "MANUAL_OSS_TEST", matches = ".+")
class ManualOssAccountProbeTest {

    @Test
    @DisplayName("真机：这把 Key 能看见哪些桶（用来判断桶名与 Key 是否同账号）")
    void listVisibleBuckets() {
        String accessKeyId = System.getenv("OSS_ACCESS_KEY_ID");
        String accessKeySecret = System.getenv("OSS_ACCESS_KEY_SECRET");
        String target = System.getenv("OSS_BUCKET");

        System.out.println();
        System.out.println("=== 对照用：Key 主账号 ID ===");
        System.out.println("AccessKeyId = " + mask(accessKeyId));
        System.out.println("目标 bucket = " + target);
        System.out.println();

        OSS client = new OSSClientBuilder()
                .build("https://oss-cn-beijing.aliyuncs.com", accessKeyId, accessKeySecret);
        try {
            var buckets = client.listBuckets();
            System.out.printf("=== 这把 Key 能看见 %d 个桶 ===%n", buckets.size());
            for (Bucket b : buckets) {
                String mark = b.getName().equals(target) ? "  ← 目标桶在此账号下 ✅" : "";
                System.out.printf("  %-40s %-16s %s%s%n",
                        b.getName(), b.getRegion(), b.getCreationDate(), mark);
            }

            boolean found = buckets.stream().anyMatch(b -> b.getName().equals(target));
            System.out.println();
            if (found) {
                System.out.println("结论：桶名正确，且属于这把 Key 的账号。"
                        + "那么此前的 AccessDenied 是权限策略问题，不是账号问题。");
            } else if (buckets.isEmpty()) {
                System.out.println("结论：列表为空。可能①策略里没有 oss:ListBuckets；"
                        + "②这把 Key 确实没有该桶权限。");
            } else {
                System.out.println("结论：目标桶 " + target
                        + " 【不在这把 Key 的账号下】。请核对：桶名是否抄错、"
                        + "或桶是否建在另一个阿里云账号 / 另一个地域。");
            }
        } catch (OSSException e) {
            System.out.printf("ListBuckets 被拒：%s / %s%n",
                    e.getErrorCode(), e.getErrorMessage());
            System.out.println("（这是预期的——正式 RAM 策略故意不给 oss:ListBuckets。"
                    + "改用控制台核对：OSS 控制台看目标桶在哪个账号下，"
                    + "RAM 控制台看这把 AccessKey 属于哪个账号。）");
        } finally {
            client.shutdown();
        }

        assertThat(true).isTrue();
    }

    /** 只显示首尾，中间打码——AccessKeyId 不是机密，但也没必要整串进日志。 */
    private static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "(未设置)";
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }
}
