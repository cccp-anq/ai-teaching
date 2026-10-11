package com.aijiaoxue.classroom;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S2-10 调整片段标题与边界：督导修正 AI 的片段划分，修正结果落在覆盖层，报告本体不动。
 *
 * <p>五条规则<b>必须由服务端强制</b>（前端只做提示，绕过前端才是常态）：
 * 未知片段拒绝、标题非空且不超长、区间合法（{@code end>start}）、不超媒体时长、同报告内不重叠。
 * 校验顺序固定为 未知 → 标题 → 区间 → 越界 → 重叠，与前端 {@code validateSegmentEdit} 一致，
 * 这样同一份输入在两边得到同一个错误码。
 *
 * <p>读目录与写覆盖都要求督导身份（与 S2-01 的目录、S2-03 的复核底稿同一口径）：
 * 覆盖是督导对划分的修正，未经过复核流程前不应被教师当成"报告里就是这么分的"。
 */
@Service
public class ClassroomSegmentService {
    private final ClassroomSegmentOverrideStore overrides;
    private final ClassroomGeneratedReportStore generatedReports;
    private final ClassroomAnalysisService analysis;
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final ObjectMapper json;

    ClassroomSegmentService(ClassroomSegmentOverrideStore overrides,
                            ClassroomGeneratedReportStore generatedReports,
                            ClassroomAnalysisService analysis,
                            CurrentUserProvider currentUsers,
                            PermissionService permissions,
                            ObjectMapper json) {
        this.overrides = overrides;
        this.generatedReports = generatedReports;
        this.analysis = analysis;
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.json = json;
    }

    /** 目录：报告片段叠加已保存的覆盖。没有可用报告版本时返回 null，控制器映射为 204。 */
    @Transactional(readOnly = true)
    public ClassroomSegmentOverride.Directory directory(long mediaId) {
        requireSupervisorReader();
        analysis.requireGeneratedReportViewerFor(mediaId);
        var stored = generatedReports.visibleForMedia(mediaId);
        if (stored == null) return null;
        return build(stored.id(), mediaId, overrides.mediaDurationMs(mediaId),
                reportSegments(stored), overrides.forReport(stored.id()));
    }

    /**
     * 保存一条片段覆盖。已确认（冻结）报告同样允许覆盖：覆盖不改报告本体，冻结快照仍是教师读到的那一版，
     * 覆盖只是督导对划分的修正（决策 DEC-S2-10-01）。
     */
    @Transactional
    public ClassroomSegmentOverride.Directory save(long mediaId, String segmentKey, String title,
                                                   long startMs, long endMs) {
        CurrentUser user = requireSupervisor();
        analysis.requireGeneratedReportViewerFor(mediaId);
        var stored = generatedReports.visibleForMedia(mediaId);
        if (stored == null)
            throw new ClassroomAnalysisException(ClassroomSegmentOverride.CODE_REPORT_NOT_READY,
                    "当前没有可修改片段划分的报告版本", 409);
        long mediaDurationMs = overrides.mediaDurationMs(mediaId);
        List<ClassroomSegmentOverride.ReportSegment> report = reportSegments(stored);
        ClassroomSegmentOverride.Directory current = build(stored.id(), mediaId, mediaDurationMs, report,
                overrides.forReport(stored.id()));

        ClassroomSegmentOverride.Segment target = null;
        for (ClassroomSegmentOverride.Segment segment : current.segments())
            if (segment.key().equals(segmentKey)) target = segment;
        if (target == null)
            throw new ClassroomAnalysisException(ClassroomSegmentOverride.CODE_UNKNOWN,
                    "要修改的片段不在当前报告版本中", 404);

        String cleaned = title == null ? "" : title.trim();
        if (cleaned.isEmpty())
            throw new ClassroomAnalysisException(ClassroomSegmentOverride.CODE_TITLE_REQUIRED,
                    "片段标题不能为空", 400);
        if (cleaned.length() > ClassroomSegmentOverride.TITLE_MAX)
            throw new ClassroomAnalysisException(ClassroomSegmentOverride.CODE_TITLE_REQUIRED,
                    "片段标题超过 " + ClassroomSegmentOverride.TITLE_MAX + " 字", 400);
        if (endMs <= startMs)
            throw new ClassroomAnalysisException(ClassroomSegmentOverride.CODE_RANGE_INVALID,
                    "结束时间必须晚于开始时间", 400);
        if (startMs < 0 || endMs > mediaDurationMs)
            throw new ClassroomAnalysisException(ClassroomSegmentOverride.CODE_OUT_OF_MEDIA,
                    "片段范围超出课堂媒体时长", 400);
        // 区间没变时不再查重叠：AI 报告本身的片段划分允许互相重叠（校验器只保证每条证据落在本片段内），
        // 若对"只改标题"也强制不重叠，督导将无法给一个与邻居重叠的片段改名。
        if ((startMs != target.startMs() || endMs != target.endMs())) {
            ClassroomSegmentOverride.Segment conflict = firstOverlap(current.segments(), segmentKey,
                    startMs, endMs);
            if (conflict != null)
                throw new ClassroomAnalysisException(ClassroomSegmentOverride.CODE_OVERLAP,
                        "与片段 " + (conflict.index() + 1) + " 的时间范围重叠", 409);
        }
        overrides.save(stored.id(), mediaId, segmentKey, cleaned, startMs, endMs, user.userId());
        return build(stored.id(), mediaId, mediaDurationMs, report, overrides.forReport(stored.id()));
    }

    /** 半开区间 [start,end) 相交即重叠；返回最靠前的冲突片段，错误提示才能点名是第几段。 */
    private static ClassroomSegmentOverride.Segment firstOverlap(
            List<ClassroomSegmentOverride.Segment> segments, String key, long startMs, long endMs) {
        ClassroomSegmentOverride.Segment conflict = null;
        for (ClassroomSegmentOverride.Segment segment : segments) {
            if (segment.key().equals(key)) continue;
            // 半开区间：首尾相接（end==start）不算重叠。
            if (startMs >= segment.endMs() || endMs <= segment.startMs()) continue;
            if (conflict == null || segment.startMs() < conflict.startMs()
                    || segment.startMs() == conflict.startMs() && segment.index() < conflict.index())
                conflict = segment;
        }
        return conflict;
    }

    /** 报告里的片段就是 topics[]；key 用下标，与前端 segmentDirectory 的 key 规则一致。 */
    private List<ClassroomSegmentOverride.ReportSegment> reportSegments(
            ClassroomGeneratedReportStore.StoredReport stored) {
        JsonNode report;
        try {
            report = json.readTree(stored.normalizedJson());
        } catch (Exception failure) {
            throw new IllegalStateException("Stored generated report is invalid", failure);
        }
        JsonNode topics = report == null ? null : report.get("topics");
        if (topics == null || !topics.isArray())
            throw new IllegalStateException("Stored generated report has no topics");
        List<ClassroomSegmentOverride.ReportSegment> segments = new ArrayList<>(topics.size());
        for (int index = 0; index < topics.size(); index++) {
            JsonNode topic = topics.get(index);
            JsonNode startMs = topic == null ? null : topic.get("startMs");
            JsonNode endMs = topic == null ? null : topic.get("endMs");
            if (startMs == null || !startMs.isNumber() || endMs == null || !endMs.isNumber())
                throw new IllegalStateException("Stored generated report topic range is invalid");
            JsonNode summary = topic.get("summary");
            JsonNode refs = topic.get("evidenceRefs");
            segments.add(new ClassroomSegmentOverride.ReportSegment("topic-" + index, index,
                    summary != null && summary.isTextual() ? summary.asText() : "",
                    startMs.asLong(), endMs.asLong(), refs != null && refs.isArray() ? refs.size() : 0));
        }
        return List.copyOf(segments);
    }

    /** 目录按生效区间升序（前端 segmentDirectory 同序），覆盖后的值优先。 */
    private static ClassroomSegmentOverride.Directory build(long reportId, long mediaId, long mediaDurationMs,
                                                            List<ClassroomSegmentOverride.ReportSegment> report,
                                                            List<ClassroomSegmentOverride.Stored> saved) {
        Map<String, ClassroomSegmentOverride.Stored> byKey = new HashMap<>();
        for (ClassroomSegmentOverride.Stored row : saved) byKey.put(row.segmentKey(), row);
        List<ClassroomSegmentOverride.Segment> segments = new ArrayList<>(report.size());
        for (ClassroomSegmentOverride.ReportSegment original : report) {
            ClassroomSegmentOverride.Stored override = byKey.get(original.key());
            if (override == null) {
                segments.add(new ClassroomSegmentOverride.Segment(original.key(), original.index(),
                        original.title(), original.startMs(), original.endMs(),
                        original.endMs() - original.startMs(), original.evidenceCount(), false,
                        original.title(), original.startMs(), original.endMs(), null, null, null));
            } else {
                segments.add(new ClassroomSegmentOverride.Segment(original.key(), original.index(),
                        override.title(), override.startMs(), override.endMs(),
                        override.endMs() - override.startMs(), original.evidenceCount(), true,
                        original.title(), original.startMs(), original.endMs(), override.editedByUserId(),
                        override.revision(), override.updatedAt()));
            }
        }
        segments.sort(Comparator.comparingLong(ClassroomSegmentOverride.Segment::startMs)
                .thenComparingLong(ClassroomSegmentOverride.Segment::endMs)
                .thenComparingInt(ClassroomSegmentOverride.Segment::index));
        return new ClassroomSegmentOverride.Directory(reportId, mediaId, mediaDurationMs, List.copyOf(segments));
    }

    private CurrentUser requireSupervisor() {
        CurrentUser user = currentUsers.requireCurrentUser();
        permissions.requireClassroomWrite(user);
        if (!user.roles().contains("SUPERVISOR"))
            throw new AccessDeniedException("SUPERVISOR segment override required");
        return user;
    }

    private CurrentUser requireSupervisorReader() {
        CurrentUser user = currentUsers.requireCurrentUser();
        if (!user.roles().contains("SUPERVISOR"))
            throw new AccessDeniedException("SUPERVISOR segment directory required");
        return user;
    }
}
