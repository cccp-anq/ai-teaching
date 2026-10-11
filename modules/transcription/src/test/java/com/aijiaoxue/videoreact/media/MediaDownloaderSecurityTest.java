package com.aijiaoxue.videoreact.media;

import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaDownloaderSecurityTest {
    @Test
    void loopbackDevelopmentFlagDoesNotPermitIpv6UniqueLocalNetworks() {
        MediaDownloader downloader = new MediaDownloader(properties(true));
        assertThrows(MediaDownloader.MediaDownloadException.class,
                () -> downloader.parseAndValidate("http://[fd00::1]/classroom.mp4"));
    }

    @Test
    void loopbackDevelopmentFlagStillPermitsLiteralLoopback() {
        new MediaDownloader(properties(true)).parseAndValidate("http://127.0.0.1/classroom.mp4");
    }

    private static MediaProperties properties(boolean allowLoopback) {
        return new MediaProperties(".work/media", "ffmpeg", "ffprobe", 10, 60, 30,
                1024, 60_000, List.of(), allowLoopback, 2_000, false);
    }
}
