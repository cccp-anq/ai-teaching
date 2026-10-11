package com.aijiaoxue.classroom;

public final class ClassroomAnalysisException extends RuntimeException {
    private final String code;
    private final int status;
    public ClassroomAnalysisException(String code, String message, int status) {
        super(message); this.code = code; this.status = status;
    }
    public String code() { return code; }
    public int status() { return status; }
}
