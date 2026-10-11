package com.aijiaoxue.course.lookup;

/**
 * Minimal read-only college selection option. It serves both the timetable import
 * source-scope picker and the offering query source-scope filter; it never implies
 * formal course ownership.
 */
public record CollegeLookup(long collegeId, String collegeCode, String collegeName) {}
