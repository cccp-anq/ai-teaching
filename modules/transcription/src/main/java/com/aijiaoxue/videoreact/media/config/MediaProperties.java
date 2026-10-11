package com.aijiaoxue.videoreact.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 媒体受控下载与音轨提取配置，前缀 {@code videoreact.media}。
 *
 * <p>契约要求「双方实现请求/结果体积、media 时长/大小、片段条数及并发上限并在部署交接表填数值」，
 * 这里的取值就是交接表里的数值来源。
 *
 * @param workDir                媒体与音频临时目录
 * @param ffmpegPath             ffmpeg 可执行文件路径
 * @param ffprobePath            ffprobe 可执行文件路径
 * @param connectTimeoutSeconds  连接超时（秒）
 * @param downloadTimeoutSeconds 整体下载超时（秒）
 * @param probeTimeoutSeconds    探测/抽音轨超时（秒）
 * @param maxDownloadBytes       单次下载最大字节数；超过返回 413/PAYLOAD_TOO_LARGE
 * @param maxDurationMs          允许的最大媒体时长（毫秒）；超过返回 422
 * @param hostAllowList          下载主机白名单；为空表示不限制（仍拒绝内网地址）
 * @param allowLoopbackHttp      是否允许 http + 环回地址（仅供本地联调，生产必须 false）
 * @param durationToleranceMs    媒体实际时长与登记 durationMs 的可接受偏差；
 *                               超出只产生 warning，不静默裁切（契约明确要求）
 * @param keepMediaAndAudio      是否保留下载的媒体与提取的音频（排错用）
 */
@ConfigurationProperties(prefix = "videoreact.media")
public record MediaProperties(
        String workDir,
        String ffmpegPath,
        String ffprobePath,
        int connectTimeoutSeconds,
        int downloadTimeoutSeconds,
        int probeTimeoutSeconds,
        long maxDownloadBytes,
        long maxDurationMs,
        List<String> hostAllowList,
        boolean allowLoopbackHttp,
        long durationToleranceMs,
        boolean keepMediaAndAudio) {
}
