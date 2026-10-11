package com.aijiaoxue.classroom;

import com.aijiaoxue.common.api.ApiError;
import com.aijiaoxue.common.web.RequestIdFilter;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;

@RestControllerAdvice(assignableTypes=ClassroomAnalysisController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClassroomAnalysisExceptionHandler {
    @ExceptionHandler(ClassroomAnalysisException.class)
    ResponseEntity<ApiError> handle(ClassroomAnalysisException error, ServletWebRequest request) {
        var servlet=request.getRequest();
        return ResponseEntity.status(error.status()).body(new ApiError(error.code(),error.getMessage(),
                servlet.getRequestURI(),(String)servlet.getAttribute(RequestIdFilter.ATTRIBUTE),List.of()));
    }

    @ExceptionHandler(ClassroomModuleContract.ContractViolation.class)
    ResponseEntity<ApiError> handleContract(ClassroomModuleContract.ContractViolation error, ServletWebRequest request) {
        var servlet=request.getRequest();
        return ResponseEntity.unprocessableEntity().body(new ApiError("MODULE_CONTRACT_VIOLATION",error.getMessage(),
                servlet.getRequestURI(),(String)servlet.getAttribute(RequestIdFilter.ATTRIBUTE),List.of()));
    }
}
