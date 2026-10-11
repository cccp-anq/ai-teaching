package com.aijiaoxue.classroom;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for the S2-10 segment override layer.
 *
 * <p>One row per (report version, segment): saving the same segment again updates that row and
 * bumps {@code revision} instead of appending history rows. The revision counter plus the editor
 * is the 修改留痕 the story asks for -- the report itself is never touched.
 */
@Service
class ClassroomSegmentOverrideStore {
    private final JdbcTemplate jdbc;

    ClassroomSegmentOverrideStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    List<ClassroomSegmentOverride.Stored> forReport(long reportId) {
        return jdbc.query("""
                SELECT id,report_id,media_id,segment_key,title,start_ms,end_ms,edited_by_user_id,
                       revision,updated_at
                FROM classroom_segment_override WHERE report_id=? ORDER BY segment_key""",
                (rs, n) -> new ClassroomSegmentOverride.Stored(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getInt(9), rs.getTimestamp(10).toLocalDateTime()), reportId);
    }

    /** Media length is the upper bound of a legal segment range; media rows are immutable. */
    @Transactional(readOnly = true)
    long mediaDurationMs(long mediaId) {
        List<Long> rows = jdbc.query("SELECT duration_ms FROM classroom_media WHERE id=?",
                (rs, n) -> rs.getLong(1), mediaId);
        if (rows.isEmpty()) throw new IllegalStateException("Classroom media is missing");
        return rows.get(0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    ClassroomSegmentOverride.Stored save(long reportId, long mediaId, String segmentKey, String title,
                                         long startMs, long endMs, long editedByUserId) {
        if (update(reportId, segmentKey, title, startMs, endMs, editedByUserId) == 0) {
            try {
                jdbc.update("""
                        INSERT INTO classroom_segment_override(report_id,media_id,segment_key,title,start_ms,
                        end_ms,edited_by_user_id,revision) VALUES (?,?,?,?,?,?,?,1)""",
                        reportId, mediaId, segmentKey, title, startMs, endMs, editedByUserId);
            } catch (DuplicateKeyException concurrent) {
                // Two supervisors saved the same segment at once; the row now exists, so update it.
                if (update(reportId, segmentKey, title, startMs, endMs, editedByUserId) == 0)
                    throw new IllegalStateException("Segment override vanished during save");
            }
        }
        ClassroomSegmentOverride.Stored row = find(reportId, segmentKey);
        if (row == null) throw new IllegalStateException("Segment override is missing after save");
        return row;
    }

    private int update(long reportId, String segmentKey, String title, long startMs, long endMs,
                       long editedByUserId) {
        return jdbc.update("""
                UPDATE classroom_segment_override SET title=?,start_ms=?,end_ms=?,edited_by_user_id=?,
                revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE report_id=? AND segment_key=?""",
                title, startMs, endMs, editedByUserId, reportId, segmentKey);
    }

    private ClassroomSegmentOverride.Stored find(long reportId, String segmentKey) {
        List<ClassroomSegmentOverride.Stored> rows = jdbc.query("""
                SELECT id,report_id,media_id,segment_key,title,start_ms,end_ms,edited_by_user_id,
                       revision,updated_at
                FROM classroom_segment_override WHERE report_id=? AND segment_key=?""",
                (rs, n) -> new ClassroomSegmentOverride.Stored(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getInt(9), rs.getTimestamp(10).toLocalDateTime()), reportId, segmentKey);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
