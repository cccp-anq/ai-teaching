package com.aijiaoxue.importing.web;

import com.aijiaoxue.importing.timetable.TimetableImportRejectedException;
import com.aijiaoxue.importing.timetable.TimetableImportResult;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TimetableImportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TimetableImportExceptionHandler {
    @ExceptionHandler(TimetableImportRejectedException.class)
    ResponseEntity<TimetableImportResult> rejected(TimetableImportRejectedException exception) {
        return ResponseEntity.status(exception.httpStatus()).body(exception.result());
    }

}
