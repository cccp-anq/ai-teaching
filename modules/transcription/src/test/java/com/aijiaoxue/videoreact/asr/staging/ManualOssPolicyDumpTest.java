package com.aijiaoxue.videoreact.asr.staging;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 bucket 上<b>真正生效</b>的授权策略原文读出来（默认跳过）。
 *
 * <p>设 {@code MANUAL_OSS_TEST=1} 后运行。
 *
 * <p><b>为什么需要它</b>：控制台上看到的和实际生效的可能是两回事——
 * 页面可能没保存、可能改的是另一栏（读写权限 ACL vs Bucket 授权策略）、
 * 也可能有多条语句叠加。{@code GetBucketPolicy} 返回的是服务端真实的策略文档，
 * 是唯一不会骗人的来源。
 *
 * <p>读不到也没关系，那本身也是信息：说明 RAM 策略里没有
 * {@code oss:GetBucketPolicy}，或该操作被显式拒绝。
 */
@EnabledIfEnvironmentVariable(named = "MANUAL_OSS_TEST", matches = ".+")
class ManualOssPolicyDumpTest {

    @Test
    @DisplayName("真机：打印 bucket 上生效的授权策略原文 + ACL")
    void dumpEffectivePolicy() {
        String bucket = System.getenv("OSS_BUCKET").trim();
        String region = System.getenv("OSS_REGION").trim();

        OSS client = new OSSClientBuilder().build(
                "https://oss-%s.aliyuncs.com".formatted(region),
                System.getenv("OSS_ACCESS_KEY_ID").trim(),
                System.getenv("OSS_ACCESS_KEY_SECRET").trim());

        try {
            System.out.println();
            System.out.println("=== bucket = " + bucket + " ===");

            // 1) 授权策略原文
            try {
                // 注意 SDK 3.18.2 的 getBucketPolicy 返回 GetBucketPolicyResult，
                // 策略文本要经 getPolicyText() 取，不是直接返回 String
                String policy = client.getBucketPolicy(bucket).getPolicyText();
                System.out.println("--- Bucket 授权策略（服务端实际生效）---");
                if (policy == null || policy.isBlank()) {
                    System.out.println("(空 —— 说明确实没有任何 bucket 策略生效)");
                } else {
                    System.out.println(policy);
                }
            } catch (OSSException e) {
                System.out.printf("--- 读授权策略失败: %s / %s%n",
                        e.getErrorCode(), oneLine(e.getErrorMessage()));
                System.out.println("（预期之内：RAM 策略里没有 oss:GetBucketPolicy。"
                        + "但若你想让我直接读，可以临时给它加这一条 action 再测。）");
            }

            // 2) ACL：区分"私有 ACL 挡住匿名"与"策略挡住"
            try {
                var acl = client.getBucketAcl(bucket);
                System.out.println("--- Bucket ACL ---");
                System.out.println("CannedACL = " + acl.getCannedACL());
                if (acl.getGrants() != null) {
                    acl.getGrants().forEach(g -> System.out.println("  grant: "
                            + g.getGrantee() + " -> " + g.getPermission()));
                }
            } catch (OSSException e) {
                System.out.printf("--- 读 ACL 失败: %s / %s%n",
                        e.getErrorCode(), oneLine(e.getErrorMessage()));
            }

        } finally {
            client.shutdown();
        }

        assertThat(true).isTrue();
    }

    private static String oneLine(String text) {
        return text == null ? "(无描述)" : text.replaceAll("\\s+", " ");
    }
}
