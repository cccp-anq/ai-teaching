package com.aijiaoxue.supervision.evaluation;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import com.aijiaoxue.importing.timetable.TimetableImportError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DAT-04 evaluation import orchestration. Follows the unified importing pattern
 * (CurrentUser → TIMETABLE_IMPORT → text constraints → habit-tolerant parse → per-row
 * validation → one transaction → result). The same normalized text is idempotent;
 * multiple records per offering are preserved.
 */
@Service
public class SupervisionImportService {
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_STATUS = 32;

    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final SupervisionBatchWriter writer;
    private final JdbcTemplate jdbc;

    public SupervisionImportService(CurrentUserProvider currentUsers, PermissionService permissions,
                                    SupervisionBatchWriter writer, JdbcTemplate jdbc) {
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.writer = writer;
        this.jdbc = jdbc;
    }

    public SupervisionImportResult importEvaluations(String sourceName, String content) {
        CurrentUser currentUser = currentUsers.requireCurrentUser();
        permissions.requireTimetableImport(currentUser);
        String safeName = sourceName == null || sourceName.isBlank() ? "manual-input.txt" : sourceName.trim();

        if (!safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".txt"))
            throw SupervisionImportRejectedException.rejected(422, 0,
                    List.of(error(null, "file", "FILE_TYPE_UNSUPPORTED", "仅支持文本框内容或 UTF-8 TXT 文件")));
        String canonicalText = EvaluationTextParser.canonicalize(content);
        byte[] bytes = canonicalText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length == 0)
            throw SupervisionImportRejectedException.rejected(422, 0,
                    List.of(error(null, "text", "EMPTY_TEXT", "请输入或上传督导评价文本")));
        if (bytes.length > MAX_FILE_BYTES)
            throw SupervisionImportRejectedException.fileTooLarge();

        var event = EvaluationTextParser.parse(canonicalText);
        if (event instanceof EvaluationTextParser.Fatal fatal)
            throw SupervisionImportRejectedException.rejected(422, 0,
                    List.of(error(null, null, "Parser", fatal.errorCode(), fatal.reason())));
        List<EvaluationTextRow> rows = ((EvaluationTextParser.Parsed) event).rows();

        List<TimetableImportError> errors = new ArrayList<>();
        List<NormalizedEvaluation> normalized = new ArrayList<>();
        Set<Long> knownOfferings = new HashSet<>();
        for (EvaluationTextRow row : rows) {
            int errorsBefore = errors.size();
            long offeringId = -1;
            try {
                offeringId = Long.parseLong(row.offeringId().trim());
                if (offeringId <= 0) throw new NumberFormatException();
            } catch (RuntimeException parseFailure) {
                errors.add(error(row.rowIndex(), "offeringId", "OFFERING_ID_INVALID",
                        "offeringId must be a positive integer"));
                continue;
            }
            if (knownOfferings.add(offeringId)) {
                var found = jdbc.queryForList("SELECT id FROM course_offering WHERE id=?", Long.class, offeringId);
                if (found.isEmpty()) {
                    errors.add(error(row.rowIndex(), "offeringId", "OFFERING_UNKNOWN",
                            "offering " + offeringId + " does not exist"));
                    continue;
                }
            }
            String status = normalizeStatus(row.status());
            if (status.isEmpty() || status.length() > MAX_STATUS)
                errors.add(error(row.rowIndex(), "status", "STATUS_INVALID",
                        "status must be a non-empty string of at most " + MAX_STATUS + " characters"));
            Timestamp completedAt = null;
            if (!row.completedAt().isBlank()) {
                try {
                    completedAt = SupervisionBatchWriter.completedAt(row.completedAt());
                } catch (RuntimeException formatFailure) {
                    errors.add(error(row.rowIndex(), "completedAt", "COMPLETED_AT_INVALID",
                            "完成时间应为常用日期或日期时间，例如 2026-09-15 10:30"));
                }
            }
            if (errors.size() == errorsBefore)
                normalized.add(new NormalizedEvaluation(row.rowIndex(), offeringId, status, completedAt));
        }
        if (!errors.isEmpty())
            throw SupervisionImportRejectedException.rejected(422, rows.size(), errors);
        return writer.apply(safeName, sha256(bytes), currentUser.userId(), normalized);
    }

    private static String normalizeStatus(String value) {
        String status = value == null ? "" : value.trim();
        if (status.equalsIgnoreCase("COMPLETED") || status.equals("已完成") || status.equals("完成"))
            return "COMPLETED";
        if (status.equalsIgnoreCase("RECORDED") || status.equals("已记录") || status.equals("记录"))
            return "RECORDED";
        return status;
    }

    private static TimetableImportError error(Integer sourceIndex, String field, String errorCode, String reason) {
        return new TimetableImportError(sourceIndex, field, "Supervision", errorCode, reason);
    }

    private static TimetableImportError error(Integer sourceIndex, String field, String domain,
                                              String errorCode, String reason) {
        return new TimetableImportError(sourceIndex, field, domain, errorCode, reason);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
