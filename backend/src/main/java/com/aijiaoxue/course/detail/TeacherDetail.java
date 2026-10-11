package com.aijiaoxue.course.detail;

/** Public teacher identity for course detail; internal employee numbers are not exposed. */
public record TeacherDetail(long teacherId, String displayName) {}
