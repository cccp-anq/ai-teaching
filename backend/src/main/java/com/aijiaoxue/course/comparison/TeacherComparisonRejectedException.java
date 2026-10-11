package com.aijiaoxue.course.comparison;

import org.springframework.http.HttpStatus;

public final class TeacherComparisonRejectedException extends RuntimeException {
    private final HttpStatus httpStatus;
    private final String code;

    private TeacherComparisonRejectedException(HttpStatus httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public static TeacherComparisonRejectedException invalidSelection(String message) {
        return new TeacherComparisonRejectedException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static TeacherComparisonRejectedException unknownTeacher(long teacherId) {
        return new TeacherComparisonRejectedException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_TEACHER",
                "Unknown teacherId: " + teacherId);
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
