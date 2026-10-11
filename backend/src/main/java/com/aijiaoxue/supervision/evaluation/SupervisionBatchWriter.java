package com.aijiaoxue.supervision.evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Transactional tail of the DAT-04 evaluation import: one batch record plus every
 * evaluation row in a single local transaction. Multiple evaluation records for the
 * same offering are preserved verbatim (require1 §6.2; dedupe happens only inside the
 * coverage predicate, never in the import).
 */
@Component
public class SupervisionBatchWriter {
    private static final List<DateTimeFormatter> DATETIMES = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"));

    private final JdbcTemplate jdbc;

    public SupervisionBatchWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public SupervisionImportResult apply(String fileName, String sha256, long operatorId,
                                         List<NormalizedEvaluation> rows) {
        jdbc.queryForObject("SELECT id FROM role WHERE role_code='ADMIN' FOR UPDATE", Long.class);
        var existing = jdbc.queryForList(
                "SELECT id FROM import_batch WHERE source_type='SUPERVISION_EVALUATION_TEXT' AND source_sha256=? " +
                        "AND status='SUCCESS' ORDER BY id DESC LIMIT 1", Long.class, sha256);
        if (!existing.isEmpty()) {
            long batchId = existing.get(0);
            Integer totalRows = jdbc.queryForObject("SELECT total_rows FROM import_batch WHERE id=?", Integer.class, batchId);
            return SupervisionImportResult.alreadyImported(batchId, totalRows == null ? 0 : totalRows);
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO import_batch (source_type, source_namespace, source_title, source_scope_college_id, " +
                        "semester_id, source_file_name, source_sha256, parser_version, total_rows, success_rows, " +
                        "failure_rows, status, operator_user_id, started_at, finished_at) " +
                        "VALUES ('SUPERVISION_EVALUATION_TEXT', 'SUPERVISION_EVALUATION_TEXT_V1', ?, NULL, NULL, ?, ?, " +
                        "'supervision-evaluation-text-v1', ?, ?, 0, 'SUCCESS', ?, ?, ?)",
                fileName, fileName, sha256, rows.size(), rows.size(), operatorId, now, now);
        Long batchId = jdbc.queryForObject(
                "SELECT id FROM import_batch WHERE source_type='SUPERVISION_EVALUATION_TEXT' AND source_sha256=? " +
                        "AND status='SUCCESS' ORDER BY id DESC LIMIT 1", Long.class, sha256);
        for (NormalizedEvaluation row : rows) {
            jdbc.update("INSERT INTO supervision_evaluation (course_offering_id, status, completed_at, created_at, updated_at) " +
                            "VALUES (?,?,?,?,?)", row.offeringId(), row.status(), row.completedAt(), now, now);
        }
        return new SupervisionImportResult(batchId, SupervisionImportStatus.SUCCESS,
                rows.size(), rows.size(), 0, rows.size(), List.of());
    }

    /** Returns the parsed completion time; null stays null (a non-completed record has none). */
    public static Timestamp completedAt(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        for (DateTimeFormatter formatter : DATETIMES) {
            try { return Timestamp.valueOf(LocalDateTime.parse(trimmed, formatter)); }
            catch (java.time.format.DateTimeParseException ignored) { }
        }
        for (DateTimeFormatter formatter : DATES) {
            try { return Timestamp.valueOf(java.time.LocalDate.parse(trimmed, formatter).atStartOfDay()); }
            catch (java.time.format.DateTimeParseException ignored) { }
        }
        throw new java.time.format.DateTimeParseException("unsupported completion time", trimmed, 0);
    }
}
