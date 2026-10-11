package com.aijiaoxue.videoreact.asr.service;

import com.aijiaoxue.videoreact.asr.client.AsrProviderClient;
import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import com.aijiaoxue.videoreact.asr.staging.AudioStaging;
import com.aijiaoxue.videoreact.asr.staging.StagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * ASR 配置自检：启动时把"这个部署到底能不能转写"讲清楚。
 *
 * <p><b>为什么值得单独一个类</b>：本模块有三种失败模式长得几乎一样，
 * 但补救动作完全不同——
 * <ol>
 *   <li>凭证缺失（缺 API Key）；</li>
 *   <li>提供方需要的通道没有实现（阿里云只收公网 URL，而暂存尚未配置）；</li>
 *   <li>提供方与提交方式不匹配（内联音频发给了 URL-only 的引擎）。</li>
 * </ol>
 * 三者都要等到<b>第一个真实任务跑到提交阶段</b>才会暴露，而那时用户已经在等结果了。
 * 启动时打一遍日志，把"配了什么、缺什么、缺了会怎样"一次说清，
 * 排查路径就从"翻日志找异常"缩短成"看启动那几行"。
 *
 * <p><b>只告警，不阻止启动</b>：这是与 {@code JobService} 里
 * "凭证不全时提交也照常返回 202"一致的选择——模块先起着，
 * 让 {@code /health} 和任务错误信息去解释原因，
 * 比让整个容器因为一个环境变量没配而起不来更有用。
 * 生产环境要的是失败关闭，那是网关鉴权（{@code MODULE_BEARER_TOKENS} 留空即全量 401）的职责。
 */
public class AsrStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(AsrStartupCheck.class);

    private final AsrProviderClient providerClient;
    private final AudioStaging audioStaging;
    private final AsrProperties properties;
    private final StagingProperties stagingProperties;

    public AsrStartupCheck(
            AsrProviderClient providerClient,
            AudioStaging audioStaging,
            AsrProperties properties,
            StagingProperties stagingProperties) {
        this.providerClient = providerClient;
        this.audioStaging = audioStaging;
        this.properties = properties;
        this.stagingProperties = stagingProperties;
    }

    /**
     * 应用就绪时输出一行结论加若干条待办。
     *
     * @param event 就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void report(ApplicationReadyEvent event) {
        log.info("ASR 提供方: {} / 模型 {}（内联音频: {}，URL 暂存: {}，轮询上限 {}）",
                providerClient.providerName(),
                providerClient.modelIdentifier(),
                providerClient.acceptsInlineAudio() ? "支持" : "不支持",
                audioStaging.description(),
                properties.poll() == null ? "(未配置)" : properties.poll().timeout());

        List<String> hints = providerClient.configurationHints();
        for (String hint : hints) {
            log.warn("ASR 配置待办: {}", hint);
        }

        reportStaging();

        long maxBytes = providerClient.maxInputUrlBytes();
        if (maxBytes != Long.MAX_VALUE) {
            log.info("提供方对提交文件的大小上限: {} 字节（本地下载上限见 videoreact.media.max-download-bytes）",
                    maxBytes);
        }
    }

    /**
     * 暂存配置自检。
     *
     * <p>分三种情况，说清三种不同的话：
     * <ol>
     *   <li>选了 {@code oss} 但必填项没齐 → 列出缺哪几项（而不是笼统说"配置不完整"）；</li>
     *   <li>选了 {@code oss} 且字段齐了，但<b>实现还没写</b> → 必须明说，
     *       否则使用者会以为自己已经配好了，然后困惑于任务为什么还是失败。
     *       这是本方法存在的主要理由——占位配置最危险的失败模式就是"看起来配好了"；</li>
     *   <li>提供方只收 URL 而暂存不可用 → 这个组合下<b>每个任务都会失败</b>，用 ERROR 级。</li>
     * </ol>
     */
    private void reportStaging() {
        boolean ossSelected = stagingProperties != null && stagingProperties.isOss();

        if (ossSelected) {
            List<String> missing = stagingProperties.missingOssFields();
            if (!missing.isEmpty()) {
                log.warn("暂存配置待办: 选了 OSS 暂存，但 {} 尚未配置", String.join("、", missing));
            }
            if (audioStaging.urlStagingAvailable()) log.info("OSS 音频暂存可用；须配置私有桶、生命周期清理和最小权限");

        }

        // 提供方只收 URL 而暂存拿不出 URL —— 该组合下所有任务都会失败
        if (!providerClient.acceptsInlineAudio() && !audioStaging.urlStagingAvailable()) {
            log.error("ASR 配置不可用：提供方 {} 只接受公网 URL（不接受内联音频），"
                            + "但当前 AudioStaging 是「{}」，无法产出 URL。"
                            + "在此状态下每个转写任务都会以 PROCESSING_FAILED 结束。"
                            + "请配置 OSS 私有音频暂存，或改用支持内联音频的提供方",
                    providerClient.providerName(), audioStaging.description());
        }
    }
}
