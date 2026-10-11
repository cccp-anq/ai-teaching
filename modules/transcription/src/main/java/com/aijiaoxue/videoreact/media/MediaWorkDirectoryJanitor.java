package com.aijiaoxue.videoreact.media;

import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

/** Removes stale job-owned media directories left by a killed process. */
@Component
public class MediaWorkDirectoryJanitor {
    private static final Logger log = LoggerFactory.getLogger(MediaWorkDirectoryJanitor.class);
    private final MediaProperties properties;
    @Value("${videoreact.media.orphan-retention-hours:24}") private long retentionHours = 24;

    public MediaWorkDirectoryJanitor(MediaProperties properties) { this.properties = properties; }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { cleanup(); }

    @Scheduled(fixedDelayString = "${videoreact.media.cleanup-interval-ms:3600000}")
    public void cleanup() {
        Path root = Path.of(properties.workDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        Instant deadline = Instant.now().minus(Duration.ofHours(Math.max(1, retentionHours)));
        try (var children = Files.list(root)) {
            children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> olderThan(path, deadline))
                    .forEach(path -> deleteTree(root, path));
        } catch (IOException e) {
            log.warn("媒体孤儿目录扫描失败: {}", e.getClass().getSimpleName());
        }
    }

    private static boolean olderThan(Path path, Instant deadline) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(deadline); }
        catch (IOException e) { return false; }
    }

    private static void deleteTree(Path root, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) return;
        try (var paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException e) {
            log.warn("媒体孤儿目录清理未完成: {}", normalized.getFileName());
        }
    }
}
