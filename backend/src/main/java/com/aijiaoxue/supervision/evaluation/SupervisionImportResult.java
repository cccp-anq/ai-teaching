package com.aijiaoxue.supervision.evaluation;

import com.aijiaoxue.importing.timetable.TimetableImportError;

import java.util.List;

public record SupervisionImportResult(
        Long batchId,
        SupervisionImportStatus status,
        int totalRows,
        int successRows,
        int failureRows,
        int importedEvaluations,
        List<TimetableImportError> errors) {
    public SupervisionImportResult {
        errors = List.copyOf(errors);
    }

    public static SupervisionImportResult rejected(int totalRows, List<TimetableImportError> errors) {
        int failedRows = totalRows > 0 ? totalRows : (errors.isEmpty() ? 0 : 1);
        return new SupervisionImportResult(null, SupervisionImportStatus.REJECTED,
                totalRows, 0, failedRows, 0, errors);
    }

    public static SupervisionImportResult alreadyImported(long batchId, int totalRows) {
        return new SupervisionImportResult(batchId, SupervisionImportStatus.ALREADY_IMPORTED,
                totalRows, totalRows, 0, 0, List.of());
    }
}
