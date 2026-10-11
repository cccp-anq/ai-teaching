package com.aijiaoxue.course.web;

import com.aijiaoxue.common.api.ApiError;
import com.aijiaoxue.common.web.RequestIdFilter;
import com.aijiaoxue.course.comparison.TeacherComparisonRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TeacherComparisonController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TeacherComparisonExceptionHandler {
    @ExceptionHandler(TeacherComparisonRejectedException.class)
    ResponseEntity<ApiError> rejected(TeacherComparisonRejectedException exception, HttpServletRequest request) {
        var fields = List.of(new ApiError.FieldViolation("teacherIds", "Invalid value"));
        var error = new ApiError(exception.code(), exception.getMessage(), request.getRequestURI(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE), fields);
        return ResponseEntity.status(exception.httpStatus()).body(error);
    }
}
