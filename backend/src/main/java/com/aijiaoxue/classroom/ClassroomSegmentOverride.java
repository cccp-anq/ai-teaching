package com.aijiaoxue.classroom;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S2-10 调整片段标题与边界：督导对 AI 片段划分的修正。
 *
 * <p>片段在本项目里不是独立实体，它就是生成报告 {@code topics[]} 的三元组
 * {@code {startMs,endMs,summary}}。报告是不可变事实，所以修正不能原地改写报告，
 * 而是在报告之上叠一层<b>可审计的覆盖</b>：绑定到具体的报告版本（AI 重算不继承），
 * 并记录修改人与修订号。
 *
 * <p>错误码与前端 {@code lib/segmentDirectory.ts} 的 {@code SegmentEditCode} 一一对应，
 * 且校验顺序固定（未知片段 → 标题 → 区间 → 越界 → 重叠），避免"前端提示合法、服务端拒绝"。
 */
public final class ClassroomSegmentOverride {
    public static final String CODE_TITLE_REQUIRED = "SEGMENT_TITLE_REQUIRED";
    public static final String CODE_RANGE_INVALID = "SEGMENT_RANGE_INVALID";
    public static final String CODE_OUT_OF_MEDIA = "SEGMENT_OUT_OF_MEDIA";
    public static final String CODE_OVERLAP = "SEGMENT_OVERLAP";
    public static final String CODE_UNKNOWN = "SEGMENT_UNKNOWN";
    /** 报告尚未生成时目录不可用；不属于"修改被拒"，因此单独一个码。 */
    public static final String CODE_REPORT_NOT_READY = "SEGMENT_REPORT_NOT_READY";
    public static final int TITLE_MAX = 200;

    private ClassroomSegmentOverride() {}

    /** 报告里的原始片段。{@code key} 为 {@code "topic-<index>"}，与前端目录共用同一套 key。 */
    public record ReportSegment(String key, int index, String title, long startMs, long endMs,
                                int evidenceCount) {}

    /**
     * 目录里的一条片段：存在覆盖时呈现覆盖后的值，同时保留原始值，界面才能显示"改过什么"。
     */
    public record Segment(String key, int index, String title, long startMs, long endMs, long durationMs,
                          int evidenceCount, boolean overridden, String originalTitle, long originalStartMs,
                          long originalEndMs, Long editedByUserId, Integer revision, LocalDateTime updatedAt) {}

    /**
     * S2-10 的目录响应。{@code segments} 已按生效区间升序排列（与前端 segmentDirectory 同序），
     * 界面可以直接使用，不需要再排一次（两处排序规则不一致就会产生"看到的顺序和提示的片段号不一致"）。
     */
    public record Directory(long reportId, long mediaId, long mediaDurationMs, List<Segment> segments) {}

    /** 已落库的覆盖行。 */
    public record Stored(long id, long reportId, long mediaId, String segmentKey, String title, long startMs,
                         long endMs, long editedByUserId, int revision, LocalDateTime updatedAt) {}
}
