package com.aijiaoxue.supervision.web;

import com.aijiaoxue.supervision.evaluation.SupervisionImportRejectedException;
import com.aijiaoxue.supervision.evaluation.SupervisionImportResult;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SupervisionImportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SupervisionImportExceptionHandler {
    @ExceptionHandler(SupervisionImportRejectedException.class)
    ResponseEntity<SupervisionImportResult> rejected(SupervisionImportRejectedException exception) {
        return ResponseEntity.status(exception.httpStatus()).body(exception.result());
    }

}
