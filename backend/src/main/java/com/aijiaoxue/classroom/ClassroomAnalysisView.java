package com.aijiaoxue.classroom;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ClassroomAnalysisView(
        long classSessionId, long offeringId, long scheduleId, LocalDate sessionDate, String title,
        LocalDateTime createdAt, List<MediaView> media) {
    public ClassroomAnalysisView { media = List.copyOf(media); }
    public record MediaView(long mediaId, String originalFilename, String contentType, long byteSize,
                            String sha256, long durationMs, String status, String playbackUrl,
                            List<JobView> moduleJobs, ReportView report) {
        public MediaView { moduleJobs = List.copyOf(moduleJobs); }
    }
    public record JobView(long jobId, String moduleType, String status, String providerId,
                          String moduleVersion, Integer resultRevision, String errorCode, String errorMessage) {}
    public record SegmentView(String id, long startMs, long endMs, String text) {}
    public record WindowView(String id, long startMs, long endMs, String quality, String qualityReason,
                             Integer headUpCount, Integer validObservedCount, Double headUpRate) {}
    public record ReportView(Long reportId, String status, String dataMode, Double meanHeadUpRate,
                             Double validCoverageRate, Integer estimatedAttendanceCount,
                             int attendanceObservedWindows, String attendanceConfidence,
                             String summary, String analysis,
                             Long evidenceStartMs, Long evidenceEndMs, String errorMessage,
                             List<SegmentView> transcript, List<WindowView> headUpWindows) {
        public ReportView { transcript = List.copyOf(transcript); headUpWindows = List.copyOf(headUpWindows); }
    }
}
