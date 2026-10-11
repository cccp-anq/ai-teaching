package com.aijiaoxue.classroom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class FfprobeClassroomMediaProbe implements ClassroomMediaProbe {
    private static final int MAX_OUTPUT_BYTES = 65_536;
    private final ClassroomAnalysisProperties properties;
    private final ObjectMapper objectMapper;

    FfprobeClassroomMediaProbe(ClassroomAnalysisProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProbeResult probe(Path mediaFile) {
        String executable = properties.getFfprobePath();
        int timeoutSeconds = properties.getMediaProbeTimeoutSeconds();
        if (executable.isBlank() || timeoutSeconds < 1) {
            throw unavailable("媒体探测配置无效");
        }
        Path configuredExecutable;
        try {
            configuredExecutable = Path.of(executable);
        } catch (RuntimeException failure) {
            throw unavailable("媒体探测配置无效");
        }
        // 显式绝对路径无需交给操作系统做 PATH 搜索。先校验可以避免 Windows
        // CreateProcess 在失败路径上短暂占用父目录，也能更快给出稳定的 503。
        if (configuredExecutable.isAbsolute() && !Files.isRegularFile(configuredExecutable)) {
            throw unavailable("媒体探测工具不可用");
        }
        List<String> command = List.of(
                executable,
                "-v", "error",
                "-show_entries", "format=duration",
                "-show_entries", "stream=codec_type:stream_disposition=attached_pic",
                "-of", "json",
                mediaFile.toAbsolutePath().toString());

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException failure) {
            throw unavailable("媒体探测工具不可用");
        }

        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        Thread stdoutThread = collectorThread(stdout, "classroom-ffprobe-stdout");
        Thread stderrThread = collectorThread(stderr, "classroom-ffprobe-stderr");
        stdoutThread.start();
        stderrThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            terminate(process, stdoutThread, stderrThread, mediaFile);
            throw unavailable("媒体探测被中断");
        }
        if (!finished) {
            terminate(process, stdoutThread, stderrThread, mediaFile);
            throw new ClassroomAnalysisException("MEDIA_PROBE_TIMEOUT", "视频媒体探测超时", 503);
        }
        if (!join(stdoutThread) || !join(stderrThread)) {
            terminate(process, stdoutThread, stderrThread, mediaFile);
            throw unavailable("媒体探测输出读取失败");
        }
        if (process.exitValue() != 0 || stdout.exceeded() || stderr.exceeded()) {
            throw decodeFailed();
        }
        return parseProbe(stdout.text(), objectMapper);
    }

    static ProbeResult parseProbe(String output, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(output);
        } catch (Exception failure) {
            throw decodeFailed();
        }
        try {
            JsonNode formatNode = root.path("format");
            if (!formatNode.hasNonNull("duration")) throw new NumberFormatException();
            JsonNode durationNode = formatNode.get("duration");
            String raw = durationNode.stringValue();
            if (raw == null || raw.isBlank()) throw new NumberFormatException();
            long durationMs = new BigDecimal(raw).multiply(BigDecimal.valueOf(1_000))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (durationMs <= 0) throw new NumberFormatException();

            boolean hasVideo = false;
            boolean hasAudio = false;
            for (JsonNode stream : root.path("streams")) {
                String type = stream.hasNonNull("codec_type")
                        ? stream.get("codec_type").stringValue() : null;
                // An embedded cover image is a video-typed stream, but not a
                // classroom recording with frames across the media timeline.
                JsonNode attachedPic = stream.path("disposition").path("attached_pic");
                hasVideo |= "video".equals(type)
                        && !(attachedPic.isNumber() && attachedPic.intValue() == 1);
                hasAudio |= "audio".equals(type);
            }
            return new ProbeResult(durationMs, hasVideo, hasAudio);
        } catch (RuntimeException failure) {
            throw decodeFailed();
        }
    }

    private static Thread collectorThread(StreamCollector collector, String name) {
        Thread thread = new Thread(collector, name);
        thread.setDaemon(true);
        return thread;
    }

    private static boolean join(Thread thread) {
        try {
            thread.join(2_000);
            return !thread.isAlive();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void terminate(Process process, Thread stdoutThread, Thread stderrThread, Path mediaFile) {
        boolean interrupted = Thread.interrupted();
        process.destroyForcibly();
        try {
            // destroyForcibly initiates termination; it does not guarantee the
            // child has released the uploaded file before the caller deletes it.
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.onExit().thenRun(() -> deleteQuietly(mediaFile));
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            process.onExit().thenRun(() -> deleteQuietly(mediaFile));
        }
        try {
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (IOException ignored) {
        }
        join(stdoutThread);
        join(stderrThread);
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void deleteQuietly(Path path) {
        try { java.nio.file.Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static ClassroomAnalysisException unavailable(String message) {
        return new ClassroomAnalysisException("MEDIA_PROBE_UNAVAILABLE", message, 503);
    }

    private static ClassroomAnalysisException decodeFailed() {
        return new ClassroomAnalysisException("VIDEO_DECODE_FAILED",
                "无法解析视频媒体，文件可能损坏或编码不受支持", 422);
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile boolean exceeded;

        private StreamCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4_096];
            try (input) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int remaining = MAX_OUTPUT_BYTES - output.size();
                    if (remaining > 0) output.write(buffer, 0, Math.min(remaining, count));
                    if (count > remaining) exceeded = true;
                }
            } catch (IOException ignored) {
                // Process termination closes the pipes; callers handle timeout/exit state.
            }
        }

        boolean exceeded() { return exceeded; }
        String text() { return output.toString(StandardCharsets.UTF_8); }
    }
}
