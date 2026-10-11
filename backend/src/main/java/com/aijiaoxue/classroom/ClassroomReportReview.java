package com.aijiaoxue.classroom;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 2 supervisor review of an AI generated report (S2-03 / S2-04).
 *
 * <p>Annotations are the traceable record: each one names the AI conclusion it judges and
 * carries the supervisor's written reason. Confirmation freezes the reviewed report into an
 * immutable snapshot, which is the version a teacher reads.
 */
public final class ClassroomReportReview {
    public static final String KIND_TOPIC = "TOPIC";
    public static final String KIND_CHANGE = "CHANGE";
    public static final String KIND_INTERPRETATION = "INTERPRETATION";
    public static final String VERDICT_ISSUE = "ISSUE";
    public static final String VERDICT_ACKNOWLEDGED = "ACKNOWLEDGED";

    private ClassroomReportReview() {}

    /** One judgement on one AI conclusion. {@code note} is the reason and is never blank. */
    public record Annotation(String targetKind, int targetIndex, String verdict, String note) {}

    public record Review(long reviewId, long reportId, long mediaId, boolean confirmed,
                         LocalDateTime confirmedAt, int revision, List<Annotation> annotations) {}
}
