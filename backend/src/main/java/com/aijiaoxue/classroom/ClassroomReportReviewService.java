package com.aijiaoxue.classroom;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Supervisor review of an AI report: annotate the conclusions with a written reason (S2-03),
 * then confirm to freeze the reviewed version the teacher reads (S2-04).
 *
 * <p>Reads use the same viewer gate as the report itself, so a teacher may read the confirmed
 * version but can never see a draft review. Writes additionally require CLASSROOM_WRITE plus the
 * SUPERVISOR role, and a confirmation is one-way: once frozen, the snapshot is never rewritten.
 */
@Service
public class ClassroomReportReviewService {
    private static final Set<String> KINDS = Set.of(ClassroomReportReview.KIND_TOPIC,
            ClassroomReportReview.KIND_CHANGE, ClassroomReportReview.KIND_INTERPRETATION);
    private static final Set<String> VERDICTS = Set.of(ClassroomReportReview.VERDICT_ISSUE,
            ClassroomReportReview.VERDICT_ACKNOWLEDGED);
    private static final int NOTE_MAX = 1000;

    private final ClassroomReportReviewStore reviews;
    private final ClassroomGeneratedReportStore generatedReports;
    private final ClassroomReportNotificationService notifications;
    private final ClassroomAnalysisService analysis;
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final ObjectMapper json;

    ClassroomReportReviewService(ClassroomReportReviewStore reviews,
                                 ClassroomGeneratedReportStore generatedReports,
                                 ClassroomReportNotificationService notifications,
                                 ClassroomAnalysisService analysis,
                                 CurrentUserProvider currentUsers,
                                 PermissionService permissions,
                                 ObjectMapper json) {
        this.reviews = reviews;
        this.generatedReports = generatedReports;
        this.notifications = notifications;
        this.analysis = analysis;
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.json = json;
    }

    /**
     * The review of the currently visible report, or null when nobody has reviewed it yet.
     * A draft review is the supervisor's working note, so teachers read the confirmed version
     * instead and never see an unconfirmed annotation.
     */
    @Transactional(readOnly = true)
    public ClassroomReportReview.Review review(long mediaId) {
        requireSupervisorReader();
        analysis.requireGeneratedReportViewerFor(mediaId);
        var stored = generatedReports.visibleForMedia(mediaId);
        if (stored == null) return null;
        var row = reviews.find(stored.id());
        return row == null ? null : view(row);
    }

    /** Saves the draft annotations. An existing confirmation is immutable and rejects the edit. */
    @Transactional
    public ClassroomReportReview.Review save(long mediaId, List<ClassroomReportReview.Annotation> input) {
        CurrentUser user = requireSupervisor();
        var stored = requireVisibleReport(mediaId);
        List<ClassroomReportReview.Annotation> annotations = validate(input);
        var row = reviews.draftFor(stored.id(), mediaId, user.userId());
        if (row == null) throw new IllegalStateException("Report review row is missing");
        if (row.confirmed())
            throw new ClassroomAnalysisException("REVIEW_ALREADY_CONFIRMED",
                    "该报告版本已完成复核确认，不能再修改", 409);
        reviews.replaceAnnotations(row.id(), annotations);
        return view(reviews.find(stored.id()));
    }

    /** S2-04: freezes the reviewed report. Confirming twice returns the same published version. */
    @Transactional
    public ClassroomReportReview.Review confirm(long mediaId) {
        requireSupervisor();
        var stored = requireVisibleReport(mediaId);
        var row = reviews.find(stored.id());
        if (row == null)
            throw new ClassroomAnalysisException("REVIEW_NOT_FOUND", "尚未保存复核记录", 404);
        if (row.confirmed()) return view(row);
        List<ClassroomReportReview.Annotation> annotations = reviews.annotations(row.id());
        if (annotations.isEmpty())
            throw new ClassroomAnalysisException("REVIEW_ANNOTATION_REQUIRED",
                    "至少需要一条带说明的复核标注", 400);
        for (ClassroomReportReview.Annotation annotation : annotations)
            if (annotation.note() == null || annotation.note().isBlank())
                throw new ClassroomAnalysisException("REVIEW_NOTE_REQUIRED",
                        "每条复核标注都必须填写说明", 400);
        reviews.freeze(row.id(), snapshot(row.reportId(), row.mediaId(), annotations));
        // S2-05: publishing is what notifies the offering's teachers; the guard inside makes a
        // repeated confirmation a no-op rather than a second notification.
        notifications.notifyPublishedReport(mediaId, row.id());
        return view(reviews.find(stored.id()));
    }

    /** S2-04: the frozen snapshot a teacher reads. Null while nothing is confirmed for this media. */
    @Transactional(readOnly = true)
    public JsonNode confirmedReport(long mediaId) {
        analysis.requireGeneratedReportViewerFor(mediaId);
        var row = reviews.latestConfirmedForMedia(mediaId);
        if (row == null || row.snapshotJson() == null) return null;
        try {
            return json.readTree(row.snapshotJson());
        } catch (Exception failure) {
            throw new IllegalStateException("Stored review snapshot is invalid", failure);
        }
    }

    /**
     * The snapshot embeds the same public report shape the teacher already reads, so the frozen
     * version can never drift from what was on screen when the supervisor confirmed it.
     */
    private String snapshot(long reportId, long mediaId, List<ClassroomReportReview.Annotation> annotations) {
        JsonNode publicReport = analysis.generatedReport(mediaId);
        if (publicReport == null) throw new ClassroomAnalysisException("REVIEW_REPORT_NOT_READY",
                "当前没有可确认的报告版本", 409);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("reportId", reportId);
        payload.put("report", publicReport);
        payload.put("annotations", annotations);
        try {
            return json.writeValueAsString(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot serialize review snapshot", failure);
        }
    }

    private ClassroomGeneratedReportStore.StoredReport requireVisibleReport(long mediaId) {
        analysis.requireGeneratedReportViewerFor(mediaId);
        var stored = generatedReports.visibleForMedia(mediaId);
        if (stored == null) throw new ClassroomAnalysisException("REVIEW_REPORT_NOT_READY",
                "当前没有可复核的报告版本", 409);
        return stored;
    }

    private CurrentUser requireSupervisor() {
        CurrentUser user = currentUsers.requireCurrentUser();
        permissions.requireClassroomWrite(user);
        if (!user.roles().contains("SUPERVISOR"))
            throw new AccessDeniedException("SUPERVISOR report review required");
        return user;
    }

    private CurrentUser requireSupervisorReader() {
        CurrentUser user = currentUsers.requireCurrentUser();
        if (!user.roles().contains("SUPERVISOR"))
            throw new AccessDeniedException("SUPERVISOR report review required");
        return user;
    }

    private static List<ClassroomReportReview.Annotation> validate(List<ClassroomReportReview.Annotation> input) {
        if (input == null) throw new ClassroomAnalysisException("REVIEW_ANNOTATION_REQUIRED",
                "复核标注不能为空", 400);
        Set<String> seen = new HashSet<>();
        List<ClassroomReportReview.Annotation> cleaned = new ArrayList<>(input.size());
        for (ClassroomReportReview.Annotation annotation : input) {
            if (annotation == null || !KINDS.contains(annotation.targetKind())
                    || annotation.targetIndex() < 0 || !VERDICTS.contains(annotation.verdict()))
                throw new ClassroomAnalysisException("REVIEW_TARGET_INVALID",
                        "复核标注的目标或结论不合法", 400);
            String note = annotation.note() == null ? "" : annotation.note().trim();
            if (note.isEmpty()) throw new ClassroomAnalysisException("REVIEW_NOTE_REQUIRED",
                    "每条复核标注都必须填写说明", 400);
            if (note.length() > NOTE_MAX) throw new ClassroomAnalysisException("REVIEW_NOTE_TOO_LONG",
                    "复核说明超过 " + NOTE_MAX + " 字", 400);
            if (!seen.add(annotation.targetKind() + "#" + annotation.targetIndex()))
                throw new ClassroomAnalysisException("REVIEW_TARGET_DUPLICATE", "同一目标只能标注一次", 400);
            cleaned.add(new ClassroomReportReview.Annotation(annotation.targetKind(),
                    annotation.targetIndex(), annotation.verdict(), note));
        }
        return List.copyOf(cleaned);
    }

    private ClassroomReportReview.Review view(ClassroomReportReviewStore.Row row) {
        if (row == null) return null;
        return new ClassroomReportReview.Review(row.id(), row.reportId(), row.mediaId(), row.confirmed(),
                row.confirmedAt(), row.revision(), reviews.annotations(row.id()));
    }
}
