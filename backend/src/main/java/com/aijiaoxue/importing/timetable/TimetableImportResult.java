package com.aijiaoxue.importing.timetable;

import java.util.List;

public record TimetableImportResult(
        Long batchId,
        TimetableImportStatus status,
        int totalRows,
        int successRows,
        int failureRows,
        int createdOfferings,
        int reusedOfferings,
        int createdSchedules,
        int reusedSchedules,
        int createdTeachers,
        int reusedTeachers,
        int createdLocations,
        int reusedLocations,
        List<TimetableImportError> errors
) {
    public TimetableImportResult {
        errors = List.copyOf(errors);
    }

    public static TimetableImportResult rejected(TimetableImportStatus status, int totalRows,
                                                  List<TimetableImportError> errors) {
        return new TimetableImportResult(null, status, totalRows, 0, totalRows,
                0, 0, 0, 0, 0, 0, 0, 0, errors);
    }
}
