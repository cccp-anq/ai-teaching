package com.aijiaoxue.importing.timetable;

/** Stable parser error codes for preview and later import orchestration. */
public enum TimetableErrorCode {
    COURSES_NOT_FOUND,
    COURSES_ARRAY_MALFORMED,
    UNSUPPORTED_ENCODING,
    INVALID_SOURCE_DOCUMENT,
    INVALID_ROW_OBJECT,
    MISSING_REQUIRED_FIELD,
    INVALID_FIELD_TYPE,
    BLANK_NAME,
    INVALID_ACTIVITY_TYPE,
    INVALID_STUDENT_COUNT,
    INVALID_WEEK_MASK,
    INVALID_WEEKDAY,
    INVALID_SECTION_RANGE,
    EMPTY_TEACHER,
    EMPTY_LOCATION,
    UNKNOWN_LOCATION_GROUP
}
