package com.aijiaoxue.action;

import com.aijiaoxue.common.api.ApiError;
import com.aijiaoxue.common.web.RequestIdFilter;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;

@RestControllerAdvice(assignableTypes = TeacherActionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TeacherActionExceptionHandler {
    @ExceptionHandler(TeacherActionException.class)
    ResponseEntity<ApiError> handle(TeacherActionException error, ServletWebRequest request) {
        var servlet = request.getRequest();
        return ResponseEntity.status(error.status()).body(new ApiError(error.code(), error.getMessage(),
                servlet.getRequestURI(), (String) servlet.getAttribute(RequestIdFilter.ATTRIBUTE), List.of()));
    }
}
