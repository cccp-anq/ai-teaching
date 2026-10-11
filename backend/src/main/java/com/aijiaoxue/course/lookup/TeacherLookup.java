package com.aijiaoxue.course.lookup;

/**
 * Minimal display identity of a teacher visible inside the caller's authorized dataset
 * (Authorized Dataset → ScheduleTeacher → Teacher). It is not a full teacher directory:
 * teachers without any authorized offering relation are never enumerable.
 */
public record TeacherLookup(long teacherId, String displayName) {}
