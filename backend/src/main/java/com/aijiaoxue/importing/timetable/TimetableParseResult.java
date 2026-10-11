package com.aijiaoxue.importing.timetable;

import java.util.List;

/** Immutable parser output reusable by import execution and a future DAT-05 preview. */
public record TimetableParseResult(
        List<TimetableSourceRow> parsedRows,
        List<TimetableValidationError> validationErrors,
        int totalSourceRows
) {
    public TimetableParseResult {
        parsedRows = List.copyOf(parsedRows);
        validationErrors = List.copyOf(validationErrors);
        if (totalSourceRows < 0 || parsedRows.size() > totalSourceRows) {
            throw new IllegalArgumentException("invalid source row counts");
        }
    }

    public static TimetableParseResult fatal(TimetableErrorCode code, String reason) {
        return new TimetableParseResult(
                List.of(), List.of(TimetableValidationError.fatal(code, reason)), 0);
    }

    public int validRowCount() {
        return parsedRows.size();
    }

    public int invalidRowCount() {
        return totalSourceRows - validRowCount();
    }

    public boolean hasFatalError() {
        return validationErrors.stream().anyMatch(TimetableValidationError::fatal);
    }

    public List<TimetableValidationError> fatalErrors() {
        return validationErrors.stream().filter(TimetableValidationError::fatal).toList();
    }
}
