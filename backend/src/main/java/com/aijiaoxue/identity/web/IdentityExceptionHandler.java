package com.aijiaoxue.identity.web;

import com.aijiaoxue.common.api.ApiError;
import com.aijiaoxue.common.web.RequestIdFilter;
import com.aijiaoxue.identity.NotVisibleException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityExceptionHandler {
    @ExceptionHandler(NotVisibleException.class)
    ResponseEntity<ApiError> invisible(NotVisibleException failure, HttpServletRequest request) {
        // Engineering default only; final 403 vs 404 business policy remains TBD.
        return response(404,"NOT_VISIBLE",request);
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException failure, HttpServletRequest request) {
        return response(403,"FORBIDDEN",request);
    }
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthenticated(AuthenticationException failure, HttpServletRequest request) {
        return response(401,failure instanceof DisabledException ? "DISABLED_ACCOUNT" : "UNAUTHENTICATED",request);
    }
    private ResponseEntity<ApiError> response(int status, String code, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(code,"Access denied",request.getRequestURI(),
                (String)request.getAttribute(RequestIdFilter.ATTRIBUTE),List.of()));
    }
}
