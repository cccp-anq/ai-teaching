package com.aijiaoxue.importing.timetable;

import java.util.Objects;

/** Stable, source-locatable import rejection detail. */
public record TimetableImportError(
        Integer sourceIndex,
        String field,
        String domain,
        String errorCode,
        String reason
) {
    public TimetableImportError {
        domain = Objects.requireNonNull(domain, "domain");
        errorCode = Objects.requireNonNull(errorCode, "errorCode");
        reason = Objects.requireNonNull(reason, "reason");
    }

    static TimetableImportError parser(TimetableValidationError error) {
        return new TimetableImportError(error.sourceIndex(), error.field(), "Parser",
                error.errorCode().name(), error.reason());
    }
}
