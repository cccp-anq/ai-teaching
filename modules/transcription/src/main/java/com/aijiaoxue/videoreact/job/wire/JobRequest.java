package com.aijiaoxue.videoreact.job.wire;

import java.util.List;

/**
 * 提交转写任务时的请求体，对应契约 §3 的 JobRequest。
 *
 * <p>字段全部按契约命名与语义：
 * <ul>
 *   <li>{@code schemaVersion} 固定 {@code "1.0"}；不支持的大版本返回 422；</li>
 *   <li>所有 ID 都是<b>不透明字符串</b>，模块不按姓名/课程标题关联；</li>
 *   <li>{@code media.sourceUrl} 是短期受控下载地址，<b>不得出现在日志或结果里</b>；</li>
 *   <li>{@code analysisRange} 与原媒体毫秒轴一致，区间为左闭右开。</li>
 * </ul>
 *
 * @param schemaVersion   wire 版本，固定 1.0
 * @param clientRequestId 主项目产生的提交幂等标识（1～128 字符）
 * @param moduleType      必须为 TRANSCRIPTION
 * @param classSessionId  课堂会话 ID
 * @param mediaId         媒体 ID
 * @param media           媒体描述
 * @param analysisRange   本次处理范围
 * @param parameters      转写专用参数
 */
public record JobRequest(
        String schemaVersion,
        String clientRequestId,
        String moduleType,
        String classSessionId,
        String mediaId,
        MediaRef media,
        AnalysisRange analysisRange,
        TranscriptionParameters parameters) {

    /**
     * 媒体描述。
     *
     * @param sha256       原媒体字节的 SHA-256（小写 64 位十六进制）
     * @param durationMs   原媒体时长（正整数毫秒）
     * @param mimeType     媒体 MIME 类型
     * @param sourceUrl    HTTPS 短期受控下载地址
     * @param urlExpiresAt 下载地址过期时间（UTC 带 Z）
     */
    public record MediaRef(
            String sha256,
            Long durationMs,
            String mimeType,
            String sourceUrl,
            String urlExpiresAt) {
    }

    /**
     * 处理范围，左闭右开。
     *
     * @param startMs 起始毫秒
     * @param endMs   结束毫秒
     */
    public record AnalysisRange(Long startMs, Long endMs) {
    }

    /**
     * 转写专用参数（契约 §2）。
     *
     * @param language             BCP 风格语言标签或 auto；首版支持 zh-CN / zh / en / auto 等
     * @param enableSpeakerLabels  首版固定 false；true 且不支持时必须 422，不得静默冒充
     */
    public record TranscriptionParameters(
            String language,
            Boolean enableSpeakerLabels) {
    }

    /**
     * 供日志与错误信息使用的脱敏描述：只保留 host，绝不带签名查询串。
     *
     * @return 形如 {@code media.example.com}
     */
    public String safeMediaHost() {
        if (media == null || media.sourceUrl() == null) {
            return "(no-media)";
        }
        try {
            java.net.URI uri = java.net.URI.create(media.sourceUrl());
            return uri.getHost() == null ? "(unknown-host)" : uri.getHost();
        } catch (RuntimeException e) {
            return "(unparsable-url)";
        }
    }

    /** 便于日志的字段清单（不含任何 URL 与 hash 全文）。 */
    public List<String> logFields() {
        return List.of(
                "clientRequestId=" + clientRequestId,
                "classSessionId=" + classSessionId,
                "mediaId=" + mediaId,
                "durationMs=" + (media == null ? null : media.durationMs()),
                "range=[" + (analysisRange == null ? "?" : analysisRange.startMs() + "," 
                        + (analysisRange == null ? "?" : analysisRange.endMs())) + ")",
                "language=" + (parameters == null ? null : parameters.language()),
                "mediaHost=" + safeMediaHost());
    }
}
