package com.aijiaoxue.classroom;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S2-05: tells the teachers of an offering that a confirmed report is available.
 *
 * <p>Recipients come from the offering's teaching relation, not from a caller-supplied list, and
 * the unique index plus the NOT EXISTS guard make a repeated confirmation a no-op instead of a
 * second notification. Reads are always scoped to the authenticated user.
 */
@Service
public class ClassroomReportNotificationService {
    private static final String COLUMNS = "id,media_id,offering_id,kind,state,created_at,read_at";

    private final JdbcTemplate jdbc;
    private final ClassroomAnalysisRepository repository;
    private final CurrentUserProvider currentUsers;

    ClassroomReportNotificationService(JdbcTemplate jdbc, ClassroomAnalysisRepository repository,
                                       CurrentUserProvider currentUsers) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.currentUsers = currentUsers;
    }

    /** Returns how many notifications were created; replaying the same review creates none. */
    @Transactional
    public int notifyPublishedReport(long mediaId, long reviewId) {
        var access = repository.mediaAccess(mediaId);
        if (access == null) return 0;
        return jdbc.update("""
                INSERT INTO classroom_report_notification(media_id,offering_id,recipient_user_id,
                review_id,kind,state)
                SELECT DISTINCT ?,?,ua.id,?,?,'UNREAD'
                FROM user_account ua
                JOIN schedule_teacher st ON st.teacher_id=ua.teacher_id
                JOIN class_schedule cs ON cs.id=st.schedule_id
                WHERE ua.enabled=TRUE AND cs.offering_id=?
                  AND NOT EXISTS (SELECT 1 FROM classroom_report_notification n
                                  WHERE n.media_id=? AND n.recipient_user_id=ua.id AND n.review_id=?)""",
                mediaId, access.offeringId(), reviewId,
                TeacherReportNotification.KIND_REPORT_PUBLISHED, access.offeringId(), mediaId, reviewId);
    }

    @Transactional(readOnly = true)
    public List<TeacherReportNotification> listMine() {
        return jdbc.query("SELECT " + COLUMNS + " FROM classroom_report_notification"
                        + " WHERE recipient_user_id=? ORDER BY id DESC",
                ClassroomReportNotificationService::map, currentUsers.requireCurrentUser().userId());
    }

    /** Owner-scoped: a foreign or missing identifier is answered identically. */
    @Transactional
    public TeacherReportNotification markRead(long notificationId) {
        CurrentUser user = currentUsers.requireCurrentUser();
        jdbc.update("""
                UPDATE classroom_report_notification SET state='READ',read_at=CURRENT_TIMESTAMP
                WHERE id=? AND recipient_user_id=? AND state='UNREAD'""",
                notificationId, user.userId());
        List<TeacherReportNotification> rows = jdbc.query("SELECT " + COLUMNS
                        + " FROM classroom_report_notification WHERE id=? AND recipient_user_id=?",
                ClassroomReportNotificationService::map, notificationId, user.userId());
        if (rows.isEmpty()) throw new ClassroomAnalysisException("NOTIFICATION_NOT_FOUND", "通知不存在", 404);
        return rows.get(0);
    }

    private static TeacherReportNotification map(ResultSet rs, int rowNum) throws SQLException {
        return new TeacherReportNotification(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6).toLocalDateTime(),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toLocalDateTime());
    }
}
