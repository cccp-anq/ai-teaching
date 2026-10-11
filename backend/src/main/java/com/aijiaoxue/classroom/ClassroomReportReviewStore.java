package com.aijiaoxue.classroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for the supervisor review layer (S2-03 / S2-04).
 *
 * <p>A review is a mutable draft until it is confirmed. Confirmation is one-way: the frozen
 * snapshot is written once and never updated, which is what keeps a published report stable
 * while the AI keeps producing new versions.
 */
@Service
class ClassroomReportReviewStore {
    private final JdbcTemplate jdbc;

    ClassroomReportReviewStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Row(long id, long reportId, long mediaId, LocalDateTime confirmedAt, String snapshotJson,
               int revision) {
        boolean confirmed() { return confirmedAt != null; }
    }

    @Transactional(readOnly = true)
    Row find(long reportId) {
        List<Row> rows = jdbc.query("""
                SELECT id,report_id,media_id,confirmed_at,snapshot_json,revision
                FROM classroom_report_review WHERE report_id=?""",
                (rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toLocalDateTime(),
                        rs.getString(5), rs.getInt(6)), reportId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The most recently confirmed review of this media, regardless of whether a newer AI version
     * exists. This is the version a teacher reads, so a later re-generation cannot take it away.
     */
    @Transactional(readOnly = true)
    Row latestConfirmedForMedia(long mediaId) {
        List<Row> rows = jdbc.query("""
                SELECT id,report_id,media_id,confirmed_at,snapshot_json,revision
                FROM classroom_report_review WHERE media_id=? AND confirmed_at IS NOT NULL
                ORDER BY confirmed_at DESC,id DESC LIMIT 1""",
                (rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toLocalDateTime(),
                        rs.getString(5), rs.getInt(6)), mediaId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    List<ClassroomReportReview.Annotation> annotations(long reviewId) {
        return jdbc.query("""
                SELECT target_kind,target_index,verdict,note
                FROM classroom_report_review_annotation WHERE review_id=?
                ORDER BY target_kind,target_index""",
                (rs, n) -> new ClassroomReportReview.Annotation(rs.getString(1), rs.getInt(2),
                        rs.getString(3), rs.getString(4)), reviewId);
    }

    /** Returns the draft row for this report, creating it on first annotation. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    Row draftFor(long reportId, long mediaId, long reviewerUserId) {
        Row existing = find(reportId);
        if (existing != null) {
            if (!existing.confirmed())
                jdbc.update("""
                        UPDATE classroom_report_review SET reviewer_user_id=?,revision=revision+1,
                        updated_at=CURRENT_TIMESTAMP WHERE id=?""", reviewerUserId, existing.id());
            return existing;
        }
        var key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO classroom_report_review(report_id,media_id,reviewer_user_id,revision)
                        VALUES (?,?,?,1)""", new String[]{"id"});
                statement.setLong(1, reportId);
                statement.setLong(2, mediaId);
                statement.setLong(3, reviewerUserId);
                return statement;
            }, key);
        } catch (DuplicateKeyException concurrent) {
            return find(reportId);
        }
        return find(reportId) == null ? null : new Row(key.getKey().longValue(), reportId, mediaId,
                null, null, 1);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void replaceAnnotations(long reviewId, List<ClassroomReportReview.Annotation> annotations) {
        jdbc.update("DELETE FROM classroom_report_review_annotation WHERE review_id=?", reviewId);
        for (ClassroomReportReview.Annotation annotation : annotations)
            jdbc.update("""
                    INSERT INTO classroom_report_review_annotation(review_id,target_kind,target_index,
                    verdict,note) VALUES (?,?,?,?,?)""",
                    reviewId, annotation.targetKind(), annotation.targetIndex(),
                    annotation.verdict(), annotation.note());
    }

    /**
     * Freezes the reviewed report. Returns false when the review was already confirmed, so the
     * caller can serve the existing snapshot instead of overwriting a published version.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    boolean freeze(long reviewId, String snapshotJson) {
        return jdbc.update("""
                UPDATE classroom_report_review SET confirmed_at=CURRENT_TIMESTAMP,snapshot_sha256=?,
                snapshot_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND confirmed_at IS NULL""",
                sha256(snapshotJson), snapshotJson, reviewId) == 1;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
