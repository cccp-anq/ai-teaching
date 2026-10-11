package com.aijiaoxue.action;

import org.springframework.http.HttpStatus;

/** A rejected teacher-action request. The code is the machine-readable part of the error body. */
public class TeacherActionException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public TeacherActionException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }

    public HttpStatus status() { return status; }
}
