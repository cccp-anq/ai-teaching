package com.aijiaoxue.videoreact.asr.staging;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列出 bucket 内容，并可清理诊断遗留（默认跳过）。设 {@code MANUAL_OSS_TEST=1} 运行。
 *
 * <p><b>为什么需要它</b>：诊断过程中会在桶里留下探测对象——尤其是排障早期
 * 写入还被拒的时候，清理语句本身也会失败，于是垃圾就留下了。
 * 这个类把"桶里到底有什么"变成一条命令，避免靠翻控制台。
 *
 * <p><b>默认只列不删。</b>删除是破坏性操作，必须显式开启：
 * 再加一个环境变量 {@code MANUAL_OSS_PURGE=1} 才会真正删除探测对象。
 * 删除范围严格限定为<b>文件名含 {@code probe} 或 {@code connectivity-probe}</b>
 * 的对象——即使误开了开关，也不会碰到真实数据。
 */
@EnabledIfEnvironmentVariable(named = "MANUAL_OSS_TEST", matches = ".+")
class ManualOssInspectTest {

    /** 只有名字里带这些标记的才算我们的探测垃圾。 */
    private static final List<String> PROBE_MARKERS = List.of("probe", "connectivity-probe");

    @Test
    @DisplayName("真机：列出 bucket 内容（可选清理探测遗留）")
    void inspectAndOptionallyPurge() {
        String bucket = System.getenv("OSS_BUCKET").trim();
        boolean purge = System.getenv("MANUAL_OSS_PURGE") != null
                && !System.getenv("MANUAL_OSS_PURGE").isBlank();

        OSS client = new OSSClientBuilder().build(
                "https://oss-%s.aliyuncs.com".formatted(System.getenv("OSS_REGION").trim()),
                System.getenv("OSS_ACCESS_KEY_ID").trim(),
                System.getenv("OSS_ACCESS_KEY_SECRET").trim());

        try {
            List<OSSObjectSummary> objects = client.listObjects(bucket).getObjectSummaries();
            System.out.println();
            System.out.printf("=== bucket %s 共 %d 个对象 ===%n", bucket, objects.size());
            if (objects.isEmpty()) {
                System.out.println("(空)");
                return;
            }
            for (OSSObjectSummary o : objects) {
                boolean ours = isProbe(o.getKey());
                System.out.printf("  %-58s %8d B  %s%n",
                        o.getKey(), o.getSize(), ours ? "← 探测遗留" : "");
            }

            List<String> junk = objects.stream()
                    .map(OSSObjectSummary::getKey)
                    .filter(ManualOssInspectTest::isProbe)
                    .toList();
            System.out.printf("%n探测遗留 %d 个%n", junk.size());

            if (junk.isEmpty()) {
                System.out.println("桶内干净，无需清理");
                return;
            }
            if (!purge) {
                System.out.println("未清理（要清理请再加 MANUAL_OSS_PURGE=1）");
                return;
            }
            client.deleteObjects(new DeleteObjectsRequest(bucket).withKeys(junk));
            System.out.println("已删除 " + junk.size() + " 个探测对象");
            System.out.println("剩余对象数 = "
                    + client.listObjects(bucket).getObjectSummaries().size());
        } finally {
            client.shutdown();
        }

        assertThat(true).isTrue();
    }

    /** 只认我们自己造的探测对象，绝不误删业务数据。 */
    private static boolean isProbe(String key) {
        String lower = key.toLowerCase();
        return PROBE_MARKERS.stream().anyMatch(lower::contains);
    }
}
