package com.aijiaoxue.videoreact.asr.domain;

/**
 * 一条转写分段，对应 {@code transcript_segment} 表，也是对外 API 的最小单元。
 *
 * @param segmentIndex 分段序号，从 0 开始，按时间升序
 * @param startMs      起始毫秒
 * @param endMs        结束毫秒
 * @param text         分段文本（腾讯的 FinalSentence）
 * @param speakerId    说话人 ID，未开启说话人分离时为 null
 */
public record TranscriptSegment(
        int segmentIndex,
        long startMs,
        long endMs,
        String text,
        Long speakerId) {

    /**
     * 生成 {@code HH:MM:SS} 形式的时间轴标签，便于前端直接展示。
     */
    public String timeLabel() {
        long totalSeconds = Math.max(0L, startMs / 1000L);
        return String.format("%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }
}
