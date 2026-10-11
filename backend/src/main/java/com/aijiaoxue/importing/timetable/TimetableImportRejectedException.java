package com.aijiaoxue.importing.timetable;

import org.springframework.http.HttpStatus;

public final class TimetableImportRejectedException extends RuntimeException {
    private final HttpStatus httpStatus;
    private final TimetableImportResult result;

    TimetableImportRejectedException(HttpStatus httpStatus, TimetableImportResult result) {
        super(result.errors().isEmpty() ? result.status().name() : result.errors().get(0).reason());
        this.httpStatus = httpStatus;
        this.result = result;
    }

    public HttpStatus httpStatus() { return httpStatus; }
    public TimetableImportResult result() { return result; }
}
