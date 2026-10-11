package com.aijiaoxue.classroom;

import java.nio.file.Path;

interface ClassroomMediaProbe {
    ProbeResult probe(Path mediaFile);

    record ProbeResult(long durationMs, boolean hasVideo, boolean hasAudio) {}
}
