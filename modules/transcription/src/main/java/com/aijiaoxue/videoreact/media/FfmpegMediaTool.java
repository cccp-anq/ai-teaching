package com.aijiaoxue.videoreact.media;

import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg / ffprobe 封装：媒体探测与音轨提取。
 *
 * <p><b>时间轴为什么必须保持 1:1</b>：契约 §1 要求「允许模块内部提取音轨或做单段裁剪，
 * 但返回时间必须已经映射回原媒体」。本实现提取的是<b>整条音轨</b>
 * （不做片段裁剪），因此原媒体毫秒 ≡ 音频毫秒，映射是恒等的、不需要猜偏移。
 * 如果以后支持单段裁剪，映射公式是「原媒体毫秒 = 处理片段毫秒 + offsetMs」，
 * 那时必须显式记录 offset，而不能沿用恒等映射。
 *
 * <p><b>抽取格式选 16k 单声道 WAV 而不是 MP3</b>：WAV 是无损且无编码延迟，
 * 不会因为编码器 priming 采样让首段整体前移几十毫秒；而 TokenHub 走 base64
 * 内联通道时体积也可控（60 秒 ≈ 1.9MB）。若以后要处理长媒体再改 MP3 并记录偏移。
 *
 * <p>ffmpeg 与 ffprobe 都是<b>外部进程</b>，本类负责：超时、非交互模式、
 * 把 stderr 收敛成可读错误，并且绝不把完整 sourceUrl 写进日志。
 */
public class FfmpegMediaTool {

    private static final Logger log = LoggerFactory.getLogger(FfmpegMediaTool.class);

    /** 交给 ASR 的音频规格：16kHz 单声道，符合主流 ASR 的输入要求。 */
    private static final int ASR_SAMPLE_RATE = 16000;

    private static final int ASR_CHANNELS = 1;

    private final MediaProperties properties;
    private final ObjectMapper objectMapper;
    @Value("${videoreact.media.extract-timeout-seconds:7200}")
    private int extractTimeoutSeconds = 7200;

    public FfmpegMediaTool(MediaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 媒体探测结果。
     *
     * @param durationMs   容器声明的时长（毫秒）
     * @param hasAudio     是否存在音轨
     * @param audioCodec   音轨编码名，无音轨时为 null
     * @param formatName   容器格式名
     */
    public record ProbeResult(long durationMs, boolean hasAudio, String audioCodec, String formatName, boolean hasVideo, int width, int height) {
        public ProbeResult(long durationMs, boolean hasAudio, String audioCodec, String formatName, boolean hasVideo) { this(durationMs, hasAudio, audioCodec, formatName, hasVideo,0,0); }
        public ProbeResult(long durationMs, boolean hasAudio, String audioCodec, String formatName) { this(durationMs, hasAudio, audioCodec, formatName, true,0,0); }
    }

    /** 外部工具执行失败（含 ffmpeg 不存在、超时、无法解码）。 */
    public static class MediaToolException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;
        private final boolean retryable;

        public MediaToolException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /**
     * 探测媒体：时长与音轨信息。
     *
     * @param mediaFile 本地媒体文件
     * @return 探测结果
     * @throws MediaToolException 无法探测或无法解码时抛出
     */
    public ProbeResult probe(Path mediaFile) {
        List<String> command = List.of(
                properties.ffprobePath(),
                "-v", "error",
                "-show_entries", "format=duration,format_name",
                "-show_entries", "stream=codec_type,codec_name,width,height",
                "-of", "json",
                mediaFile.toAbsolutePath().toString());

        String stdout = run(command, "ffprobe", properties.probeTimeoutSeconds());

        JsonNode root;
        try {
            root = objectMapper.readTree(stdout);
        } catch (Exception e) {
            throw new MediaToolException("DECODE_FAILED", "无法解析 ffprobe 输出", false);
        }

        long durationMs = 0;
        JsonNode format = root.path("format");
        if (format.hasNonNull("duration")) {
            try {
                // stringValue() 替代已弃用的 asText()；ffprobe 把 duration 输出为字符串
                durationMs = Math.round(Double.parseDouble(format.get("duration").stringValue()) * 1000.0);
            } catch (NumberFormatException ignored) {
                durationMs = 0;
            }
        }
        String formatName = format.hasNonNull("format_name") ? format.get("format_name").stringValue() : null;

        boolean hasAudio = false;
        boolean hasVideo = false;
        int width=0,height=0;
        String audioCodec = null;
        for (JsonNode stream : root.path("streams")) {
            if ("video".equals(stream.path("codec_type").stringValue())) {hasVideo = true;width=Math.max(width,stream.path("width").asInt(0));height=Math.max(height,stream.path("height").asInt(0));}
            // path() 在字段缺失时返回 MissingNode：stringValue() 给 null，asText() 给 ""，
            // 但下面用的是 "audio".equals(...)，两者都为 false，行为不变
            if ("audio".equals(stream.path("codec_type").stringValue())) {
                hasAudio = true;
                audioCodec = stream.hasNonNull("codec_name") ? stream.get("codec_name").stringValue() : null;

            }
        }
        if (durationMs <= 0) {
            throw new MediaToolException("DECODE_FAILED",
                    "无法从媒体容器读出有效时长，可能是损坏或不支持的格式", false);
        }
        return new ProbeResult(durationMs, hasAudio, audioCodec, formatName, hasVideo,width,height);
    }

    /**
     * 提取整条音轨为 16k 单声道 WAV。
     *
     * <p>使用 {@code -vn}（丢弃视频）与 {@code -t} 不设限（整条音轨），
     * 因此输出音频与原媒体<b>等长同轴</b>。
     *
     * @param mediaFile 本地媒体文件
     * @param outputDir 输出目录
     * @return 生成的 WAV 文件
     * @throws MediaToolException 无音轨或抽取失败时抛出
     */
    public Path extractAudio(Path mediaFile, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new MediaToolException("PROCESSING_FAILED", "无法创建音频目录", true);
        }
        Path output = outputDir.resolve("audio-" + System.nanoTime() + ".wav");

        List<String> command = List.of(
                properties.ffmpegPath(),
                "-hide_banner",
                "-nostdin",
                "-v", "error",
                "-y",
                "-i", mediaFile.toAbsolutePath().toString(),
                "-vn",
                "-ac", String.valueOf(ASR_CHANNELS),
                "-ar", String.valueOf(ASR_SAMPLE_RATE),
                "-c:a", "pcm_s16le",
                "-f", "wav",
                output.toAbsolutePath().toString());

        run(command, "ffmpeg", extractTimeoutSeconds);

        if (!Files.exists(output)) {
            throw new MediaToolException("PROCESSING_FAILED", "ffmpeg 未生成音频文件", true);
        }
        try {
            if (Files.size(output) <= 44) {
                // 44 字节是 WAV 头，只有头说明没有有效音频数据
                Files.deleteIfExists(output);
                throw new MediaToolException("UNSUPPORTED_MEDIA", "媒体中没有可用的音频内容", false);
            }
        } catch (IOException e) {
            throw new MediaToolException("PROCESSING_FAILED", "无法校验生成的音频文件", true);
        }
        return output;
    }

    /**
     * 执行外部命令并返回 stdout。
     *
     * <p>命令与参数直接传数组，不经过 shell，因此路径里的空格与特殊字符不会被解释。
     *
     * @param command 命令与参数
     * @param toolName 用于报错的工具名
     * @return stdout 文本
     * @throws MediaToolException 工具不存在、超时、非零退出
     */
    private String run(List<String> command, String toolName, int timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new MediaToolException("PROCESSING_FAILED",
                    toolName + " 无法启动（请检查 videoreact.media." + toolName
                            + "-path 配置或安装）: " + e.getClass().getSimpleName(), false);
        }

        // stderr/stdout 必须并发读取：任何一路写满管道缓冲区都会让子进程阻塞死锁
        StreamCollector out = new StreamCollector(process.getInputStream());
        StreamCollector err = new StreamCollector(process.getErrorStream());
        Thread outThread = new Thread(out, toolName + "-stdout");
        Thread errThread = new Thread(err, toolName + "-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        boolean finished;
        try {
            finished = process.waitFor(
                    Math.max(10, timeoutSeconds), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new MediaToolException("PROCESSING_FAILED", toolName + " 被中断", true);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new MediaToolException("PROCESSING_FAILED",
                    toolName + " 超过 " + timeoutSeconds + " 秒未完成，已终止", true);
        }
        joinQuietly(outThread);
        joinQuietly(errThread);

        if (process.exitValue() != 0) {
            String detail = err.text().isBlank() ? out.text() : err.text();
            throw new MediaToolException("DECODE_FAILED",
                    toolName + " 退出码 " + process.exitValue() + ": " + lastLines(detail, 3), false);
        }
        return out.text();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 只保留最后几行：ffmpeg 的错误说明通常在末尾，前面是进度噪声。 */
    private static String lastLines(String text, int count) {
        if (text == null || text.isBlank()) {
            return "(无输出)";
        }
        String[] lines = text.strip().split("\n");
        int from = Math.max(0, lines.length - count);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append(lines[i].strip());
        }
        return sb.length() > 500 ? sb.substring(0, 500) + "…" : sb.toString();
    }

    /** 独立的收集线程，避免父子进程因管道缓冲区满而死锁。 */
    private static final class StreamCollector implements Runnable {

        private final java.io.InputStream stream;
        private final StringBuilder buffer = new StringBuilder();

        StreamCollector(java.io.InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 进程结束时管道关闭属正常
            }
        }

        String text() {
            return buffer.toString();
        }
    }

    /**
     * 检查 ffmpeg/ffprobe 是否可用，供启动自检使用。
     *
     * @return 可用时返回 null，否则返回可读原因
     */
    public String checkTools() {
        for (String tool : List.of(properties.ffmpegPath(), properties.ffprobePath())) {
            try {
                Process process = new ProcessBuilder(tool, "-version")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return tool + " 启动后 15 秒无响应";
                }
                if (process.exitValue() != 0) {
                    return tool + " 返回退出码 " + process.exitValue();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return tool + " 检查被中断";
            } catch (IOException e) {
                return tool + " 不可执行：" + e.getClass().getSimpleName();
            }
        }
        return null;
    }

    /** 供日志展示的音频规格。 */
    public String audioSpec() {
        return ASR_SAMPLE_RATE / 1000 + "kHz/" + ASR_CHANNELS + "ch/wav";
    }
}
