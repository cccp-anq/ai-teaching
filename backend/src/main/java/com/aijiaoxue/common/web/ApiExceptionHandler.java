package com.aijiaoxue.common.web;

import java.util.List;
import com.aijiaoxue.common.api.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.aijiaoxue.importing.timetable.TimetableImportError;
import com.aijiaoxue.importing.timetable.TimetableImportResult;
import com.aijiaoxue.importing.timetable.TimetableImportStatus;
import com.aijiaoxue.supervision.evaluation.SupervisionImportRejectedException;
import com.aijiaoxue.supervision.web.SupervisionImportBodyLimit;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers,HttpStatusCode status,WebRequest request) {
        Throwable cause=ex;
        while(cause!=null) {
            if(cause instanceof SupervisionImportBodyLimit.BodyTooLarge) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(SupervisionImportRejectedException.fileTooLarge().result());
            }
            cause=cause.getCause();
        }
        return super.handleHttpMessageNotReadable(ex,headers,status,request);
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldViolation> fields = ex instanceof MethodArgumentNotValidException invalid
            ? invalid.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiError.FieldViolation(e.getField(), "Invalid value")).toList()
            : List.of();
        String code = fields.isEmpty() ? "HTTP_" + status.value() : "VALIDATION_FAILED";
        return new ResponseEntity<>(error(code, fields.isEmpty() ? "Request rejected" : "Validation failed", fields, request), headers, status);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        LOG.error("Unhandled request failure", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(error("INTERNAL_ERROR", "Internal server error", List.of(), request));
    }
    private ApiError error(String code, String message, List<ApiError.FieldViolation> fields, WebRequest request) {
        var servlet = ((ServletWebRequest) request).getRequest();
        return new ApiError(code, message, servlet.getRequestURI(),
                (String) servlet.getAttribute(RequestIdFilter.ATTRIBUTE), fields);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ignored, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        Object body;
        if (path.matches("^/api/v1/course-offerings/[^/]+/class-sessions/video$")) {
            body = error("VIDEO_TOO_LARGE", "Video exceeds the configured upload limit", List.of(), request);
        } else if (path.equals("/api/v1/imports/supervision-evaluations")) {
            body = SupervisionImportRejectedException.fileTooLarge().result();
        } else if (path.equals("/api/v1/imports/timetable")) {
            body = TimetableImportResult.rejected(TimetableImportStatus.FAILED, 0, List.of(
                    new TimetableImportError(null, "file", "Parser", "FILE_TOO_LARGE",
                            "HTML file exceeds the 5 MiB limit")));
        } else {
            body = error("UPLOAD_TOO_LARGE", "Upload exceeds the configured limit", List.of(), request);
        }
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }
}
