package com.aijiaoxue.common.api;

import java.util.List;

public record ApiError(String code, String message, String path, String requestId,
                       List<FieldViolation> fieldErrors) {
    public ApiError { fieldErrors = List.copyOf(fieldErrors); }
    public record FieldViolation(String field, String message) {}
}
