package com.aijiaoxue.action;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Storage for teacher-owned actions. Every statement is keyed by the owner, so a row can
 * only ever be read or written by the user it belongs to.
 */
@Repository
class TeacherActionRepository {
    private static final String COLUMNS =
            "id,media_id,source_kind,source_index,title,content,status,revision,created_at,updated_at";

    private final JdbcTemplate jdbc;

    TeacherActionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<TeacherActionView> listForOwner(long ownerUserId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM teacher_action WHERE owner_user_id=? ORDER BY id DESC",
                TeacherActionRepository::map, ownerUserId);
    }

    TeacherActionView findOwned(long actionId, long ownerUserId) {
        List<TeacherActionView> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM teacher_action WHERE id=? AND owner_user_id=?", TeacherActionRepository::map,
                actionId, ownerUserId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    long insert(long ownerUserId, Long mediaId, String sourceKind, Integer sourceIndex,
                String title, String content) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO teacher_action(owner_user_id,media_id,source_kind,source_index,
                    title,content,status,revision) VALUES (?,?,?,?,?,?,'PLANNED',1)""",
                    new String[]{"id"});
            statement.setLong(1, ownerUserId);
            if (mediaId == null) statement.setNull(2, Types.BIGINT); else statement.setLong(2, mediaId);
            statement.setString(3, sourceKind);
            if (sourceIndex == null) statement.setNull(4, Types.INTEGER); else statement.setInt(4, sourceIndex);
            statement.setString(5, title);
            statement.setString(6, content);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    int updateContent(long actionId, long ownerUserId, String title, String content) {
        return jdbc.update("""
                UPDATE teacher_action SET title=?,content=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND owner_user_id=?""", title, content, actionId, ownerUserId);
    }

    int updateStatus(long actionId, long ownerUserId, String status) {
        return jdbc.update("""
                UPDATE teacher_action SET status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND owner_user_id=?""", status, actionId, ownerUserId);
    }

    private static TeacherActionView map(ResultSet rs, int rowNum) throws SQLException {
        return new TeacherActionView(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3),
                (Integer) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getInt(8), rs.getTimestamp(9).toLocalDateTime(), rs.getTimestamp(10).toLocalDateTime());
    }
}
