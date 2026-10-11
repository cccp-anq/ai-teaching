package com.aijiaoxue.importing.timetable;

import java.util.Objects;

/** A file-level fatal error or a 1-based COURSES row error. */
public record TimetableValidationError(
        String source,
        Integer sourceIndex,
        String field,
        TimetableErrorCode errorCode,
        String reason,
        String rawValue,
        boolean fatal
) {
    public static final String COURSES_SOURCE = "COURSES";

    public TimetableValidationError {
        source = Objects.requireNonNull(source, "source");
        errorCode = Objects.requireNonNull(errorCode, "errorCode");
        reason = Objects.requireNonNull(reason, "reason");
        if (sourceIndex != null && sourceIndex < 1) {
            throw new IllegalArgumentException("sourceIndex must be 1-based");
        }
        if (fatal && sourceIndex != null) {
            throw new IllegalArgumentException("fatal document errors cannot have a sourceIndex");
        }
    }

    public static TimetableValidationError fatal(TimetableErrorCode code, String reason) {
        return new TimetableValidationError(COURSES_SOURCE, null, null, code, reason, null, true);
    }

    public static TimetableValidationError row(int sourceIndex, String field,
                                                TimetableErrorCode code, String reason,
                                                String rawValue) {
        return new TimetableValidationError(
                COURSES_SOURCE, sourceIndex, field, code, reason, rawValue, false);
    }

    public String location() {
        String row = sourceIndex == null ? source : source + "[" + sourceIndex + "]";
        return field == null || field.isBlank() ? row : row + "." + field;
    }

    @Override
    public String toString() {
        String suffix = rawValue == null ? "" : "; rawValue=" + rawValue;
        return location() + ": " + errorCode + ": " + reason + suffix;
    }
}
